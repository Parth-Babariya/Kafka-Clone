package com.distributedmq.metadata.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response DTO for sync trigger operations
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SyncTriggerResponse {

    private boolean success;
    private Integer brokersTriggered;
    private Integer topicsSynced;
    private Long triggerTimestamp;
}