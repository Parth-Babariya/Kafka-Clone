package com.distributedmq.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Response to a produce request (supports batching)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProduceResponse {
    private String topic;
    private Integer partition;
    private List<ProduceResult> results; // Results for each message in batch
    private Long throttleTimeMs;
    private String errorMessage;
    private ErrorCode errorCode;
    private boolean success;

    /**
     * Result for individual message in batch
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProduceResult {
        private Long offset;
        private Long timestamp;
        private ErrorCode errorCode;
        private String errorMessage;
    }

    /**
     * Error codes for produce operations
     */
    public enum ErrorCode {
        NONE(0, "No error"),
        UNKNOWN_TOPIC_OR_PARTITION(3, "Unknown topic or partition"),
        INVALID_REQUEST(10, "Invalid request"),
        UNAUTHORIZED(16, "Authentication required or insufficient permissions"),
        NOT_LEADER_FOR_PARTITION(6, "Not leader for partition"),
        MESSAGE_TOO_LARGE(10, "Message too large"),
        INVALID_PRODUCER_EPOCH(47, "Invalid producer epoch"),
        DUPLICATE_SEQUENCE_NUMBER(34, "Duplicate sequence number"),
        TIMEOUT(7, "Request timeout");

        private final int code;
        private final String message;

        ErrorCode(int code, String message) {
            this.code = code;
            this.message = message;
        }

        public int getCode() { return code; }
        public String getMessage() { return message; }
    }
}
