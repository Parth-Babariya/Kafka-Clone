package com.distributedmq.common.dto;

import lombok.Builder;
import lombok.Data;

/**
 * Individual ISR status update within a batch
 */
@Data
@Builder
public class ISRStatusUpdate {

    /**
     * Type of ISR status update
     */
    public enum UpdateType {
        LAG_THRESHOLD_BREACHED,    // Follower lag exceeded threshold
        LAG_THRESHOLD_RECOVERED,   // Follower lag returned below threshold
        ISR_MEMBERSHIP_CHANGED,    // ISR membership was modified
        PARTITION_BECAME_LEADER,   // This node became leader
        PARTITION_BECAME_FOLLOWER  // This node became follower
    }

    private String topic;
    private Integer partition;
    private UpdateType updateType;

    /**
     * Broker ID affected by this update (the one that's lagging or recovering)
     */
    private Integer brokerId;

    /**
     * Current lag (for lag-related updates)
     */
    private Long currentLag;

    /**
     * Lag threshold that was breached/recovered
     */
    private Long lagThreshold;

    /**
     * Whether this node is currently in ISR
     */
    private Boolean isInISR;

    /**
     * Timestamp when this update was detected
     */
    private Long timestamp;

    /**
     * Additional context or details
     */
    private String details;
}