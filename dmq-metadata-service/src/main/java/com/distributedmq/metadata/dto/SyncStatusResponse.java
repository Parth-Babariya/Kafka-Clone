package com.distributedmq.metadata.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response DTO for sync status
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SyncStatusResponse {

    private boolean isControllerLeader;
    private Long lastSyncTimestamp;
    private Integer activeBrokers;
    private Integer totalTopics;
    private String status; // HEALTHY, DEGRADED, etc.
}