package com.distributedmq.metadata.service;

import com.distributedmq.common.dto.ISRLagReport;
import com.distributedmq.metadata.coordination.MetadataStateMachine;
import com.distributedmq.metadata.coordination.PartitionInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Phase 3: ISR Auto-Management
 * 
 * Processes lag reports from storage services (followers) and automatically
 * manages ISR membership based on replica lag.
 * 
 * Decisions:
 * - Remove from ISR: If offsetLag > threshold OR timeSinceCaughtUp > threshold
 * - Add to ISR: If offsetLag < 100 AND timeSinceCaughtUp < 1s AND is replica AND not in ISR
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ISRLagProcessor {
    
    private final MetadataServiceImpl metadataService;
    private final MetadataStateMachine metadataStateMachine;
    
    // Configuration thresholds (should come from config)
    private final long replicaLagTimeMaxMs = 10000L;      // 10 seconds
    private final long replicaLagMaxMessages = 4000L;     // 4000 messages
    private final long catchUpOffsetThreshold = 100L;     // < 100 messages to be "caught up"
    private final long catchUpTimeThreshold = 1000L;      // < 1 second since caught up
    private final int minISR = 1;                         // Minimum ISR size (at least 1 replica must be in ISR)
    
    /**
     * Process a lag report from a follower broker
     * Evaluates each partition and decides whether to add/remove from ISR
     */
    public void processLagReport(ISRLagReport report) {
        log.debug("Processing lag report from broker {} with {} partitions",
                report.getBrokerId(), 
                report.getPartitions() != null ? report.getPartitions().size() : 0);
        
        if (report.getPartitions() == null || report.getPartitions().isEmpty()) {
            log.debug("Empty lag report from broker {}, nothing to process", report.getBrokerId());
            return;
        }
        
        // Process each partition in the report
        for (ISRLagReport.PartitionLag lag : report.getPartitions()) {
            try {
                processPartitionLag(lag, report.getBrokerId());
            } catch (Exception e) {
                log.error("Error processing lag for partition {}-{} from broker {}: {}",
                        lag.getTopic(), lag.getPartition(), report.getBrokerId(), e.getMessage(), e);
            }
        }
    }
    
    /**
     * Process lag for a single partition
     * Makes decision to add or remove replica from ISR
     */
    private void processPartitionLag(ISRLagReport.PartitionLag lag, Integer brokerId) {
        String topic = lag.getTopic();
        Integer partition = lag.getPartition();
        
        log.debug("Processing partition {}-{} from broker {}: offsetLag={}, timeSinceCaughtUp={}ms, isOutOfSync={}",
                topic, partition, brokerId, lag.getOffsetLag(), lag.getTimeSinceCaughtUp(), lag.getIsOutOfSync());
        
        // Get current partition state from Raft
        PartitionInfo partitionInfo = metadataStateMachine.getPartition(topic, partition);
        
        if (partitionInfo == null) {
            log.warn("Partition {}-{} not found in metadata, ignoring lag report from broker {}",
                    topic, partition, brokerId);
            return;
        }
        
        // Check if broker is a replica for this partition
        if (!partitionInfo.getReplicaIds().contains(brokerId)) {
            log.warn("Broker {} is not a replica for partition {}-{}, ignoring lag report",
                    brokerId, topic, partition);
            return;
        }
        
        // Check current ISR membership
        boolean isInISR = partitionInfo.getIsrIds().contains(brokerId);
        
        // Decision 1: Should remove from ISR?
        if (isInISR && shouldRemoveFromISR(lag)) {
            // Safety check: Do not remove if it would violate minimum ISR
            if (partitionInfo.getIsrIds().size() <= minISR) {
                log.warn("Cannot remove broker {} from ISR for {}-{}: would violate minimum ISR requirement (current ISR size: {}, min: {})",
                        brokerId, topic, partition, partitionInfo.getIsrIds().size(), minISR);
                return; // Skip removal
            }
            
            log.warn("Removing broker {} from ISR for {}-{} due to lag: offsetLag={}, timeSinceCaughtUp={}ms",
                    brokerId, topic, partition, lag.getOffsetLag(), lag.getTimeSinceCaughtUp());
            
            try {
                metadataService.removeFromISR(topic, partition, brokerId);
                log.info("Successfully removed broker {} from ISR for {}-{}", brokerId, topic, partition);
            } catch (Exception e) {
                log.error("Failed to remove broker {} from ISR for {}-{}: {}",
                        brokerId, topic, partition, e.getMessage(), e);
            }
        }
        // Decision 2: Should add to ISR?
        else if (!isInISR && shouldAddToISR(lag)) {
            log.info("Adding broker {} to ISR for {}-{} (caught up): offsetLag={}, timeSinceCaughtUp={}ms",
                    brokerId, topic, partition, lag.getOffsetLag(), lag.getTimeSinceCaughtUp());
            
            try {
                metadataService.addToISR(topic, partition, brokerId);
                log.info("Successfully added broker {} to ISR for {}-{}", brokerId, topic, partition);
            } catch (Exception e) {
                log.error("Failed to add broker {} to ISR for {}-{}: {}",
                        brokerId, topic, partition, e.getMessage(), e);
            }
        }
        // No action needed
        else {
            if (isInISR) {
                log.debug("Broker {} remains in ISR for {}-{} (in-sync)", brokerId, topic, partition);
            } else {
                log.debug("Broker {} remains out of ISR for {}-{} (not caught up yet)", brokerId, topic, partition);
            }
        }
    }
    
    /**
     * Determine if replica should be removed from ISR
     * Remove if:
     * - Offset lag exceeds message threshold (> 4000), OR
     * - Time since caught up exceeds time threshold (> 10s)
     */
    private boolean shouldRemoveFromISR(ISRLagReport.PartitionLag lag) {
        // Check offset lag threshold
        if (lag.getOffsetLag() != null && lag.getOffsetLag() > replicaLagMaxMessages) {
            log.debug("Replica should be removed: offsetLag {} > threshold {}",
                    lag.getOffsetLag(), replicaLagMaxMessages);
            return true;
        }
        
        // Check time lag threshold
        if (lag.getTimeSinceCaughtUp() != null && lag.getTimeSinceCaughtUp() > replicaLagTimeMaxMs) {
            log.debug("Replica should be removed: timeSinceCaughtUp {}ms > threshold {}ms",
                    lag.getTimeSinceCaughtUp(), replicaLagTimeMaxMs);
            return true;
        }
        
        // Can also use the isOutOfSync flag from the report
        if (lag.getIsOutOfSync() != null && lag.getIsOutOfSync()) {
            log.debug("Replica should be removed: isOutOfSync=true");
            return true;
        }
        
        return false;
    }
    
    /**
     * Determine if replica should be added to ISR
     * Add if:
     * - Offset lag is very small (< 100 messages), AND
     * - Recently caught up (< 1 second ago), AND
     * - Is a replica for the partition
     * 
     * Note: Caller already checks if broker is NOT in ISR
     */
    private boolean shouldAddToISR(ISRLagReport.PartitionLag lag) {
        // Check offset lag is small enough
        boolean offsetOK = lag.getOffsetLag() != null && lag.getOffsetLag() < catchUpOffsetThreshold;
        
        // Check time since caught up is recent enough
        boolean timeOK = lag.getTimeSinceCaughtUp() != null && lag.getTimeSinceCaughtUp() < catchUpTimeThreshold;
        
        if (offsetOK && timeOK) {
            log.debug("Replica should be added: offsetLag {} < {}, timeSinceCaughtUp {}ms < {}ms",
                    lag.getOffsetLag(), catchUpOffsetThreshold,
                    lag.getTimeSinceCaughtUp(), catchUpTimeThreshold);
            return true;
        }
        
        log.debug("Replica not ready to add: offsetOK={}, timeOK={}", offsetOK, timeOK);
        return false;
    }
    
    /**
     * Get current thresholds (for monitoring/debugging)
     */
    public long getReplicaLagTimeMaxMs() {
        return replicaLagTimeMaxMs;
    }
    
    public long getReplicaLagMaxMessages() {
        return replicaLagMaxMessages;
    }
    
    public long getCatchUpOffsetThreshold() {
        return catchUpOffsetThreshold;
    }
    
    public long getCatchUpTimeThreshold() {
        return catchUpTimeThreshold;
    }
}
