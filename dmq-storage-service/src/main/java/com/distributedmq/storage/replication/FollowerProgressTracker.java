package com.distributedmq.storage.replication;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks the replication progress (Log End Offset) of each follower broker
 * for each partition where this broker is the leader.
 * 
 * Used by CatchUpReplicator to detect lagging followers and initiate catch-up replication.
 */
@Slf4j
@Component
public class FollowerProgressTracker {

    /**
     * Nested map structure: topic -> partition -> followerId -> LEO
     * Tracks the last known Log End Offset for each follower
     */
    private final Map<String, Map<Integer, Map<Integer, FollowerProgress>>> progressMap = new ConcurrentHashMap<>();

    /**
     * Progress information for a single follower
     */
    public static class FollowerProgress {
        private long logEndOffset;
        private long lastUpdated;

        public FollowerProgress(long logEndOffset, long lastUpdated) {
            this.logEndOffset = logEndOffset;
            this.lastUpdated = lastUpdated;
        }

        public long getLogEndOffset() {
            return logEndOffset;
        }

        public long getLastUpdated() {
            return lastUpdated;
        }

        public void update(long newLEO) {
            this.logEndOffset = newLEO;
            this.lastUpdated = System.currentTimeMillis();
        }
    }

    /**
     * Update the progress of a follower for a partition
     * Called when receiving replication acknowledgment or lag report
     */
    public void updateFollowerProgress(String topic, Integer partition, Integer followerId, Long followerLEO) {
        if (topic == null || partition == null || followerId == null || followerLEO == null) {
            log.warn("Invalid progress update: topic={}, partition={}, followerId={}, LEO={}",
                    topic, partition, followerId, followerLEO);
            return;
        }

        progressMap
            .computeIfAbsent(topic, k -> new ConcurrentHashMap<>())
            .computeIfAbsent(partition, k -> new ConcurrentHashMap<>())
            .compute(followerId, (k, existing) -> {
                if (existing == null) {
                    log.debug("Tracking new follower progress: {}-{} follower {} at LEO {}",
                            topic, partition, followerId, followerLEO);
                    return new FollowerProgress(followerLEO, System.currentTimeMillis());
                } else {
                    log.trace("Updated follower progress: {}-{} follower {} from LEO {} to {}",
                            topic, partition, followerId, existing.getLogEndOffset(), followerLEO);
                    existing.update(followerLEO);
                    return existing;
                }
            });
    }

    /**
     * Get the last known LEO for a follower
     * Returns null if no progress has been recorded
     */
    public Long getFollowerLEO(String topic, Integer partition, Integer followerId) {
        Map<Integer, Map<Integer, FollowerProgress>> topicMap = progressMap.get(topic);
        if (topicMap == null) {
            return null;
        }

        Map<Integer, FollowerProgress> partitionMap = topicMap.get(partition);
        if (partitionMap == null) {
            return null;
        }

        FollowerProgress progress = partitionMap.get(followerId);
        return progress != null ? progress.getLogEndOffset() : null;
    }

    /**
     * Check if a follower is lagging behind the leader
     * 
     * @param topic Topic name
     * @param partition Partition ID
     * @param followerId Follower broker ID
     * @param leaderLEO Leader's current Log End Offset
     * @param threshold Lag threshold in messages (e.g., 50)
     * @return true if follower is lagging by more than threshold, false otherwise
     */
    public boolean isFollowerLagging(String topic, Integer partition, Integer followerId, 
                                     Long leaderLEO, long threshold) {
        if (leaderLEO == null) {
            return false; // No leader data, can't determine lag
        }

        Long followerLEO = getFollowerLEO(topic, partition, followerId);
        
        if (followerLEO == null) {
            // No progress data for this follower yet
            // Consider it lagging to trigger initial catch-up
            log.debug("No progress data for follower {} on {}-{}, considering it lagging",
                    followerId, topic, partition);
            return true;
        }

        long lag = leaderLEO - followerLEO;
        boolean isLagging = lag > threshold;

        if (isLagging) {
            log.debug("Follower {} is lagging on {}-{}: leader LEO {}, follower LEO {}, lag {} messages (threshold: {})",
                    followerId, topic, partition, leaderLEO, followerLEO, lag, threshold);
        }

        return isLagging;
    }

    /**
     * Get all follower IDs being tracked for a partition
     */
    public Map<Integer, FollowerProgress> getFollowersForPartition(String topic, Integer partition) {
        Map<Integer, Map<Integer, FollowerProgress>> topicMap = progressMap.get(topic);
        if (topicMap == null) {
            return Map.of();
        }

        Map<Integer, FollowerProgress> partitionMap = topicMap.get(partition);
        return partitionMap != null ? partitionMap : Map.of();
    }

    /**
     * Remove follower tracking (e.g., when follower is removed from replica set)
     */
    public void removeFollower(String topic, Integer partition, Integer followerId) {
        Map<Integer, Map<Integer, FollowerProgress>> topicMap = progressMap.get(topic);
        if (topicMap == null) {
            return;
        }

        Map<Integer, FollowerProgress> partitionMap = topicMap.get(partition);
        if (partitionMap != null) {
            partitionMap.remove(followerId);
            log.info("Removed follower {} tracking for {}-{}", followerId, topic, partition);
        }
    }

    /**
     * Clear all tracking for a partition (e.g., when partition is deleted)
     */
    public void clearPartition(String topic, Integer partition) {
        Map<Integer, Map<Integer, FollowerProgress>> topicMap = progressMap.get(topic);
        if (topicMap != null) {
            topicMap.remove(partition);
            log.info("Cleared all follower tracking for {}-{}", topic, partition);
        }
    }

    /**
     * Get statistics for monitoring/debugging
     */
    public String getProgressStats() {
        StringBuilder stats = new StringBuilder();
        stats.append("Follower Progress Tracker Stats:\n");
        stats.append("================================\n");

        progressMap.forEach((topic, partitions) -> {
            partitions.forEach((partition, followers) -> {
                stats.append(String.format("\nTopic: %s, Partition: %d\n", topic, partition));
                followers.forEach((followerId, progress) -> {
                    long age = System.currentTimeMillis() - progress.getLastUpdated();
                    stats.append(String.format("  Follower %d: LEO=%d, last_updated=%dms ago\n",
                            followerId, progress.getLogEndOffset(), age));
                });
            });
        });

        return stats.toString();
    }
}
