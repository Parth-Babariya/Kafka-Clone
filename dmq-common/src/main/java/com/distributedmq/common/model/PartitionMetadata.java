package com.distributedmq.common.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * Metadata about a partition
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PartitionMetadata implements Serializable {
    private static final long serialVersionUID = 1L;

    private String topicName;
    private Integer partitionId;
    private BrokerNode leader;
    private List<BrokerNode> replicas;
    private List<BrokerNode> isr; // In-Sync Replicas
    private Long startOffset;
    private Long endOffset;

    // TODO: Add partition state information
    // TODO: Add leader epoch tracking
}
