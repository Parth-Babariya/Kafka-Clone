package com.distributedmq.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Per-partition status for storage monitoring and reporting
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PartitionStatus {

    /**
     * Topic name
     */
    private String topic;

    /**
     * Partition ID
     */
    private Integer partition;

    /**
     * Role of this node for this partition (LEADER or FOLLOWER)
     */
    private Role role;

    /**
     * Log End Offset (LEO) of this node for the partition
     */
    private Long leo;

    /**
     * Lag from leader's HWM (for followers only, null for leaders)
     * Calculated as: leaderHWM - localLEO
     */
    private Long lag;

    /**
     * High Water Mark (for leaders only, null for followers)
     */
    private Long hwm;

    /**
     * Metadata version this node has for this partition
     */
    private Long metadataVersion;

    /**
     * Timestamp when this status was collected
     */
    private Long timestamp;

    /**
     * Partition role enum
     */
    public enum Role {
        LEADER,
        FOLLOWER
    }
}