package com.distributedmq.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * Consumer join group request
 * Sent when consumer wants to join a group
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConsumerJoinRequest {
    
    /**
     * Consumer unique identifier
     */
    private String consumerId;
    
    /**
     * Consumer group ID
     */
    private String groupId;
    
    /**
     * Topic to consume from
     */
    private String topic;
    
    /**
     * Application ID (group name)
     */
    private String appId;
    
    /**
     * Consumer's local offset state for partitions it knows about
     * partition -> offset
     * Only includes partitions with meaningful local data (omits zeros)
     */
    @Builder.Default
    private Map<Integer, Long> offsetState = new HashMap<>();
}
