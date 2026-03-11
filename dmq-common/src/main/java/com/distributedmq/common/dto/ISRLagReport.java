package com.distributedmq.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Phase 2: ISR Lag Reporting
 * 
 * Report sent from storage service (follower) to metadata service (controller)
 * containing lag information for all partitions where this broker is a follower.
 * 
 * Sent periodically (every 15 seconds) or immediately on state changes.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ISRLagReport {
    
    /**
     * ID of the broker sending this report
     */
    private Integer brokerId;
    
    /**
     * List of partition lag information
     */
    private List<PartitionLag> partitions;
    
    /**
     * Timestamp when this report was generated
     */
    private Long timestamp;
    
    /**
     * Lag information for a single partition
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PartitionLag {
        
        /**
         * Topic name
         */
        private String topic;
        
        /**
         * Partition ID
         */
        private Integer partition;
        
        /**
         * Number of messages behind the leader
         */
        private Long offsetLag;
        
        /**
         * Time in milliseconds since the follower was last caught up (lag=0)
         */
        private Long timeSinceCaughtUp;
        
        /**
         * Follower's current Log End Offset
         */
        private Long followerLEO;
        
        /**
         * Leader's Log End Offset (from last replication request)
         */
        private Long leaderLEO;
        
        /**
         * Whether this replica is out of sync based on thresholds
         * true = should be removed from ISR
         * false = in sync or catching up
         */
        private Boolean isOutOfSync;
        
        /**
         * Time since last fetch/replication in milliseconds
         */
        private Long timeSinceLastFetch;
    }
}
