package com.distributedmq.metadata.coordination;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * Represents a single partition's assignment to brokers
 * Used in AssignPartitionsCommand
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PartitionAssignment implements Serializable {
    private static final long serialVersionUID = 1L;

    private int partitionId;
    private int leaderId;
    private List<Integer> replicaIds;
    private List<Integer> isrIds; // In-Sync Replicas
}
