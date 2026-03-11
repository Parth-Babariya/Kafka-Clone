package com.distributedmq.metadata.service;

import com.distributedmq.common.config.ServiceDiscovery;
import com.distributedmq.common.dto.MetadataUpdateRequest;
import com.distributedmq.common.dto.MetadataUpdateResponse;
import com.distributedmq.common.model.BrokerNode;
import com.distributedmq.common.model.PartitionMetadata;
import com.distributedmq.common.model.TopicMetadata;
import com.distributedmq.metadata.config.ServicePairingConfig;
import com.distributedmq.metadata.coordination.MetadataStateMachine;
import com.distributedmq.metadata.coordination.TopicInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Service for pushing metadata updates to storage nodes
 * Handles the push mechanism of metadata synchronization
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MetadataPushService {

    private final StorageNodeClient storageNodeClient;
    private final MetadataStateMachine metadataStateMachine;

    /**
     * Push topic metadata to all storage nodes
     * Called after topic creation or updates
     */
    public List<MetadataUpdateResponse> pushTopicMetadata(TopicMetadata topicMetadata, List<BrokerNode> activeBrokers) {
        log.info("Pushing topic metadata for topic: {} to all storage nodes",
                topicMetadata.getTopicName());

        // Convert TopicMetadata to MetadataUpdateRequest
        MetadataUpdateRequest request = createMetadataUpdateRequest(topicMetadata, activeBrokers);

        // Get all storage service URLs
        List<String> storageUrls = ServiceDiscovery.getAllStorageServices().stream()
                .map(ServiceDiscovery.StorageServiceInfo::getUrl)
                .collect(Collectors.toList());

        log.info("Pushing to {} storage nodes: {}", storageUrls.size(), storageUrls);

        // Push to all storage nodes
        List<MetadataUpdateResponse> responses = storageUrls.stream()
                .map(url -> pushToStorageNode(url, request))
                .collect(Collectors.toList());

        // Log results
        long successCount = responses.stream().filter(MetadataUpdateResponse::isSuccess).count();
        log.info("Topic metadata push completed: {}/{} storage nodes successful",
                successCount, responses.size());

        return responses;
    }

    /**
     * Push full cluster metadata to all storage nodes
     * Used for initial sync or major updates (e.g., topic deletion)
     * Includes ALL current brokers and ALL current partitions for full snapshot replacement
     */
    public List<MetadataUpdateResponse> pushFullClusterMetadata(List<BrokerNode> activeBrokers) {
        log.info("Pushing full cluster metadata to all storage nodes");

        // Get ALL current topics and their partitions from state machine
        List<MetadataUpdateRequest.PartitionMetadata> allPartitions = new ArrayList<>();
        Map<String, TopicInfo> allTopics = metadataStateMachine.getAllTopics();
        
        log.debug("Building full cluster snapshot from {} topics", allTopics.size());
        
        for (TopicInfo topicInfo : allTopics.values()) {
            try {
                TopicMetadata metadata = convertTopicInfoToMetadata(topicInfo);
                for (com.distributedmq.common.model.PartitionMetadata partition : metadata.getPartitions()) {
                    allPartitions.add(convertPartitionMetadata(partition));
                }
            } catch (Exception e) {
                log.error("Failed to convert topic {} metadata: {}", topicInfo.getTopicName(), e.getMessage());
            }
        }

        // Create complete cluster snapshot with ALL brokers and ALL partitions
        MetadataUpdateRequest request = MetadataUpdateRequest.builder()
                .updateType(MetadataUpdateRequest.UpdateType.FULL_SNAPSHOT)  // Explicit full snapshot type
                .version(metadataStateMachine.getMetadataVersion())
                .brokers(activeBrokers.stream()
                        .map(this::convertBrokerNodeToBrokerInfo)
                        .collect(Collectors.toList()))
                .partitions(allPartitions)  // Include ALL current partitions for snapshot replacement
                .timestamp(System.currentTimeMillis())
                .build();

        // Get all storage service URLs
        List<String> storageUrls = ServiceDiscovery.getAllStorageServices().stream()
                .map(ServiceDiscovery.StorageServiceInfo::getUrl)
                .collect(Collectors.toList());

        log.info("Pushing complete cluster snapshot to {} storage nodes: {} brokers, {} partitions", 
                storageUrls.size(), request.getBrokers().size(), allPartitions.size());

        // Push to all storage nodes
        List<MetadataUpdateResponse> responses = storageUrls.stream()
                .map(url -> pushToStorageNode(url, request))
                .collect(Collectors.toList());

        // Log results
        long successCount = responses.stream().filter(MetadataUpdateResponse::isSuccess).count();
        log.info("Cluster metadata push completed: {}/{} storage nodes successful",
                successCount, responses.size());

        return responses;
    }

    /**
     * Push ISR update for specific partitions (incremental)
     * Only sends changed ISR, not entire cluster state
     * Only pushes to affected brokers (replicas of the partition)
     */
    public List<MetadataUpdateResponse> pushISRUpdate(String topic, int partition, List<Integer> newISR, 
                                                     List<Integer> replicaIds) {
        log.info("Pushing incremental ISR update for {}-{}: newISR={}", topic, partition, newISR);

        // Create partition metadata with only ISR info
        MetadataUpdateRequest.PartitionMetadata partitionMetadata =
                MetadataUpdateRequest.PartitionMetadata.builder()
                        .topic(topic)
                        .partition(partition)
                        .isrIds(newISR)
                        .build();

        MetadataUpdateRequest request = MetadataUpdateRequest.builder()
                .updateType(MetadataUpdateRequest.UpdateType.ISR_UPDATE)  // Incremental ISR update
                .version(metadataStateMachine.getMetadataVersion())
                .partitions(List.of(partitionMetadata))
                .timestamp(System.currentTimeMillis())
                .build();

        // Get affected storage nodes (only replicas need this update)
        List<String> storageUrls = getStorageUrlsForBrokers(replicaIds);

        log.info("Pushing ISR update to {} replica brokers: {}", storageUrls.size(), replicaIds);

        // Push to affected storage nodes only
        List<MetadataUpdateResponse> responses = storageUrls.stream()
                .map(url -> pushToStorageNode(url, request))
                .collect(Collectors.toList());

        // Log results
        long successCount = responses.stream().filter(MetadataUpdateResponse::isSuccess).count();
        log.info("ISR update push completed: {}/{} replicas successful",
                successCount, responses.size());

        return responses;
    }

    /**
     * Push leader update for specific partition (incremental)
     * Only sends changed leader, not entire cluster state
     */
    public List<MetadataUpdateResponse> pushLeaderUpdate(String topic, int partition, int newLeader, 
                                                         long leaderEpoch, List<Integer> followers, 
                                                         List<Integer> isrIds, List<Integer> replicaIds) {
        log.info("Pushing incremental leader update for {}-{}: newLeader={}, epoch={}", 
                topic, partition, newLeader, leaderEpoch);

        // Create partition metadata with leader info
        MetadataUpdateRequest.PartitionMetadata partitionMetadata =
                MetadataUpdateRequest.PartitionMetadata.builder()
                        .topic(topic)
                        .partition(partition)
                        .leaderId(newLeader)
                        .leaderEpoch(leaderEpoch)
                        .followerIds(followers)
                        .isrIds(isrIds)
                        .build();

        MetadataUpdateRequest request = MetadataUpdateRequest.builder()
                .updateType(MetadataUpdateRequest.UpdateType.LEADER_UPDATE)  // Incremental leader update
                .version(metadataStateMachine.getMetadataVersion())
                .partitions(List.of(partitionMetadata))
                .timestamp(System.currentTimeMillis())
                .build();

        // Get affected storage nodes (only replicas need this update)
        List<String> storageUrls = getStorageUrlsForBrokers(replicaIds);

        log.info("Pushing leader update to {} replica brokers: {}", storageUrls.size(), replicaIds);

        // Push to affected storage nodes only
        List<MetadataUpdateResponse> responses = storageUrls.stream()
                .map(url -> pushToStorageNode(url, request))
                .collect(Collectors.toList());

        // Log results
        long successCount = responses.stream().filter(MetadataUpdateResponse::isSuccess).count();
        log.info("Leader update push completed: {}/{} replicas successful",
                successCount, responses.size());

        return responses;
    }

    /**
     * Push topic deletion notification (incremental)
     * Tells storage nodes to remove partitions for deleted topic
     */
    public List<MetadataUpdateResponse> pushTopicDeleted(String topicName) {
        log.info("Pushing topic deletion notification for topic: {}", topicName);

        MetadataUpdateRequest request = MetadataUpdateRequest.builder()
                .updateType(MetadataUpdateRequest.UpdateType.TOPIC_DELETED)  // Topic deletion
                .version(metadataStateMachine.getMetadataVersion())
                .deletedTopics(List.of(topicName))
                .timestamp(System.currentTimeMillis())
                .build();

        // Get all storage service URLs (all nodes need to know about topic deletion)
        List<String> storageUrls = ServiceDiscovery.getAllStorageServices().stream()
                .map(ServiceDiscovery.StorageServiceInfo::getUrl)
                .collect(Collectors.toList());

        log.info("Pushing topic deletion to {} storage nodes", storageUrls.size());

        // Push to all storage nodes
        List<MetadataUpdateResponse> responses = storageUrls.stream()
                .map(url -> pushToStorageNode(url, request))
                .collect(Collectors.toList());

        // Log results
        long successCount = responses.stream().filter(MetadataUpdateResponse::isSuccess).count();
        log.info("Topic deletion push completed: {}/{} storage nodes successful",
                successCount, responses.size());

        return responses;
    }

    /**
     * Get storage service URLs for specific broker IDs
     */
    private List<String> getStorageUrlsForBrokers(List<Integer> brokerIds) {
        return ServiceDiscovery.getAllStorageServices().stream()
                .filter(service -> brokerIds.contains(service.getId()))
                .map(ServiceDiscovery.StorageServiceInfo::getUrl)
                .collect(Collectors.toList());
    }

    /**
     * Extract broker ID from service ID (e.g., "storage-101" → 101)
     * Note: ServiceDiscovery.StorageServiceInfo.getId() returns broker ID directly
     */
    private Integer extractBrokerIdFromServiceId(String serviceId) {
        if (serviceId == null || !serviceId.startsWith("storage-")) {
            return -1;
        }
        try {
            return Integer.parseInt(serviceId.substring("storage-".length()));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Push metadata update to a specific storage node URL
     * Used by controller for cross-node coordination
     */
    public MetadataUpdateResponse pushToStorageNode(String storageNodeUrl, MetadataUpdateRequest request) {
        log.debug("Pushing metadata update to storage node: {}", storageNodeUrl);

        return storageNodeClient.pushMetadataToUrl(storageNodeUrl, request);
    }

    /**
     * Push partition leadership changes to all storage nodes
     * Called when partition leaders change
     */
    public List<MetadataUpdateResponse> pushPartitionLeadershipUpdate(String topicName, int partitionId,
                                            int newLeaderId, List<Integer> followers,
                                            List<Integer> isr) {
        log.info("Pushing partition leadership update for {}-{}: leader={} to all storage nodes",
                topicName, partitionId, newLeaderId);

        // Create partition metadata update
        MetadataUpdateRequest.PartitionMetadata partitionMetadata =
                MetadataUpdateRequest.PartitionMetadata.builder()
                        .topic(topicName)
                        .partition(partitionId)
                        .leaderId(newLeaderId)
                        .followerIds(followers)
                        .isrIds(isr)
                        .leaderEpoch(System.currentTimeMillis()) // Use timestamp as epoch
                        .build();

        MetadataUpdateRequest request = MetadataUpdateRequest.builder()
                .version(metadataStateMachine.getMetadataVersion())
                .partitions(List.of(partitionMetadata))
                .timestamp(System.currentTimeMillis())
                .build();

        // Get all storage service URLs
        List<String> storageUrls = ServiceDiscovery.getAllStorageServices().stream()
                .map(ServiceDiscovery.StorageServiceInfo::getUrl)
                .collect(Collectors.toList());

        // Push to all storage nodes
        List<MetadataUpdateResponse> responses = storageUrls.stream()
                .map(url -> pushToStorageNode(url, request))
                .collect(Collectors.toList());

        // Log results
        long successCount = responses.stream().filter(MetadataUpdateResponse::isSuccess).count();
        log.info("Partition leadership update push completed: {}/{} storage nodes successful",
                successCount, responses.size());

        return responses;
    }

    /**
     * Convert TopicMetadata to MetadataUpdateRequest
     */
    private MetadataUpdateRequest createMetadataUpdateRequest(TopicMetadata topicMetadata, List<BrokerNode> activeBrokers) {
        // Convert partitions
        List<MetadataUpdateRequest.PartitionMetadata> partitionMetadatas =
                topicMetadata.getPartitions().stream()
                        .map(this::convertPartitionMetadata)
                        .collect(Collectors.toList());

        // Use provided active brokers for broker info
        List<MetadataUpdateRequest.BrokerInfo> brokerInfos = activeBrokers.stream()
                .map(this::convertBrokerNodeToBrokerInfo)
                .collect(Collectors.toList());

        return MetadataUpdateRequest.builder()
                .updateType(MetadataUpdateRequest.UpdateType.TOPIC_CREATED)  // Topic creation
                .version(metadataStateMachine.getMetadataVersion())
                .brokers(brokerInfos)
                .partitions(partitionMetadatas)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    /**
     * Convert TopicInfo from state machine to TopicMetadata with full partition information
     */
    private TopicMetadata convertTopicInfoToMetadata(com.distributedmq.metadata.coordination.TopicInfo topicInfo) {
        TopicMetadata metadata = TopicMetadata.builder()
                .topicName(topicInfo.getTopicName())
                .partitionCount(topicInfo.getPartitionCount())
                .replicationFactor(topicInfo.getReplicationFactor())
                .config(topicInfo.getConfig())
                .createdAt(topicInfo.getCreatedAt())
                .build();

        // Get partition information from state machine
        Map<Integer, com.distributedmq.metadata.coordination.PartitionInfo> partitionMap = 
                metadataStateMachine.getPartitions(topicInfo.getTopicName());
        
        if (partitionMap != null && !partitionMap.isEmpty()) {
            List<com.distributedmq.common.model.PartitionMetadata> partitions = new ArrayList<>();
            
            for (com.distributedmq.metadata.coordination.PartitionInfo partInfo : partitionMap.values()) {
                // Convert replica IDs to BrokerNodes
                List<BrokerNode> replicas = new ArrayList<>();
                for (Integer brokerId : partInfo.getReplicaIds()) {
                    com.distributedmq.metadata.coordination.BrokerInfo brokerInfo = 
                            metadataStateMachine.getBroker(brokerId);
                    if (brokerInfo != null) {
                        replicas.add(BrokerNode.builder()
                                .brokerId(brokerInfo.getBrokerId())
                                .host(brokerInfo.getHost())
                                .port(brokerInfo.getPort())
                                .status(com.distributedmq.common.model.BrokerStatus.ONLINE)
                                .build());
                    }
                }
                
                // Leader is the first replica
                BrokerNode leader = !replicas.isEmpty() ? replicas.get(0) : null;
                
                // ISR - for now, all replicas are in ISR
                List<BrokerNode> isr = new ArrayList<>(replicas);
                
                if (leader != null) {
                    com.distributedmq.common.model.PartitionMetadata partition = 
                            com.distributedmq.common.model.PartitionMetadata.builder()
                            .topicName(topicInfo.getTopicName())
                            .partitionId(partInfo.getPartitionId())
                            .leader(leader)
                            .replicas(replicas)
                            .isr(isr)
                            .build();
                    
                    partitions.add(partition);
                }
            }
            
            metadata.setPartitions(partitions);
        }
        
        return metadata;
    }

    /**
     * Convert PartitionMetadata to MetadataUpdateRequest.PartitionMetadata
     */
    private MetadataUpdateRequest.PartitionMetadata convertPartitionMetadata(
            PartitionMetadata partitionMetadata) {

        return MetadataUpdateRequest.PartitionMetadata.builder()
                .topic(partitionMetadata.getTopicName())
                .partition(partitionMetadata.getPartitionId())
                .leaderId(partitionMetadata.getLeader().getBrokerId())
                .followerIds(partitionMetadata.getReplicas().stream()
                        .map(broker -> broker.getBrokerId())
                        .filter(id -> !id.equals(partitionMetadata.getLeader().getBrokerId()))
                        .collect(Collectors.toList()))
                .isrIds(partitionMetadata.getIsr().stream()
                        .map(broker -> broker.getBrokerId())
                        .collect(Collectors.toList()))
                .leaderEpoch(System.currentTimeMillis())
                .build();
    }

    /**
     * Convert BrokerNode to MetadataUpdateRequest.BrokerInfo
     */
    private MetadataUpdateRequest.BrokerInfo convertBrokerNodeToBrokerInfo(BrokerNode brokerNode) {
        return MetadataUpdateRequest.BrokerInfo.builder()
                .id(brokerNode.getBrokerId())
                .host(brokerNode.getHost())
                .port(brokerNode.getPort())
                .isAlive(brokerNode.getStatus() == com.distributedmq.common.model.BrokerStatus.ONLINE)
                .build();
    }

    /**
     * Push CONTROLLER_CHANGED notification to all storage nodes
     * Called when Raft leader election completes
     * Retries up to 2 times on failure (with 500ms, 1s delays)
     */
    public void pushControllerChanged(Integer controllerId, String controllerUrl, Long controllerTerm) {
        log.info("🔄 Pushing CONTROLLER_CHANGED notification: ID={}, URL={}, term={}", 
            controllerId, controllerUrl, controllerTerm);

        // Create CONTROLLER_CHANGED request
        MetadataUpdateRequest request = MetadataUpdateRequest.builder()
                .updateType(MetadataUpdateRequest.UpdateType.CONTROLLER_CHANGED)
                .version(metadataStateMachine.getMetadataVersion())
                .controllerId(controllerId)
                .controllerUrl(controllerUrl)
                .controllerTerm(controllerTerm)
                .timestamp(System.currentTimeMillis())
                .build();

        // Get all storage service URLs
        List<String> storageUrls = ServiceDiscovery.getAllStorageServices().stream()
                .map(ServiceDiscovery.StorageServiceInfo::getUrl)
                .collect(Collectors.toList());

        log.info("Pushing CONTROLLER_CHANGED to {} storage nodes", storageUrls.size());

        // Push to all storage nodes with retry
        int successCount = 0;
        for (String url : storageUrls) {
            boolean success = pushControllerChangedWithRetry(url, request);
            if (success) {
                successCount++;
            }
        }

        log.info("✅ CONTROLLER_CHANGED push completed: {}/{} storage nodes successful",
                successCount, storageUrls.size());
    }

    /**
     * Push CONTROLLER_CHANGED to single storage node with retry
     * Retry pattern: Initial attempt + 2 retries (max 3 total)
     * Delays: 500ms, 1s
     */
    private boolean pushControllerChangedWithRetry(String storageUrl, MetadataUpdateRequest request) {
        int maxAttempts = 3;  // 1 initial + 2 retries
        long[] retryDelays = {0, 500, 1000};  // Initial: 0ms, Retry1: 500ms, Retry2: 1s

        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            try {
                // Wait before retry (not on first attempt)
                if (attempt > 0) {
                    Thread.sleep(retryDelays[attempt]);
                    log.debug("Retrying CONTROLLER_CHANGED push to {} (attempt {}/{})", 
                        storageUrl, attempt + 1, maxAttempts);
                }

                MetadataUpdateResponse response = pushToStorageNode(storageUrl, request);
                
                if (response != null && response.isSuccess()) {
                    if (attempt > 0) {
                        log.info("✅ CONTROLLER_CHANGED push succeeded to {} on attempt {}", 
                            storageUrl, attempt + 1);
                    }
                    return true;
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("CONTROLLER_CHANGED push to {} interrupted", storageUrl);
                return false;
            } catch (Exception e) {
                log.warn("CONTROLLER_CHANGED push to {} failed (attempt {}/{}): {}", 
                    storageUrl, attempt + 1, maxAttempts, e.getMessage());
            }
        }

        log.error("❌ CONTROLLER_CHANGED push to {} failed after {} attempts", 
            storageUrl, maxAttempts);
        return false;
    }
}