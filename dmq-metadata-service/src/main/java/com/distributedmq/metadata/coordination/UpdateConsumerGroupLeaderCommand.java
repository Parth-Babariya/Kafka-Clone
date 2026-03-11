package com.distributedmq.metadata.coordination;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * Raft command for updating consumer group leader
 * Used when the current group leader broker fails and a new leader is assigned
 * Submitted to Raft log for consensus across metadata service cluster
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateConsumerGroupLeaderCommand implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * Group ID of the consumer group to update
     */
    private String groupId;
    
    /**
     * New broker ID to act as group leader/coordinator
     */
    private Integer newGroupLeaderBrokerId;
    
    /**
     * Command timestamp (milliseconds since epoch)
     */
    private Long timestamp;
}
