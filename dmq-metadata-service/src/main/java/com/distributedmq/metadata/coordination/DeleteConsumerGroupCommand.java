package com.distributedmq.metadata.coordination;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * Raft command for deleting a consumer group
 * Submitted when the last member leaves the group
 * Submitted to Raft log for consensus across metadata service cluster
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeleteConsumerGroupCommand implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * Group ID of the consumer group to delete
     */
    private String groupId;
    
    /**
     * Command timestamp (milliseconds since epoch)
     */
    private Long timestamp;
}
