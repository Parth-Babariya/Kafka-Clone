package com.distributedmq.metadata.service;

import com.distributedmq.common.dto.MetadataUpdateRequest;
import com.distributedmq.common.dto.MetadataUpdateResponse;
import com.distributedmq.common.model.TopicMetadata;
import com.distributedmq.common.dto.CreateTopicRequest;
import com.distributedmq.metadata.dto.RegisterBrokerRequest;
import com.distributedmq.metadata.dto.BrokerResponse;

import java.util.List;

/**
 * Service interface for Metadata operations
 */
public interface MetadataService {

    /**
     * Create a new topic
     */
    TopicMetadata createTopic(CreateTopicRequest request);

    /**
     * Get topic metadata
     */
    TopicMetadata getTopicMetadata(String topicName);

    /**
     * List all topics
     */
    List<String> listTopics();

    /**
     * Delete a topic
     */
    void deleteTopic(String topicName);

    /**
     * Register a broker
     */
    BrokerResponse registerBroker(RegisterBrokerRequest request);

    /**
     * Get broker information
     */
    BrokerResponse getBroker(Integer brokerId);

    /**
     * List all brokers
     */
    List<BrokerResponse> listBrokers();

    /**
     * Update broker status
     */
    void updateBrokerStatus(Integer brokerId, String status);

    /**
     * Push all metadata to a requesting metadata service
     * Used for synchronization when a metadata service is out of sync
     */
    void pushAllMetadataToService(String serviceUrl);

    /**
     * Receive metadata pushed from active controller
     * Used by non-active metadata services to receive synced data
     */
    void receiveMetadataFromController(com.distributedmq.common.dto.MetadataUpdateRequest metadataUpdate);

    /**
     * Get the timestamp of the last metadata update received by this service
     */
    Long getLastMetadataUpdateTimestamp();

    /**
     * Process metadata updates received from storage services
     * Storage services notify metadata services about local changes
     */
    MetadataUpdateResponse processStorageUpdate(MetadataUpdateRequest storageUpdate);

    /**
     * Remove a broker from ISR for a specific partition
     * Called when a follower exceeds lag threshold
     */
    void removeFromISR(String topic, Integer partition, Integer brokerId);

    /**
     * Add a broker to ISR for a specific partition
     * Called when a follower recovers from lag
     */
    void addToISR(String topic, Integer partition, Integer brokerId);

    /**
     * Get current ISR for a partition
     */
    List<Integer> getISR(String topic, Integer partition);

    /**
     * Check if a broker is in ISR for a partition
     */
    boolean isInISR(String topic, Integer partition, Integer brokerId);

    /**
     * Get current metadata version
     * Used by storage services to check if their metadata is up-to-date
     */
    long getMetadataVersion();

    /**
     * Get full cluster metadata
     * Returns all brokers and topics with complete partition information
     * Used by storage services on startup and for periodic refresh
     */
    com.distributedmq.metadata.dto.ClusterMetadataResponse getClusterMetadata();

    // TODO: Add partition management methods
    // TODO: Add consumer group methods
    // TODO: Add offset management methods
}
