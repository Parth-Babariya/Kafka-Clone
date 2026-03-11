package com.distributedmq.metadata.coordination;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * In-memory state for consumer group in MetadataStateMachine
 * Minimal information needed for routing consumers to group leaders
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConsumerGroupInfo {
    
    /**
     * Group ID in format: G_<topic>_<appId>
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
     * Broker ID of the group leader/coordinator
     */
    private Integer groupLeaderBrokerId;
    
    /**
     * Timestamp when the group was created
     */
    private Long createdAt;
    
    /**
     * Timestamp when the group was last modified
     */
    private Long lastModifiedAt;
}
