package com.distributedmq.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response from storage node after processing metadata update
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MetadataUpdateResponse {
    private boolean success;
    private ErrorCode errorCode;
    private String errorMessage;
    private Long processedTimestamp;
    private Integer brokerId; // The broker that processed this update

    public enum ErrorCode {
        NONE,
        INVALID_REQUEST,
        PROCESSING_ERROR,
        PARTIAL_UPDATE
    }
}