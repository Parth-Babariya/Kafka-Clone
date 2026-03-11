package com.distributedmq.metadata.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Response DTO for full sync operations
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FullSyncResponse {

    private List<String> topics;
    private List<BrokerResponse> brokers;
    private Long syncTimestamp;
    private boolean fullSyncPerformed;
}