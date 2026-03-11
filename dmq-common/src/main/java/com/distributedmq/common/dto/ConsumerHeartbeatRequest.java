package com.distributedmq.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * Consumer heartbeat/commit request
 * Sent every 10 seconds by consumers
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConsumerHeartbeatRequest {
    
    /**
     * Consumer unique identifier
     */
    private String consumerId;
    
    /**
     * Consumer group ID
     */
    private String groupId;
    
    /**
     * Current offsets for assigned partitions
     * partition -> current offset
     */
    @Builder.Default
    private Map<Integer, Long> offsets = new HashMap<>();
}
