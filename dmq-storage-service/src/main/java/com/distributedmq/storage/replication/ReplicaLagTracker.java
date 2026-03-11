package com.distributedmq.storage.replication;

import com.distributedmq.storage.config.StorageConfig;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Phase 1: ISR Lag Monitoring
 * 
 * Tracks replication lag for each partition where this broker is a follower.
 * Calculates offset lag and time lag to determine if replica is out of sync.
 */
@Slf4j
@Component
public class ReplicaLagTracker {
    
    // Configuration thresholds
    private final long replicaLagTimeMaxMs;
    private final long replicaLagMaxMessages;
    
    // Track lag info per partition
    private final Map<PartitionKey, LagInfo> lagInfoMap = new ConcurrentHashMap<>();
    
    public ReplicaLagTracker(StorageConfig config) {
        // Get thresholds from configuration
        this.replicaLagTimeMaxMs = config.getReplication().getReplicaLagTimeMaxMs();
        this.replicaLagMaxMessages = config.getReplication().getReplicaLagMaxMessages();
        
        log.info("ReplicaLagTracker initialized with thresholds: lagTimeMax={}ms, lagMessagesMax={}",
                replicaLagTimeMaxMs, replicaLagMaxMessages);
    }
    
    /**
     * Record a replication event and update lag tracking
     * Called by follower after processing replication request
     */
    public void recordReplication(String topic, int partition, long leaderLEO, long followerLEO) {
        PartitionKey key = new PartitionKey(topic, partition);
        long now = System.currentTimeMillis();
        
        // Calculate offset lag
        long offsetLag = Math.max(0, leaderLEO - followerLEO);
        
        // Get or create lag info
        LagInfo lagInfo = lagInfoMap.computeIfAbsent(key, k -> new LagInfo());
        
        // Track state transition for logging
        boolean wasOutOfSync = isOutOfSync(lagInfo);
        
        // Update lag info
        lagInfo.setOffsetLag(offsetLag);
        lagInfo.setFollowerLEO(followerLEO);
        lagInfo.setLeaderLEO(leaderLEO);
        lagInfo.setLastFetchTimestamp(now);
        
        // Update last caught up timestamp if currently caught up
        if (offsetLag == 0) {
            lagInfo.setLastCaughtUpTimestamp(now);
        }
        
        // Log state transitions
        boolean isNowOutOfSync = isOutOfSync(lagInfo);
        if (!wasOutOfSync && isNowOutOfSync) {
            log.warn("Replica fell out of sync for {}-{}: offsetLag={}, timeLag={}ms",
                    topic, partition, offsetLag, now - lagInfo.getLastCaughtUpTimestamp());
        } else if (wasOutOfSync && !isNowOutOfSync) {
            log.info("Replica caught up for {}-{}: offsetLag={}", topic, partition, offsetLag);
        }
        
        log.debug("Recorded replication for {}-{}: leaderLEO={}, followerLEO={}, offsetLag={}",
                topic, partition, leaderLEO, followerLEO, offsetLag);
    }
    
    /**
     * Get lag information for a specific partition
     */
    public LagInfo getLagInfo(String topic, int partition) {
        PartitionKey key = new PartitionKey(topic, partition);
        return lagInfoMap.get(key);
    }
    
    /**
     * Check if replica is out of sync based on thresholds
     */
    public boolean isReplicaOutOfSync(String topic, int partition) {
        LagInfo lagInfo = getLagInfo(topic, partition);
        return lagInfo != null && isOutOfSync(lagInfo);
    }
    
    /**
     * Internal method to check if lag info indicates out-of-sync state
     */
    private boolean isOutOfSync(LagInfo lagInfo) {
        if (lagInfo == null) {
            return false;
        }
        
        long now = System.currentTimeMillis();
        long timeSinceLastFetch = now - lagInfo.getLastFetchTimestamp();
        
        // Out of sync if:
        // 1. Offset lag exceeds message threshold, OR
        // 2. Time since last fetch exceeds time threshold
        return lagInfo.getOffsetLag() > replicaLagMaxMessages ||
               timeSinceLastFetch > replicaLagTimeMaxMs;
    }
    
    /**
     * Check if replica is caught up (ready to be added to ISR)
     */
    public boolean isCaughtUp(String topic, int partition) {
        LagInfo lagInfo = getLagInfo(topic, partition);
        if (lagInfo == null) {
            return false;
        }
        
        long now = System.currentTimeMillis();
        long timeSinceCaughtUp = now - lagInfo.getLastCaughtUpTimestamp();
        
        // Caught up if:
        // 1. Offset lag is very small (< 100 messages), AND
        // 2. Recently was at lag=0 (< 1 second ago)
        return lagInfo.getOffsetLag() < 100 && timeSinceCaughtUp < 1000;
    }
    
    /**
     * Get all partitions being tracked
     */
    public List<PartitionKey> getTrackedPartitions() {
        return lagInfoMap.keySet().stream().collect(Collectors.toList());
    }
    
    /**
     * Remove partition from tracking (e.g., when no longer a follower)
     */
    public void removePartition(String topic, int partition) {
        PartitionKey key = new PartitionKey(topic, partition);
        lagInfoMap.remove(key);
        log.debug("Removed lag tracking for partition {}-{}", topic, partition);
    }
    
    /**
     * Clear all lag tracking
     */
    public void clear() {
        lagInfoMap.clear();
        log.info("Cleared all lag tracking");
    }
    
    /**
     * Partition identifier (topic + partition)
     */
    @Data
    public static class PartitionKey {
        private final String topic;
        private final int partition;
        
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            PartitionKey that = (PartitionKey) o;
            return partition == that.partition && Objects.equals(topic, that.topic);
        }
        
        @Override
        public int hashCode() {
            return Objects.hash(topic, partition);
        }
        
        @Override
        public String toString() {
            return topic + "-" + partition;
        }
    }
    
    /**
     * Lag information for a partition
     */
    @Data
    public static class LagInfo {
        private long offsetLag;                 // Messages behind leader
        private long followerLEO;               // Follower's Log End Offset
        private long leaderLEO;                 // Leader's Log End Offset
        private long lastFetchTimestamp;        // Last time replication occurred
        private long lastCaughtUpTimestamp;     // Last time offsetLag was 0
        
        public LagInfo() {
            long now = System.currentTimeMillis();
            this.lastFetchTimestamp = now;
            this.lastCaughtUpTimestamp = now;
            this.offsetLag = 0;
            this.followerLEO = 0;
            this.leaderLEO = 0;
        }
        
        /**
         * Get time since last replication
         */
        public long getTimeSinceLastFetch() {
            return System.currentTimeMillis() - lastFetchTimestamp;
        }
        
        /**
         * Get time since replica was caught up (lag=0)
         */
        public long getTimeSinceCaughtUp() {
            return System.currentTimeMillis() - lastCaughtUpTimestamp;
        }
    }
}
