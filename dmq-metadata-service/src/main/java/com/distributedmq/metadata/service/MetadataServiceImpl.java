package com.distributedmq.metadata.service;

import com.distributedmq.common.dto.MetadataUpdateRequest;
import com.distributedmq.common.dto.MetadataUpdateResponse;
import com.distributedmq.common.model.TopicConfig;
import com.distributedmq.common.model.TopicMetadata;
import com.distributedmq.common.model.BrokerNode;
import com.distributedmq.common.model.BrokerStatus;
import com.distributedmq.common.dto.CreateTopicRequest;
import com.distributedmq.metadata.dto.RegisterBrokerRequest;
import com.distributedmq.metadata.dto.BrokerResponse;
import com.distributedmq.metadata.entity.TopicEntity;
import com.distributedmq.metadata.entity.BrokerEntity;
import com.distributedmq.metadata.entity.PartitionEntity;
import com.distributedmq.metadata.repository.TopicRepository;
import com.distributedmq.metadata.repository.BrokerRepository;
import com.distributedmq.metadata.repository.PartitionRepository;
import com.distributedmq.metadata.coordination.MetadataStateMachine;
import com.distributedmq.metadata.coordination.BrokerInfo;
import com.distributedmq.metadata.coordination.RegisterTopicCommand;
import com.distributedmq.metadata.coordination.DeleteTopicCommand;
import com.distributedmq.metadata.coordination.TopicInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.scheduling.annotation.Async;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import javax.annotation.PostConstruct;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import java.util.ArrayList;

