package com.distributedmq.common.dto;

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Response from follower broker after processing replication request
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReplicationResponse {
    private String topic;
    private Integer partition;
    private Integer followerId; // Follower broker ID
    private Long baseOffset; // Base offset that was replicated
    private Integer messageCount; // Number of messages replicated
    private boolean success;
    private ErrorCode errorCode;
    private String errorMessage;

    // Replication timing
    private Long replicationTimeMs;

    // Lag tracking (Phase 1: ISR Lag Monitoring)
    private Long followerLogEndOffset;      // Follower's current LEO after replication
    private Long leaderLogEndOffset;        // Leader's LEO (from request)
    private Long offsetLag;                 // LEO difference (messages behind)
    private Long lastCaughtUpTimestamp;     // Last time follower was at lag=0
    private Long timeSinceLastFetch;        // Time since last replication (ms)

    public enum ErrorCode {
        NONE,
        @JsonEnumDefaultValue
        UNKNOWN,
        INVALID_REQUEST,
        NOT_FOLLOWER_FOR_PARTITION,
        OFFSET_OUT_OF_ORDER,
        STORAGE_ERROR,
        TIMEOUT
    }
}