package com.distributedmq.metadata.coordination;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * Raft command for registering a new consumer group
 * Submitted to Raft log for consensus across metadata service cluster
 * 
 * Creates minimal routing entry: (topic, app_id) -> group_leader_broker_id
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RegisterConsumerGroupCommand implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * Generated group ID in format: G_<topic>_<appId>
     */
    private String groupId;
    
    /**
     * Topic this consumer group subscribes to
     */
    private String topic;
    
    /**
     * Application ID - uniquely identifies the consumer application
     */
    private String appId;
    
    /**
     * Broker ID of the Storage Service acting as group leader/coordinator
     */
    private Integer groupLeaderBrokerId;
    
    /**
     * Command timestamp (milliseconds since epoch)
     */
    private Long timestamp;
}
