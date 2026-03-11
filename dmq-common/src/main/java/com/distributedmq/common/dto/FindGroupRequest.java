package com.distributedmq.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request to find or create a consumer group
 * Sent by consumer to bootstrap metadata service
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FindGroupRequest {
    
    /**
     * Topic the consumer wants to subscribe to
     */
    private String topic;
    
    /**
     * Application ID - identifies the consumer application
     * Equivalent to Kafka's group.id
     * Combined with topic, uniquely identifies a consumer group
     */
    private String appId;
}
