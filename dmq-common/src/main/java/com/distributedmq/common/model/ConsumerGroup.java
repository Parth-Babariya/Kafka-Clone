package com.distributedmq.common.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * Consumer Group domain model
 * Represents minimal consumer group information for routing consumers to group leaders
 * 
 * Design Philosophy:
 * - Metadata service stores only routing information
 * - Group leader (Storage Service/Broker) manages operational state in-memory
 * - Consumers discover their group leader through metadata service
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConsumerGroup implements Serializable {
    private static final long serialVersionUID = 1L;
    
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
     * Application ID - uniquely identifies the consumer application
     * Equivalent to Kafka's group.id
     */
    private String appId;
    
    /**
     * Broker ID of the Storage Service acting as group leader/coordinator
     */
    private Integer groupLeaderBrokerId;
    
    /**
     * URL of the group leader broker
     * Format: host:port
     * Example: localhost:8081
     */
    private String groupLeaderUrl;
    
    /**
     * Timestamp when the group was created (milliseconds since epoch)
     */
    private Long createdAt;
    
    /**
     * Timestamp when the group was last modified (milliseconds since epoch)
     */
    private Long lastModifiedAt;
    
    /**
     * Generate group ID from topic and app ID
     */
    public static String generateGroupId(String topic, String appId) {
        return "G_" + topic + "_" + appId;
    }
}
