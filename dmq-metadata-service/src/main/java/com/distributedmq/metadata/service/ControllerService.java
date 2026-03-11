package com.distributedmq.metadata.service;

import com.distributedmq.common.model.BrokerNode;
import com.distributedmq.common.model.PartitionMetadata;

import java.util.List;

/**
 * Controller Service for cluster coordination
 * Handles failure detection, leader election, and replication
 */
public interface ControllerService {

    /**
     * Assign partitions to brokers
     */
    List<PartitionMetadata> assignPartitions(String topicName, int partitionCount, int replicationFactor);

    /**
     * Cleanup topic partitions on deletion
     */
    void cleanupTopicPartitions(String topicName);

    /**
     * Handle broker failure
     */
    void handleBrokerFailure(Integer brokerId);

    /**
     * Elect new leader for partition
     */
    BrokerNode electPartitionLeader(String topicName, int partition);

    /**
     * Get all active brokers
     */
    List<BrokerNode> getActiveBrokers();

    /**
     * Register a new broker
     */
    void registerBroker(BrokerNode broker);

    /**
     * Unregister a broker
     */
    void unregisterBroker(Integer brokerId);

    /**
     * Update partition leadership information
     */
    void updatePartitionLeadership(String topicName, int partitionId, Integer leaderId, List<Integer> followers, List<Integer> isr);

    /**
     * Remove broker from ISR for a partition
     */
    void removeFromISR(String topicName, int partitionId, Integer brokerId);

    /**
     * Add broker to ISR for a partition
     */
    void addToISR(String topicName, int partitionId, Integer brokerId);

    /**
     * Get current ISR for a partition
     */
    List<Integer> getISR(String topicName, int partitionId);

    /**
     * Get partition leader
     */
    Integer getPartitionLeader(String topicName, int partitionId);

    /**
     * Get partition followers
     */
    List<Integer> getPartitionFollowers(String topicName, int partitionId);

    // TODO: Add rebalancing logic
}
