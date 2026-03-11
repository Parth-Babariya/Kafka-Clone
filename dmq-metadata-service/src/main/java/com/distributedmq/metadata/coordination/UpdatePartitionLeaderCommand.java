package com.distributedmq.metadata.coordination;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * Raft command for updating partition leader
 * Used during leader election when current leader fails
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdatePartitionLeaderCommand implements Serializable {
    private static final long serialVersionUID = 1L;

    private String topicName;
    private int partitionId;
    private int newLeaderId;
    private long leaderEpoch;
    private long timestamp;
}
