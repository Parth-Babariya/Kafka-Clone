package com.distributedmq.common.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Batch update of ISR status from storage nodes to controller
 * Sent every 30 seconds to report ISR membership changes and lag issues
 */
@Data
@Builder
public class ISRStatusBatchRequest {

    /**
     * Storage node ID sending the update
     */
    private Integer nodeId;

    /**
     * List of ISR status updates
     */
    private List<ISRStatusUpdate> isrUpdates;

    /**
     * Current metadata version known by this storage node
     */
    private Long metadataVersion;

    /**
     * Timestamp when this batch was created
     */
    private Long timestamp;
}