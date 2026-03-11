package com.distributedmq.metadata.repository;

import com.distributedmq.metadata.entity.PartitionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for Partition entities
 * Used for async backup only - Raft log is source of truth
 */
@Repository
public interface PartitionRepository extends JpaRepository<PartitionEntity, Long> {

    /**
     * Find all partitions for a topic
     */
    List<PartitionEntity> findByTopicName(String topicName);

    /**
     * Find a specific partition
     */
    Optional<PartitionEntity> findByTopicNameAndPartitionId(String topicName, Integer partitionId);

    /**
     * Check if partition exists
     */
    boolean existsByTopicNameAndPartitionId(String topicName, Integer partitionId);

    /**
     * Delete all partitions for a topic
     */
    void deleteByTopicName(String topicName);

    /**
     * Count partitions for a topic
     */
    long countByTopicName(String topicName);
}
