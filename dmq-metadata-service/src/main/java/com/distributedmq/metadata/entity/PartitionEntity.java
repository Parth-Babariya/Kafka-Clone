package com.distributedmq.metadata.entity;

import com.distributedmq.metadata.coordination.PartitionInfo;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import javax.persistence.*;
import java.util.ArrayList;
import java.util.List;

/**
 * JPA Entity for Partition metadata
 * Used for async backup only - Raft log is source of truth
 */
@Slf4j
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "partitions", indexes = {
    @Index(name = "idx_topic_partition", columnList = "topicName,partitionId", unique = true),
    @Index(name = "idx_topic", columnList = "topicName")
})
public class PartitionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String topicName;

    @Column(nullable = false)
    private Integer partitionId;

    @Column(nullable = false)
    private Integer leaderId;

    /**
     * Replica broker IDs stored as JSON array
     * Example: "[1,2,3]"
     */
    @Column(columnDefinition = "TEXT", nullable = false)
    private String replicaIds;

    /**
     * In-Sync Replica broker IDs stored as JSON array
     * Example: "[1,2]"
     */
    @Column(columnDefinition = "TEXT", nullable = false)
    private String isrIds;

    @Column(nullable = false)
    @Builder.Default
    private Long startOffset = 0L;

    @Column(nullable = false)
    @Builder.Default
    private Long endOffset = 0L;

    @Column(nullable = false)
    @Builder.Default
    private Long leaderEpoch = 0L;

    @Column(nullable = false)
    private Long lastUpdatedAt;

    private static final ObjectMapper objectMapper = new ObjectMapper();

    @PrePersist
    @PreUpdate
    protected void onUpdate() {
        lastUpdatedAt = System.currentTimeMillis();
    }

    /**
     * Convert PartitionInfo (from state machine) to PartitionEntity (for database)
     */
    public static PartitionEntity fromPartitionInfo(PartitionInfo partitionInfo) {
        if (partitionInfo == null) {
            throw new IllegalArgumentException("PartitionInfo cannot be null");
        }

        try {
            String replicaIdsJson = objectMapper.writeValueAsString(partitionInfo.getReplicaIds());
            String isrIdsJson = objectMapper.writeValueAsString(partitionInfo.getIsrIds());

            return PartitionEntity.builder()
                    .topicName(partitionInfo.getTopicName())
                    .partitionId(partitionInfo.getPartitionId())
                    .leaderId(partitionInfo.getLeaderId())
                    .replicaIds(replicaIdsJson)
                    .isrIds(isrIdsJson)
                    .startOffset(partitionInfo.getStartOffset())
                    .endOffset(partitionInfo.getEndOffset())
                    .leaderEpoch(partitionInfo.getLeaderEpoch())
                    .lastUpdatedAt(System.currentTimeMillis())
                    .build();
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize partition info to entity", e);
            throw new RuntimeException("Failed to convert PartitionInfo to PartitionEntity", e);
        }
    }

    /**
     * Convert PartitionEntity (from database) to PartitionInfo (for state machine)
     */
    public PartitionInfo toPartitionInfo() {
        try {
            List<Integer> replicaIdsList = objectMapper.readValue(
                    replicaIds, 
                    new TypeReference<List<Integer>>() {}
            );
            List<Integer> isrIdsList = objectMapper.readValue(
                    isrIds, 
                    new TypeReference<List<Integer>>() {}
            );

            return new PartitionInfo(
                    topicName,
                    partitionId,
                    leaderId,
                    replicaIdsList != null ? replicaIdsList : new ArrayList<>(),
                    isrIdsList != null ? isrIdsList : new ArrayList<>(),
                    startOffset,
                    endOffset,
                    leaderEpoch
            );
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize partition entity to info", e);
            throw new RuntimeException("Failed to convert PartitionEntity to PartitionInfo", e);
        }
    }

    /**
     * Update entity from PartitionInfo
     */
    public void updateFromPartitionInfo(PartitionInfo partitionInfo) {
        try {
            this.leaderId = partitionInfo.getLeaderId();
            this.replicaIds = objectMapper.writeValueAsString(partitionInfo.getReplicaIds());
            this.isrIds = objectMapper.writeValueAsString(partitionInfo.getIsrIds());
            this.startOffset = partitionInfo.getStartOffset();
            this.endOffset = partitionInfo.getEndOffset();
            this.leaderEpoch = partitionInfo.getLeaderEpoch();
            this.lastUpdatedAt = System.currentTimeMillis();
        } catch (JsonProcessingException e) {
            log.error("Failed to update partition entity from info", e);
            throw new RuntimeException("Failed to update PartitionEntity", e);
        }
    }
}
