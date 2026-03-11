package com.distributedmq.common.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * Metadata about a topic
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TopicMetadata implements Serializable {
    private static final long serialVersionUID = 1L;

    private String topicName;
    private Integer partitionCount;
    private Integer replicationFactor;
    private List<PartitionMetadata> partitions;
    private Long createdAt;
    private TopicConfig config;

    // TODO: Add topic configuration options
    // TODO: Add retention policy information
}
