package com.distributedmq.metadata.dto;

import com.distributedmq.common.model.PartitionMetadata;
import com.distributedmq.common.model.TopicConfig;
import com.distributedmq.common.model.TopicMetadata;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Response DTO for topic metadata
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TopicMetadataResponse {
    
    private String topicName;
    private Integer partitionCount;
    private Integer replicationFactor;
    private List<PartitionMetadata> partitions;
    private Long createdAt;
    private TopicConfig config;

    public static TopicMetadataResponse from(TopicMetadata metadata) {
        return TopicMetadataResponse.builder()
                .topicName(metadata.getTopicName())
                .partitionCount(metadata.getPartitionCount())
                .replicationFactor(metadata.getReplicationFactor())
                .partitions(metadata.getPartitions())
                .createdAt(metadata.getCreatedAt())
                .config(metadata.getConfig())
                .build();
    }
}
