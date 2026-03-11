package com.distributedmq.metadata.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Response DTO for broker-specific sync operations
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BrokerSyncResponse {

    private BrokerResponse broker;
    private List<String> topics;
    private Long syncTimestamp;
}