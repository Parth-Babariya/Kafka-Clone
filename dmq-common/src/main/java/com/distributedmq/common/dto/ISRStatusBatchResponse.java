package com.distributedmq.common.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Response to ISR status batch update
 */
@Data
@Builder
public class ISRStatusBatchResponse {

    /**
     * Whether the batch was processed successfully
     */
    private boolean success;

    /**
     * Current controller metadata version
     */
    private Long controllerMetadataVersion;

    /**
     * Response timestamp
     */
    private Long responseTimestamp;

    /**
     * Error message if any
     */
    private String errorMessage;

    /**
     * Instructions from controller (e.g., ISR membership changes)
     */
    private List<String> instructions;
}