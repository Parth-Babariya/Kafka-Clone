package com.distributedmq.metadata.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Request DTO for sync trigger operations
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SyncTriggerRequest {

    private List<Integer> brokers;
    private List<String> topics;
}