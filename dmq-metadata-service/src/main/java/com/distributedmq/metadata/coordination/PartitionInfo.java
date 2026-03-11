package com.distributedmq.metadata.coordination;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * Internal state representation of a partition in MetadataStateMachine
 * This is the in-memory state replicated across all metadata nodes via Raft
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PartitionInfo implements Serializable {
    private static final long serialVersionUID = 1L;

    private String topicName;
    private int partitionId;
    private int leaderId;
    private List<Integer> replicaIds;
    private List<Integer> isrIds; // In-Sync Replicas
    private long startOffset;
    private long endOffset;
    private long leaderEpoch;
}