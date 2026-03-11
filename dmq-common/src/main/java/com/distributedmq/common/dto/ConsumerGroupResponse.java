package com.distributedmq.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response containing consumer group information
 * Returned by metadata service to consumer with group leader details
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConsumerGroupResponse {
    
    /**
     * Group ID in format: G_<topic>_<appId>
     * Example: G_orders_order_processor
     */
    private String groupId;
    
    /**
     * Topic this consumer group subscribes to
     */
    private String topic;
    
    /**
     * Application ID
     */
    private String appId;
    
    /**
     * Broker ID of the group leader (coordinator)
     */
    private Integer groupLeaderBrokerId;
    
    /**
     * URL of the group leader broker
     * Format: host:port
     * Example: localhost:8081
     * 
     * Consumer uses this to connect to the group leader for join/heartbeat/commit operations
     */
    private String groupLeaderUrl;
    
    /**
     * Timestamp when the group was created
     */
    private Long createdAt;
    
    /**
     * Timestamp when the group was last modified
     */
    private Long lastModifiedAt;
}
