package com.distributedmq.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Acknowledgment sent by follower to leader after successful replication
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReplicationAck {
    private String topic;
    private Integer partition;
    private Integer followerId; // Follower broker ID
    private Long baseOffset; // Base offset that was acknowledged
    private Integer messageCount; // Number of messages acknowledged
    private Long logEndOffset; // Follower's LEO after replication
    private Long highWaterMark; // Follower's HW after replication
    private boolean success;
    private ErrorCode errorCode;
    private String errorMessage;

    public enum ErrorCode {
        NONE,
        INVALID_REQUEST,
        OFFSET_MISMATCH,
        REPLICATION_FAILED
    }
}