/**
 * Implementation of MetadataService
 * Business logic layer
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MetadataServiceImpl implements MetadataService {

    private final TopicRepository topicRepository;
    private final BrokerRepository brokerRepository;
    private final PartitionRepository partitionRepository;
    private final ControllerService controllerService;
    private final MetadataPushService metadataPushService;
    private final com.distributedmq.metadata.coordination.RaftController raftController;
    private final MetadataStateMachine metadataStateMachine;
    private final RestTemplate restTemplate;

    // In-memory storage for non-active metadata services
    private final Map<String, TopicMetadata> metadataCache = new ConcurrentHashMap<>();
    private volatile boolean hasSyncedData = false;

    @PostConstruct
    public void init() {
        log.info("Initializing MetadataServiceImpl");
        // Register this service with itself if it's the controller
        if (raftController.isControllerLeader()) {
            // Controller initialization - no heartbeat registration needed
        }
    }

    @Override
    @Transactional
    public TopicMetadata createTopic(CreateTopicRequest request) {
        log.info("Creating topic: {} with {} partitions, replication factor: {}",
                request.getTopicName(), request.getPartitionCount(), request.getReplicationFactor());

        // Only active controller can create topics
        if (!raftController.isControllerLeader()) {
            throw new IllegalStateException("Only active controller can create topics. Current leader: " +
                    raftController.getControllerLeaderId());
        }

        // Check if topic already exists in state machine
        if (metadataStateMachine.topicExists(request.getTopicName())) {
            log.info("Topic {} already exists, returning existing topic", request.getTopicName());
            TopicInfo topicInfo = metadataStateMachine.getTopic(request.getTopicName());
            return convertTopicInfoToMetadata(topicInfo);
        }

        // VALIDATE: Partition count must be positive
        if (request.getPartitionCount() <= 0) {
            String errorMsg = String.format(
                "Cannot create topic '%s': Partition count must be greater than 0. Provided: %d",
                request.getTopicName(), request.getPartitionCount());
            log.error(errorMsg);
            throw new IllegalArgumentException(errorMsg);
        }
        
        // VALIDATE: Replication factor must be positive
        if (request.getReplicationFactor() <= 0) {
            String errorMsg = String.format(
                "Cannot create topic '%s': Replication factor must be greater than 0. Provided: %d",
                request.getTopicName(), request.getReplicationFactor());
            log.error(errorMsg);
            throw new IllegalArgumentException(errorMsg);
        }

        // VALIDATE: Check if we have enough brokers for replication factor BEFORE creating topic
        List<BrokerInfo> availableBrokers = new ArrayList<>(metadataStateMachine.getAllBrokers().values());
        
        // Filter to only ONLINE brokers
        long onlineBrokerCount = availableBrokers.stream()
                .filter(broker -> broker.getStatus() == com.distributedmq.common.model.BrokerStatus.ONLINE)
                .count();
        
        if (onlineBrokerCount == 0) {
            String errorMsg = "Cannot create topic: No brokers are currently online";
            log.error(errorMsg);
            throw new IllegalArgumentException(errorMsg);
        }
        
        if (onlineBrokerCount < request.getReplicationFactor()) {
            String errorMsg = String.format(
                "Cannot create topic '%s': Insufficient brokers for replication factor %d. " +
                "Available online brokers: %d. Please reduce replication factor to %d or less.",
                request.getTopicName(), request.getReplicationFactor(), onlineBrokerCount, onlineBrokerCount);
            log.error(errorMsg);
            throw new IllegalArgumentException(errorMsg);
        }
        
        log.info("Validation passed: {} online brokers available for replication factor {}", 
                onlineBrokerCount, request.getReplicationFactor());

        // Create topic configuration with defaults
        TopicConfig config = TopicConfig.builder()
                .retentionMs(request.getRetentionMs() != null ? request.getRetentionMs() : 604800000L) // 7 days
                .retentionBytes(request.getRetentionBytes() != null ? request.getRetentionBytes() : -1L) // unlimited
                .segmentBytes(request.getSegmentBytes() != null ? request.getSegmentBytes() : 1073741824) // 1GB
                .compressionType(request.getCompressionType() != null ? request.getCompressionType() : "none")
                .minInsyncReplicas(request.getMinInsyncReplicas() != null ? request.getMinInsyncReplicas() : 1)
                .build();

        // Step 1: Create RegisterTopicCommand and submit to Raft
        RegisterTopicCommand registerCommand = new RegisterTopicCommand(
                request.getTopicName(),
                request.getPartitionCount(),
                request.getReplicationFactor(),
                config,
                System.currentTimeMillis()
        );

        try {
            // Step 2: Submit to Raft and wait for commit
            CompletableFuture<Void> registerFuture = raftController.appendCommand(registerCommand);
            registerFuture.get(10, TimeUnit.SECONDS);
            log.info("Topic {} registered via Raft consensus", request.getTopicName());
        } catch (Exception e) {
            log.error("Failed to register topic {} via Raft", request.getTopicName(), e);
            throw new IllegalStateException("Failed to register topic via Raft consensus", e);
        }

        // Step 3: Assign partitions to brokers (this now uses Raft internally via AssignPartitionsCommand)
        List<com.distributedmq.common.model.PartitionMetadata> partitions;
        try {
            partitions = controllerService.assignPartitions(
                    request.getTopicName(),
                    request.getPartitionCount(),
                    request.getReplicationFactor()
            );
        } catch (Exception e) {
            log.error("Failed to assign partitions for topic {}, rolling back topic registration", request.getTopicName(), e);
            
            // Rollback: Delete the orphaned topic from Raft
            try {
                DeleteTopicCommand deleteCommand = DeleteTopicCommand.builder()
                        .topicName(request.getTopicName())
                        .timestamp(System.currentTimeMillis())
                        .build();
                raftController.appendCommand(deleteCommand).get(5, TimeUnit.SECONDS);
                log.info("Successfully rolled back orphaned topic: {}", request.getTopicName());
            } catch (Exception rollbackEx) {
                log.error("Failed to rollback topic {} after partition assignment failure", 
                         request.getTopicName(), rollbackEx);
            }
            
            // Re-throw the original exception with context
            throw new IllegalStateException(
                String.format("Failed to assign partitions for topic '%s': %s", 
                             request.getTopicName(), e.getMessage()), e);
        }

        // Step 4: Async persist to database (leader only, non-blocking)
        asyncPersistTopic(request.getTopicName());
        asyncPersistPartitions(request.getTopicName());

        // Step 5: Build metadata from state machine
        TopicInfo topicInfo = metadataStateMachine.getTopic(request.getTopicName());
        TopicMetadata metadata = convertTopicInfoToMetadata(topicInfo);
        metadata.setPartitions(partitions);

        // Step 6: Push metadata to storage services
        List<MetadataUpdateResponse> pushResponses = metadataPushService.pushTopicMetadata(metadata, controllerService.getActiveBrokers());
        long successCount = pushResponses.stream().filter(MetadataUpdateResponse::isSuccess).count();
        log.info("Topic metadata push completed: {}/{} storage nodes successful", successCount, pushResponses.size());

        log.info("Successfully created topic: {} with {} partitions via Raft", request.getTopicName(), request.getPartitionCount());
        return metadata;
    }

    /**
     * Helper method to convert TopicInfo from state machine to TopicMetadata
     */
    private TopicMetadata convertTopicInfoToMetadata(TopicInfo topicInfo) {
        return TopicMetadata.builder()
                .topicName(topicInfo.getTopicName())
                .partitionCount(topicInfo.getPartitionCount())
                .replicationFactor(topicInfo.getReplicationFactor())
                .config(topicInfo.getConfig())
                .createdAt(topicInfo.getCreatedAt())
                .build();
    }

    /**
     * Async persist topic to database (leader only, non-blocking)
     */
    @Async("dbPersistenceExecutor")
    private void asyncPersistTopic(String topicName) {
        try {
            // Only leader persists to database
            if (!raftController.isControllerLeader()) {
                log.debug("Skipping async persist on non-leader node for topic: {}", topicName);
                return;
            }

            TopicInfo topicInfo = metadataStateMachine.getTopic(topicName);
            if (topicInfo == null) {
                log.warn("Cannot persist topic {} - not found in state machine", topicName);
                return;
            }

            TopicMetadata metadata = convertTopicInfoToMetadata(topicInfo);
            TopicEntity entity = TopicEntity.fromMetadata(metadata);
            topicRepository.save(entity);
            
            log.info("Async persisted topic {} to database", topicName);
        } catch (Exception e) {
            log.error("Failed to async persist topic {} to database", topicName, e);
            // Don't throw - this is async backup only, Raft log is source of truth
        }
    }

    /**
     * Async persist partitions to database (leader only, non-blocking)
     */
    @Async("dbPersistenceExecutor")
    private void asyncPersistPartitions(String topicName) {
        try {
            // Only leader persists to database
            if (!raftController.isControllerLeader()) {
                log.debug("Skipping async partition persist on non-leader node for topic: {}", topicName);
                return;
            }

            Map<Integer, com.distributedmq.metadata.coordination.PartitionInfo> partitionMap = 
                    metadataStateMachine.getPartitions(topicName);
            
            if (partitionMap == null || partitionMap.isEmpty()) {
                log.warn("Cannot persist partitions for topic {} - not found in state machine", topicName);
                return;
            }

            int persistedCount = 0;
            for (com.distributedmq.metadata.coordination.PartitionInfo partInfo : partitionMap.values()) {
                // Check if partition already exists
                Optional<PartitionEntity> existing = partitionRepository.findByTopicNameAndPartitionId(
                        topicName, partInfo.getPartitionId());
                
                if (existing.isPresent()) {
                    // Update existing partition
                    PartitionEntity entity = existing.get();
                    entity.updateFromPartitionInfo(partInfo);
                    partitionRepository.save(entity);
                } else {
                    // Create new partition
                    PartitionEntity entity = PartitionEntity.fromPartitionInfo(partInfo);
                    partitionRepository.save(entity);
                }
                persistedCount++;
            }
            
            log.info("Async persisted {} partitions for topic {} to database", persistedCount, topicName);
        } catch (Exception e) {
            log.error("Failed to async persist partitions for topic {} to database", topicName, e);
            // Don't throw - this is async backup only, Raft log is source of truth
        }
    }

    /**
     * Async delete partitions from database (leader only, non-blocking)
     */
    @Async("dbPersistenceExecutor")
    private void asyncDeletePartitions(String topicName) {
        try {
            // Only leader deletes from database
            if (!raftController.isControllerLeader()) {
                log.debug("Skipping async partition delete on non-leader node for topic: {}", topicName);
                return;
            }

            long count = partitionRepository.countByTopicName(topicName);
            if (count > 0) {
                partitionRepository.deleteByTopicName(topicName);
                log.info("Async deleted {} partitions for topic {} from database", count, topicName);
            }
        } catch (Exception e) {
            log.error("Failed to async delete partitions for topic {} from database", topicName, e);
            // Don't throw - this is async backup only, Raft log is source of truth
        }
    }

    /**
     * Async persist broker to database (leader only, non-blocking)
     */
    @Async("dbPersistenceExecutor")
    private void asyncPersistBroker(int brokerId) {
        try {
            // Only leader persists to database
            if (!raftController.isControllerLeader()) {
                log.debug("Skipping async broker persist on non-leader node for broker: {}", brokerId);
                return;
            }

            BrokerInfo brokerInfo = metadataStateMachine.getBroker(brokerId);
            if (brokerInfo == null) {
                log.warn("Cannot persist broker {} - not found in state machine", brokerId);
                return;
            }

            // Check if broker already exists
            Optional<BrokerEntity> existing = brokerRepository.findById(brokerId);
            
            if (existing.isPresent()) {
                // Update existing broker
                BrokerEntity entity = existing.get();
                entity.setHost(brokerInfo.getHost());
                entity.setPort(brokerInfo.getPort());
                entity.setStatus("ONLINE");
                brokerRepository.save(entity);
                log.info("Async updated broker {} in database", brokerId);
            } else {
                // Create new broker
                BrokerEntity entity = BrokerEntity.builder()
                        .id(brokerId)
                        .host(brokerInfo.getHost())
                        .port(brokerInfo.getPort())
                        .status("ONLINE")
                        .build();
                brokerRepository.save(entity);
                log.info("Async persisted broker {} to database", brokerId);
            }
        } catch (Exception e) {
            log.error("Failed to async persist broker {} to database", brokerId, e);
            // Don't throw - this is async backup only, Raft log is source of truth
        }
    }

    @Override
    public TopicMetadata getTopicMetadata(String topicName) {
        log.debug("Getting metadata for topic: {}", topicName);

        // Read from state machine (works on all nodes - leader and followers)
        TopicInfo topicInfo = metadataStateMachine.getTopic(topicName);
        if (topicInfo == null) {
            throw new IllegalArgumentException("Topic not found: " + topicName);
        }

        // Convert TopicInfo to TopicMetadata
        TopicMetadata metadata = convertTopicInfoToMetadata(topicInfo);

        // Get partition information from state machine
        Map<Integer, com.distributedmq.metadata.coordination.PartitionInfo> partitionMap = 
                metadataStateMachine.getPartitions(topicName);
        
        if (partitionMap != null && !partitionMap.isEmpty()) {
            List<com.distributedmq.common.model.PartitionMetadata> partitions = new ArrayList<>();
            
            for (com.distributedmq.metadata.coordination.PartitionInfo partInfo : partitionMap.values()) {
                // Convert replica IDs to BrokerNodes
                List<BrokerNode> replicas = new ArrayList<>();
                for (Integer brokerId : partInfo.getReplicaIds()) {
                    BrokerInfo brokerInfo = metadataStateMachine.getBroker(brokerId);
                    if (brokerInfo != null) {
                        replicas.add(BrokerNode.builder()
                                .brokerId(brokerInfo.getBrokerId())
                                .host(brokerInfo.getHost())
                                .port(brokerInfo.getPort())
                                .status(BrokerStatus.ONLINE)
                                .build());
                    }
                }

                // Convert ISR IDs to BrokerNodes
                List<BrokerNode> isr = new ArrayList<>();
                for (Integer brokerId : partInfo.getIsrIds()) {
                    BrokerInfo brokerInfo = metadataStateMachine.getBroker(brokerId);
                    if (brokerInfo != null) {
                        isr.add(BrokerNode.builder()
                                .brokerId(brokerInfo.getBrokerId())
                                .host(brokerInfo.getHost())
                                .port(brokerInfo.getPort())
                                .status(BrokerStatus.ONLINE)
                                .build());
                    }
                }

                // Get leader node
                BrokerNode leader = null;
                BrokerInfo leaderInfo = metadataStateMachine.getBroker(partInfo.getLeaderId());
                if (leaderInfo != null) {
                    leader = BrokerNode.builder()
                            .brokerId(leaderInfo.getBrokerId())
                            .host(leaderInfo.getHost())
                            .port(leaderInfo.getPort())
                            .status(BrokerStatus.ONLINE)
                            .build();
                }

                com.distributedmq.common.model.PartitionMetadata partMetadata = 
                        com.distributedmq.common.model.PartitionMetadata.builder()
                                .topicName(partInfo.getTopicName())
                                .partitionId(partInfo.getPartitionId())
                                .leader(leader)
                                .replicas(replicas)
                                .isr(isr)
                                .startOffset(partInfo.getStartOffset())
                                .endOffset(partInfo.getEndOffset())
                                .build();
                
                partitions.add(partMetadata);
            }
            
            metadata.setPartitions(partitions);
        }

        return metadata;
    }

    @Override
    public List<String> listTopics() {
        log.debug("Listing all topics");

        // Read from state machine (works on all nodes - leader and followers)
        Map<String, TopicInfo> topics = metadataStateMachine.getAllTopics();
        return new ArrayList<>(topics.keySet());
    }

    @Override
    @Transactional
    public void deleteTopic(String topicName) {
        log.info("Deleting topic: {}", topicName);

        // Only active controller can delete topics
        if (!raftController.isControllerLeader()) {
            throw new IllegalStateException("Only active controller can delete topics. Current leader: " +
                    raftController.getControllerLeaderId());
        }

        // Check if topic exists in state machine
        if (!metadataStateMachine.topicExists(topicName)) {
            throw new IllegalArgumentException("Topic not found: " + topicName);
        }

        // Step 1: Create DeleteTopicCommand and submit to Raft
        DeleteTopicCommand deleteCommand = new DeleteTopicCommand(topicName, System.currentTimeMillis());

        try {
            // Step 2: Submit to Raft and wait for commit
            CompletableFuture<Void> deleteFuture = raftController.appendCommand(deleteCommand);
            deleteFuture.get(10, TimeUnit.SECONDS);
            log.info("Topic {} deleted via Raft consensus", topicName);
        } catch (Exception e) {
            log.error("Failed to delete topic {} via Raft", topicName, e);
            throw new IllegalStateException("Failed to delete topic via Raft consensus", e);
        }

        // Step 3: Cleanup partitions via controller service
        controllerService.cleanupTopicPartitions(topicName);

        // Step 4: Async delete from database (leader only, non-blocking)
        asyncDeleteTopic(topicName);

        // Step 5: Push cluster metadata update (without the deleted topic) to storage nodes
        try {
            // Get all topics metadata from state machine
            List<TopicMetadata> remainingTopics = new ArrayList<>();
            Map<String, TopicInfo> allTopics = metadataStateMachine.getAllTopics();
            for (TopicInfo topicInfo : allTopics.values()) {
                TopicMetadata metadata = convertTopicInfoToMetadata(topicInfo);
                remainingTopics.add(metadata);
            }

            List<MetadataUpdateResponse> pushResponses = metadataPushService.pushFullClusterMetadata(controllerService.getActiveBrokers());
            long successCount = pushResponses.stream().filter(MetadataUpdateResponse::isSuccess).count();
            log.info("Cluster metadata push after topic deletion completed: {}/{} storage nodes successful", successCount, pushResponses.size());
        } catch (Exception e) {
            log.error("Failed to push cluster metadata update after deleting topic {}: {}", topicName, e.getMessage());
            // Don't fail the deletion if push fails
        }

        log.info("Successfully deleted topic: {}", topicName);
    }

    /**
     * Async delete topic from database (leader only, non-blocking)
     */
    @Async("dbPersistenceExecutor")
    private void asyncDeleteTopic(String topicName) {
        try {
            // Only leader deletes from database
            if (!raftController.isControllerLeader()) {
                log.debug("Skipping async delete on non-leader node for topic: {}", topicName);
                return;
            }

            // Delete partitions first
            asyncDeletePartitions(topicName);

            // Then delete topic
            Optional<TopicEntity> entity = topicRepository.findByTopicName(topicName);
            if (entity.isPresent()) {
                topicRepository.delete(entity.get());
                log.info("Async deleted topic {} from database", topicName);
            }
        } catch (Exception e) {
            log.error("Failed to async delete topic {} from database", topicName, e);
            // Don't throw - this is async backup only, Raft log is source of truth
        }
    }

    @Override
    @Transactional
    public BrokerResponse registerBroker(RegisterBrokerRequest request) {
        log.info("Registering broker: {}", request.getId());

        // Only active controller can register brokers
        if (!raftController.isControllerLeader()) {
            throw new IllegalStateException("Only active controller can register brokers. Current leader: " +
                    raftController.getControllerLeaderId());
        }

        // Check if broker already exists in state machine
        BrokerInfo existingBroker = metadataStateMachine.getBroker(request.getId());
        if (existingBroker != null) {
            // If broker exists with same details, return existing broker (idempotent operation)
            if (existingBroker.getHost().equals(request.getHost()) &&
                existingBroker.getPort() == request.getPort()) {
                log.info("Broker {} already exists with same details, returning existing broker", request.getId());
                return BrokerResponse.builder()
                        .id(existingBroker.getBrokerId())
                        .host(existingBroker.getHost())
                        .port(existingBroker.getPort())
                        .status(existingBroker.getStatus().name())
                        .address(existingBroker.getHost() + ":" + existingBroker.getPort())
                        .registeredAt(existingBroker.getRegistrationTime())
                        .build();
            } else {
                // Broker exists but with different details - this is a conflict
                throw new IllegalArgumentException("Broker already exists with different details: " + request.getId());
            }
        }

        // Register broker through Raft consensus via controller service
        controllerService.registerBroker(BrokerNode.builder()
                .brokerId(request.getId())
                .host(request.getHost())
                .port(request.getPort())
                .build());

        // Get the broker from state machine (should be there now after Raft consensus)
        BrokerInfo registeredBroker = metadataStateMachine.getBroker(request.getId());
        if (registeredBroker == null) {
            throw new RuntimeException("Broker registration failed - broker not found in state machine after Raft consensus");
        }

        // Async persist broker to database (leader only, non-blocking)
        asyncPersistBroker(request.getId());

        log.info("Successfully registered broker: {}", registeredBroker.getBrokerId());

        return BrokerResponse.builder()
                .id(registeredBroker.getBrokerId())
                .host(registeredBroker.getHost())
                .port(registeredBroker.getPort())
                .status(registeredBroker.getStatus().name())
                .address(registeredBroker.getHost() + ":" + registeredBroker.getPort())
                .registeredAt(registeredBroker.getRegistrationTime())
                .build();
    }

    @Override
    public BrokerResponse getBroker(Integer brokerId) {
        log.debug("Getting broker: {}", brokerId);

        // Read from Raft state machine
        BrokerInfo brokerInfo = metadataStateMachine.getBroker(brokerId);
        if (brokerInfo == null) {
            throw new IllegalArgumentException("Broker not found: " + brokerId);
        }

        return BrokerResponse.builder()
                .id(brokerInfo.getBrokerId())
                .host(brokerInfo.getHost())
                .port(brokerInfo.getPort())
                .status(brokerInfo.getStatus().name())
                .address(brokerInfo.getHost() + ":" + brokerInfo.getPort())
                .registeredAt(brokerInfo.getRegistrationTime())
                .build();
    }

    @Override
    public List<BrokerResponse> listBrokers() {
        log.debug("Listing all brokers");

        // Read from Raft state machine (consistent across all nodes)
        Map<Integer, BrokerInfo> allBrokers = metadataStateMachine.getAllBrokers();
        return allBrokers.values().stream()
                .map(brokerInfo -> BrokerResponse.builder()
                        .id(brokerInfo.getBrokerId())
                        .host(brokerInfo.getHost())
                        .port(brokerInfo.getPort())
                        .status(brokerInfo.getStatus().name())
                        .address(brokerInfo.getHost() + ":" + brokerInfo.getPort())
                        .registeredAt(brokerInfo.getRegistrationTime())
                        .build())
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public void updateBrokerStatus(Integer brokerId, String status) {
        log.info("Updating broker {} status to: {}", brokerId, status);

        Optional<BrokerEntity> entityOpt = brokerRepository.findById(brokerId);
        if (entityOpt.isEmpty()) {
            throw new IllegalArgumentException("Broker not found: " + brokerId);
        }

        BrokerEntity entity = entityOpt.get();
        entity.setStatus(status);

        brokerRepository.save(entity);

        // Update controller service
        if ("ONLINE".equals(status)) {
            controllerService.registerBroker(BrokerNode.builder()
                    .brokerId(brokerId)
                    .host(entity.getHost())
                    .port(entity.getPort())
                    .status(BrokerStatus.ONLINE)
                    .build());
        } else {
            controllerService.unregisterBroker(brokerId);
        }

        log.info("Successfully updated broker {} status to: {}", brokerId, status);
    }

    @Override
    public void pushAllMetadataToService(String serviceUrl) {
        log.info("Pushing all metadata to service: {}", serviceUrl);

        // Only active controller can push metadata
        if (!raftController.isControllerLeader()) {
            throw new IllegalStateException("Only active controller can push metadata. Current leader: " +
                    raftController.getControllerLeaderId());
        }

        // Get all topics from database
        List<TopicEntity> allTopics = topicRepository.findAll();

        // Push each topic metadata to the requesting service
        for (TopicEntity topicEntity : allTopics) {
            TopicMetadata metadata = topicEntity.toMetadata();
            try {
                // Push to metadata service endpoint instead of storage service
                String metadataEndpoint = serviceUrl + "/api/v1/metadata/receive-metadata";
                restTemplate.postForObject(metadataEndpoint, createMetadataUpdateRequest(metadata), MetadataUpdateResponse.class);
                log.debug("Successfully pushed topic {} metadata to metadata service {}", metadata.getTopicName(), serviceUrl);
            } catch (Exception e) {
                log.error("Error pushing topic {} metadata to metadata service {}: {}", metadata.getTopicName(), serviceUrl, e.getMessage());
            }
        }

        // Push broker/cluster metadata
        try {
            String metadataEndpoint = serviceUrl + "/api/v1/metadata/receive-metadata";
            restTemplate.postForObject(metadataEndpoint, createClusterMetadataUpdateRequest(), MetadataUpdateResponse.class);
            log.debug("Successfully pushed cluster metadata to metadata service {}", serviceUrl);
        } catch (Exception e) {
            log.error("Error pushing cluster metadata to metadata service {}: {}", serviceUrl, e.getMessage());
        }

        log.info("Completed pushing all metadata to service: {}", serviceUrl);
    }

    @Override
    public void receiveMetadataFromController(com.distributedmq.common.dto.MetadataUpdateRequest metadataUpdate) {
        log.info("Receiving metadata from active controller");

        // Only non-active controllers should receive metadata
        if (raftController.isControllerLeader()) {
            log.warn("Active controller received metadata push - this should not happen");
            return;
        }

        // Process topic metadata
        if (metadataUpdate.getPartitions() != null) {
            for (com.distributedmq.common.dto.MetadataUpdateRequest.PartitionMetadata partition : metadataUpdate.getPartitions()) {
                // Group partitions by topic
                String topicName = partition.getTopic();
                TopicMetadata existingMetadata = metadataCache.get(topicName);

                if (existingMetadata == null) {
                    // Create new topic metadata
                    existingMetadata = TopicMetadata.builder()
                            .topicName(topicName)
                            .partitionCount(1) // Will be updated as more partitions are received
                            .replicationFactor(1) // Placeholder
                            .createdAt(System.currentTimeMillis())
                            .build();
                }

                // Add/update partition
                // TODO: Properly reconstruct TopicMetadata from partition data
                // For now, just mark that we have data for this topic
                metadataCache.put(topicName, existingMetadata);
            }
        }

        hasSyncedData = true;
        log.info("Successfully received and cached metadata from active controller");
    }

    @Override
    public Long getLastMetadataUpdateTimestamp() {
        // For non-active controllers, return the timestamp when we last received data
        // For active controller, return current time (always up to date)
        if (raftController.isControllerLeader()) {
            return System.currentTimeMillis();
        } else {
            // TODO: Track actual last update timestamp when receiving metadata
            // For now, return a timestamp based on when we synced
            return hasSyncedData ? System.currentTimeMillis() - 60000 : 0L; // 1 minute ago if synced, 0 if not
        }
    }

    @Override
    public MetadataUpdateResponse processStorageUpdate(MetadataUpdateRequest storageUpdate) {
        log.info("Processing metadata update from storage service");

        try {
            // Update local metadata information
            updateLocalMetadataFromStorage(storageUpdate);

            // If this is not the active controller, notify the controller
            if (!raftController.isControllerLeader()) {
                notifyControllerAboutStorageUpdate(storageUpdate);
            } else {
                // This is the controller - update controller metadata and propagate to other services
                updateControllerMetadataAndPropagate(storageUpdate);
            }

            return MetadataUpdateResponse.builder()
                    .success(true)
                    .processedTimestamp(System.currentTimeMillis())
                    .build();

        } catch (Exception e) {
            log.error("Error processing storage update: {}", e.getMessage());
            return MetadataUpdateResponse.builder()
                    .success(false)
                    .errorCode(MetadataUpdateResponse.ErrorCode.PROCESSING_ERROR)
                    .errorMessage("Failed to process storage update: " + e.getMessage())
                    .processedTimestamp(System.currentTimeMillis())
                    .build();
        }
    }

    /**
     * Create MetadataUpdateRequest from TopicMetadata
     */
    private com.distributedmq.common.dto.MetadataUpdateRequest createMetadataUpdateRequest(TopicMetadata topicMetadata) {
        // Convert partitions
        List<com.distributedmq.common.dto.MetadataUpdateRequest.PartitionMetadata> partitionMetadatas =
                topicMetadata.getPartitions().stream()
                        .map(this::convertPartitionMetadata)
                        .collect(Collectors.toList());

        // Get all active brokers for broker info
        List<com.distributedmq.common.model.BrokerNode> activeBrokers = controllerService.getActiveBrokers();
        List<com.distributedmq.common.dto.MetadataUpdateRequest.BrokerInfo> brokerInfos = activeBrokers.stream()
                .map(this::convertBrokerNodeToBrokerInfo)
                .collect(Collectors.toList());

        return com.distributedmq.common.dto.MetadataUpdateRequest.builder()
                .brokers(brokerInfos)
                .partitions(partitionMetadatas)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    /**
     * Create cluster metadata update request (brokers only)
     */
    private com.distributedmq.common.dto.MetadataUpdateRequest createClusterMetadataUpdateRequest() {
        List<com.distributedmq.common.model.BrokerNode> activeBrokers = controllerService.getActiveBrokers();
        List<com.distributedmq.common.dto.MetadataUpdateRequest.BrokerInfo> brokerInfos = activeBrokers.stream()
                .map(this::convertBrokerNodeToBrokerInfo)
                .collect(Collectors.toList());

        return com.distributedmq.common.dto.MetadataUpdateRequest.builder()
                .brokers(brokerInfos)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    /**
     * Convert PartitionMetadata to MetadataUpdateRequest.PartitionMetadata
     */
    private com.distributedmq.common.dto.MetadataUpdateRequest.PartitionMetadata convertPartitionMetadata(
            com.distributedmq.common.model.PartitionMetadata partitionMetadata) {

        return com.distributedmq.common.dto.MetadataUpdateRequest.PartitionMetadata.builder()
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
    private com.distributedmq.common.dto.MetadataUpdateRequest.BrokerInfo convertBrokerNodeToBrokerInfo(
            com.distributedmq.common.model.BrokerNode brokerNode) {
        return com.distributedmq.common.dto.MetadataUpdateRequest.BrokerInfo.builder()
                .id(brokerNode.getBrokerId())
                .host(brokerNode.getHost())
                .port(brokerNode.getPort())
                .isAlive(brokerNode.getStatus() == com.distributedmq.common.model.BrokerStatus.ONLINE)
                .build();
    }

    /**
     * Get controller info from a peer metadata service
     * Used when this node doesn't know who the active controller is
     */
    private Integer getControllerIdFromPeer(String peerUrl) {
        try {
            String url = peerUrl + "/api/v1/metadata/controller";
            // TODO: Create proper response DTO
            // For now, assume we can get controller info
            log.debug("Getting controller info from peer: {}", peerUrl);
            // Placeholder - need to implement proper deserialization
            return null;
        } catch (RestClientException e) {
            log.warn("Failed to get controller info from peer {}: {}", peerUrl, e.getMessage());
            return null;
        }
    }

    /**
     * Request the active controller to push metadata to this service
     */
    private void requestMetadataSyncFromController(String controllerUrl) {
        try {
            String url = controllerUrl + "/api/v1/metadata/sync";
            // Include this service's URL as a parameter so controller knows where to push
            String thisServiceUrl = "http://localhost:" + System.getProperty("server.port", "9091"); // Use current service URL
            url += "?requestingServiceUrl=" + thisServiceUrl;

            log.info("Requesting metadata sync from controller: {}", url);

            // Make POST request to trigger sync
            restTemplate.postForObject(url, null, Void.class);

        } catch (RestClientException e) {
            log.error("Failed to request metadata sync from controller {}: {}", controllerUrl, e.getMessage());
            throw new RuntimeException("Failed to sync metadata from controller", e);
        }
    }

    /**
     * Synchronize metadata from active controller when data is missing
     */
    private void synchronizeMetadataIfNeeded() {
        if (raftController.isControllerLeader()) {
            return; // Active controller doesn't need to sync
        }

        if (hasSyncedData) {
            return; // Already have synced data
        }

        log.info("Metadata missing, triggering synchronization from active controller");

        // Step 1: Get controller ID from a peer metadata service
        Integer controllerId = getControllerIdFromPeer(getPeerMetadataServiceUrl());
        if (controllerId == null) {
            throw new IllegalStateException("Cannot determine active controller ID from peers");
        }

        // Step 2: Request controller to push metadata to this service
        String controllerUrl = getControllerUrl(controllerId);
        requestMetadataSyncFromController(controllerUrl);

        // Note: hasSyncedData will be set to true when data is received via receiveMetadataFromController
        // For now, we'll assume the sync was successful and data will arrive soon
        log.info("Metadata synchronization request sent to controller {}", controllerId);
    }

    /**
     * Get URL of a peer metadata service (placeholder implementation)
     * TODO: Implement proper service discovery
     */
    private String getPeerMetadataServiceUrl() {
        // Placeholder: try known metadata service peers on ports 9091, 9092, 9093
        // In real implementation, this would use service discovery
        String[] peerUrls = {"http://localhost:9091", "http://localhost:9092", "http://localhost:9093"};

        for (String url : peerUrls) {
            try {
                // Quick check if service is available
                restTemplate.getForObject(url + "/api/v1/metadata/controller", String.class);
                return url;
            } catch (Exception e) {
                log.debug("Peer {} not available: {}", url, e.getMessage());
            }
        }

        throw new IllegalStateException("No peer metadata services available for controller discovery");
    }

    /**
     * Get the controller's current metadata timestamp (truth value)
     */
    private Long getControllerMetadataTimestamp() {
        // The controller's timestamp is the current time, as it's always up to date
        // In a real implementation, this could be the timestamp of the last committed metadata change
        return System.currentTimeMillis();
    }

    /**
     * Get URL for a metadata service (placeholder implementation)
     */
    private String getMetadataServiceUrl(Integer serviceId) {
        // Metadata services run on ports 9091, 9092, 9093 for service IDs 1, 2, 3
        int basePort = 9090; // 9090 + serviceId = correct port
        return "http://localhost:" + (basePort + serviceId);
    }

    /**
     * Get controller URL from controller ID
     */
    private String getControllerUrl(Integer controllerId) {
        // Metadata services run on ports 9091, 9092, 9093 for service IDs 1, 2, 3
        int basePort = 9090; // 9090 + controllerId = correct port
        return "http://localhost:" + (basePort + controllerId);
    }

    /**
     * Get the current service ID (placeholder implementation)
     * TODO: Implement proper service ID discovery
     */
    private Integer getCurrentServiceId() {
        // Determine service ID based on server port
        // Metadata services: 9091=ID1, 9092=ID2, 9093=ID3
        String port = System.getProperty("server.port", "9091");
        switch (port) {
            case "9091": return 1;
            case "9092": return 2;
            case "9093": return 3;
            default: return 1; // Default to controller
        }
    }

    /**
     * Update local metadata from storage service update
     */
    private void updateLocalMetadataFromStorage(MetadataUpdateRequest storageUpdate) {
        log.debug("Updating local metadata from storage update");

        // For non-controller metadata services, update the in-memory cache
        if (!raftController.isControllerLeader()) {
            // Process partition updates
            if (storageUpdate.getPartitions() != null) {
                for (MetadataUpdateRequest.PartitionMetadata partition : storageUpdate.getPartitions()) {
                    String topicName = partition.getTopic();
                    TopicMetadata existingMetadata = metadataCache.get(topicName);

                    if (existingMetadata == null) {
                        // Create basic topic metadata if it doesn't exist
                        existingMetadata = TopicMetadata.builder()
                                .topicName(topicName)
                                .partitionCount(1)
                                .replicationFactor(1)
                                .createdAt(System.currentTimeMillis())
                                .build();
                    }

                    // Update cache with new information
                    metadataCache.put(topicName, existingMetadata);
                }
            }
        }

        // Update last metadata timestamp
        // This will be used for metadata synchronization
        log.debug("Local metadata updated from storage service");
    }

    /**
     * Notify the active controller about storage update
     */
    private void notifyControllerAboutStorageUpdate(MetadataUpdateRequest storageUpdate) {
        try {
            // Get controller URL
            String controllerUrl = getControllerUrl(raftController.getControllerLeaderId());
            String endpoint = controllerUrl + "/api/v1/metadata/storage-updates";

            log.info("Notifying controller about storage update: {}", controllerUrl);

            // Send the update to controller
            MetadataUpdateResponse response = restTemplate.postForObject(endpoint, storageUpdate, MetadataUpdateResponse.class);

            if (response != null && response.isSuccess()) {
                log.info("Successfully notified controller about storage update");
            } else {
                log.warn("Controller notification failed: {}", response != null ? response.getErrorMessage() : "null response");
            }

        } catch (Exception e) {
            log.error("Failed to notify controller about storage update: {}", e.getMessage());
        }
    }

    /**
     * Update controller metadata and propagate to other services
     */
    private void updateControllerMetadataAndPropagate(MetadataUpdateRequest storageUpdate) {
        log.info("Controller updating metadata and propagating to other services");

        // Update controller's database with the new information
        updateControllerDatabaseFromStorage(storageUpdate);

        // Propagate the update to all other metadata services
        propagateUpdateToAllMetadataServices(storageUpdate);

        log.info("Controller metadata update and propagation completed");
    }

    /**
     * Update controller's database from storage update
     */
    private void updateControllerDatabaseFromStorage(MetadataUpdateRequest storageUpdate) {
        // Process partition leadership changes
        if (storageUpdate.getPartitions() != null) {
            for (MetadataUpdateRequest.PartitionMetadata partition : storageUpdate.getPartitions()) {
                // Update partition leadership in controller's view
                controllerService.updatePartitionLeadership(
                        partition.getTopic(),
                        partition.getPartition(),
                        partition.getLeaderId(),
                        partition.getFollowerIds(),
                        partition.getIsrIds()
                );
            }
        }

        // Process broker status changes
        if (storageUpdate.getBrokers() != null) {
            for (MetadataUpdateRequest.BrokerInfo brokerInfo : storageUpdate.getBrokers()) {
                if (!brokerInfo.isAlive()) {
                    // Handle broker failure
                    controllerService.handleBrokerFailure(brokerInfo.getId());
                } else {
                    // Update broker information
                    controllerService.registerBroker(BrokerNode.builder()
                            .brokerId(brokerInfo.getId())
                            .host(brokerInfo.getHost())
                            .port(brokerInfo.getPort())
                            .status(BrokerStatus.ONLINE)
                            .build());
                }
            }
        }

        log.debug("Controller database updated from storage update");
    }

    /**
     * Propagate update to all registered metadata services
     */
    private void propagateUpdateToAllMetadataServices(MetadataUpdateRequest storageUpdate) {
        // Since heartbeat functionality has been removed, we no longer maintain a registry
        // of metadata services. In a real implementation, this would use service discovery
        // to find all metadata services and propagate updates to them.
        log.info("Metadata service propagation skipped - heartbeat registry removed");
    }

    /**
     * Push metadata update to the paired storage service
     */
    @Override
    public void removeFromISR(String topic, Integer partition, Integer brokerId) {
        log.info("Removing broker {} from ISR for partition {}-{}", brokerId, topic, partition);

        if (raftController.isControllerLeader()) {
            // Active controller: update via controller service
            controllerService.removeFromISR(topic, partition, brokerId);

            // Propagate the change to storage services
            List<Integer> updatedISR = controllerService.getISR(topic, partition);
            propagateISRUpdateToStorageServices(topic, partition, updatedISR);
        } else {
            // Non-active controller: this should not happen as only controller manages ISR
            log.warn("Non-active controller received removeFromISR request for {}-{}", topic, partition);
        }
    }

    @Override
    public void addToISR(String topic, Integer partition, Integer brokerId) {
        log.info("Adding broker {} to ISR for partition {}-{}", brokerId, topic, partition);

        if (raftController.isControllerLeader()) {
            // Active controller: update via controller service
            controllerService.addToISR(topic, partition, brokerId);

            // Propagate the change to storage services
            List<Integer> updatedISR = controllerService.getISR(topic, partition);
            propagateISRUpdateToStorageServices(topic, partition, updatedISR);
        } else {
            // Non-active controller: this should not happen as only controller manages ISR
            log.warn("Non-active controller received addToISR request for {}-{}", topic, partition);
        }
    }

    @Override
    public List<Integer> getISR(String topic, Integer partition) {
        // Get ISR from controller service (works for both active and non-active controllers)
        return controllerService.getISR(topic, partition);
    }

    @Override
    public boolean isInISR(String topic, Integer partition, Integer brokerId) {
        List<Integer> isr = getISR(topic, partition);
        return isr.contains(brokerId);
    }

    /**
     * Propagate ISR updates to all storage services
     */
    private void propagateISRUpdateToStorageServices(String topicName, int partitionId, List<Integer> isr) {
        log.info("Propagating ISR update for {}-{} to all storage services", topicName, partitionId);

        // Get partition info from controller service
        Integer leaderId = controllerService.getPartitionLeader(topicName, partitionId);
        List<Integer> followerIds = controllerService.getPartitionFollowers(topicName, partitionId);

        // Create partition metadata update
        MetadataUpdateRequest.PartitionMetadata partitionUpdate =
                new MetadataUpdateRequest.PartitionMetadata();
        partitionUpdate.setTopic(topicName);
        partitionUpdate.setPartition(partitionId);
        partitionUpdate.setLeaderId(leaderId);
        partitionUpdate.setFollowerIds(followerIds);
        partitionUpdate.setIsrIds(isr);
        partitionUpdate.setLeaderEpoch(System.currentTimeMillis());

        MetadataUpdateRequest updateRequest = MetadataUpdateRequest.builder()
                .partitions(List.of(partitionUpdate))
                .timestamp(System.currentTimeMillis())
                .build();

        // Get all storage services from service discovery
        List<com.distributedmq.common.config.ServiceDiscovery.StorageServiceInfo> storageServices =
            com.distributedmq.common.config.ServiceDiscovery.getAllStorageServices();

        for (com.distributedmq.common.config.ServiceDiscovery.StorageServiceInfo storageService : storageServices) {
            try {
                String serviceUrl = storageService.getUrl();
                String endpoint = serviceUrl + "/api/v1/storage/metadata";

                log.debug("Propagating ISR update to storage service {}: {}", storageService.getId(), serviceUrl);

                // Send the update
                restTemplate.postForObject(endpoint, updateRequest, MetadataUpdateResponse.class);

            } catch (Exception e) {
                log.error("Failed to propagate ISR update to storage service {}: {}",
                        storageService.getId(), e.getMessage());
            }
        }

        log.info("Completed ISR update propagation to storage services");
    }

    @Override
    public long getMetadataVersion() {
        return metadataStateMachine.getMetadataVersion();
    }

    @Override
    public com.distributedmq.metadata.dto.ClusterMetadataResponse getClusterMetadata() {
        log.debug("Building cluster metadata response");
        
        // Get all brokers
        List<BrokerResponse> brokers = listBrokers();
        
        // Get all topics with full metadata
        List<String> topicNames = listTopics();
        List<com.distributedmq.metadata.dto.TopicMetadataResponse> topics = topicNames.stream()
                .map(topicName -> {
                    try {
                        TopicMetadata metadata = getTopicMetadata(topicName);
                        return com.distributedmq.metadata.dto.TopicMetadataResponse.from(metadata);
                    } catch (Exception e) {
                        log.error("Failed to get metadata for topic {}: {}", topicName, e.getMessage());
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        
        // Calculate total partitions
        int totalPartitions = topics.stream()
                .mapToInt(com.distributedmq.metadata.dto.TopicMetadataResponse::getPartitionCount)
                .sum();
        
        // Build controller info
        Integer controllerId = raftController.getControllerLeaderId();
        String controllerUrl = com.distributedmq.common.config.ServiceDiscovery.getMetadataServiceUrl(controllerId);
        Long controllerTerm = raftController.getCurrentTerm();
        
        com.distributedmq.common.dto.ControllerInfo controllerInfo = com.distributedmq.common.dto.ControllerInfo.builder()
                .controllerId(controllerId)
                .controllerUrl(controllerUrl)
                .controllerTerm(controllerTerm)
                .timestamp(System.currentTimeMillis())
                .build();
        
        // Build active metadata nodes list
        List<com.distributedmq.common.dto.MetadataNodeInfo> activeMetadataNodes = 
                com.distributedmq.common.config.ServiceDiscovery.getAllMetadataServices().stream()
                .map(metadataService -> com.distributedmq.common.dto.MetadataNodeInfo.builder()
                        .id(metadataService.getId())
                        .url(metadataService.getUrl())
                        .isLeader(metadataService.getId().equals(controllerId))
                        .healthy(true) // TODO: Implement health check mechanism
                        .lastSeen(System.currentTimeMillis())
                        .build())
                .collect(Collectors.toList());
        
        return com.distributedmq.metadata.dto.ClusterMetadataResponse.builder()
                .version(getMetadataVersion())
                .brokers(brokers)
                .topics(topics)
                .timestamp(System.currentTimeMillis())
                .controllerLeaderId(raftController.getControllerLeaderId())
                .totalPartitions(totalPartitions)
                .controllerInfo(controllerInfo)
                .activeMetadataNodes(activeMetadataNodes)
                .build();
    }
}
