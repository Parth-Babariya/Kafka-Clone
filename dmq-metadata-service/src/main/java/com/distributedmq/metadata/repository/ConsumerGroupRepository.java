package com.distributedmq.metadata.repository;

import com.distributedmq.metadata.entity.ConsumerGroupEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for Consumer Group Registry
 * Manages persistence of minimal consumer group routing information
 */
@Repository
public interface ConsumerGroupRepository extends JpaRepository<ConsumerGroupEntity, Long> {
    
    /**
     * Find consumer group by topic and application ID
     * This is the primary lookup method for finding/creating groups
     * 
     * @param topic The topic name
     * @param appId The application ID
     * @return Optional containing the consumer group if found
     */
    Optional<ConsumerGroupEntity> findByTopicAndAppId(String topic, String appId);
    
    /**
     * Find consumer group by group ID
     * 
     * @param groupId The group ID (format: G_<topic>_<appId>)
     * @return Optional containing the consumer group if found
     */
    Optional<ConsumerGroupEntity> findByGroupId(String groupId);
    
    /**
     * Find all consumer groups coordinated by a specific broker
     * Used when a broker fails to reassign groups to other brokers
     * 
     * @param groupLeaderBrokerId The broker ID
     * @return List of consumer groups coordinated by this broker
     */
    List<ConsumerGroupEntity> findByGroupLeaderBrokerId(Integer groupLeaderBrokerId);
    
    /**
     * Delete consumer group by group ID
     * Called when the last member leaves the group
     * 
     * @param groupId The group ID to delete
     */
    void deleteByGroupId(String groupId);
    
    /**
     * Check if a consumer group exists for a topic and app ID
     * 
     * @param topic The topic name
     * @param appId The application ID
     * @return true if group exists, false otherwise
     */
    boolean existsByTopicAndAppId(String topic, String appId);
    
    /**
     * Count number of groups coordinated by a broker
     * Useful for load balancing when selecting group leaders
     * 
     * @param groupLeaderBrokerId The broker ID
     * @return Number of groups coordinated by this broker
     */
    long countByGroupLeaderBrokerId(Integer groupLeaderBrokerId);
}
