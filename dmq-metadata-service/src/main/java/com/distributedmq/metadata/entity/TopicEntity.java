package com.distributedmq.metadata.entity;

import com.distributedmq.common.model.TopicConfig;
import com.distributedmq.common.model.TopicMetadata;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import javax.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * JPA Entity for Topic
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "topics")
public class TopicEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String topicName;

    @Column(nullable = false)
    private Integer partitionCount;

    @Column(nullable = false)
    private Integer replicationFactor;

    @Column(nullable = false)
    private Long createdAt;

    // Configuration stored as JSON
    @Column(columnDefinition = "TEXT")
    private String configJson;

    // Timestamp for metadata synchronization
    @Column(nullable = false)
    private Long lastUpdatedAt;

    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Convert TopicMetadata to TopicEntity
     */
    public static TopicEntity fromMetadata(TopicMetadata metadata) {
        try {
            String configJson = objectMapper.writeValueAsString(metadata.getConfig());
            long now = System.currentTimeMillis();

            return TopicEntity.builder()
                    .topicName(metadata.getTopicName())
                    .partitionCount(metadata.getPartitionCount())
                    .replicationFactor(metadata.getReplicationFactor())
                    .createdAt(metadata.getCreatedAt() != null ? metadata.getCreatedAt() : now)
                    .configJson(configJson)
                    .lastUpdatedAt(now)
                    .build();
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize topic config", e);
        }
    }

    /**
     * Convert TopicEntity to TopicMetadata
     */
    public TopicMetadata toMetadata() {
        try {
            TopicMetadata.TopicMetadataBuilder builder = TopicMetadata.builder()
                    .topicName(topicName)
                    .partitionCount(partitionCount)
                    .replicationFactor(replicationFactor)
                    .createdAt(createdAt);

            if (configJson != null && !configJson.isEmpty()) {
                builder.config(objectMapper.readValue(configJson, TopicConfig.class));
            }

            return builder.build();
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to deserialize topic config", e);
        }
    }

    /**
     * Update entity from metadata
     */
    public void updateFromMetadata(TopicMetadata metadata) {
        try {
            this.partitionCount = metadata.getPartitionCount();
            this.replicationFactor = metadata.getReplicationFactor();
            this.configJson = objectMapper.writeValueAsString(metadata.getConfig());
            this.lastUpdatedAt = System.currentTimeMillis();
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize topic config", e);
        }
    }
}
