package com.distributedmq.metadata.service;

import com.distributedmq.common.dto.ConsumerGroupResponse;
import com.distributedmq.common.model.BrokerStatus;
import com.distributedmq.metadata.coordination.*;
import com.distributedmq.metadata.entity.ConsumerGroupEntity;
import com.distributedmq.metadata.repository.ConsumerGroupRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Implementation of ConsumerGroupService
 * Manages minimal consumer group registry with Raft consensus
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConsumerGroupServiceImpl implements ConsumerGroupService {

    private final RaftController raftController;
    private final MetadataStateMachine metadataStateMachine;
    private final ConsumerGroupRepository consumerGroupRepository;

    @Override
    public ConsumerGroupResponse findOrCreateGroup(String topic, String appId) {
        log.info("Finding or creating consumer group for topic={}, appId={}", topic, appId);

        // Only active controller can manage consumer groups
        if (!raftController.isControllerLeader()) {
            throw new IllegalStateException("Only active controller can manage consumer groups. Current leader: " +
                    raftController.getControllerLeaderId());
        }

        // Check if group already exists in state machine
        ConsumerGroupInfo existing = metadataStateMachine.getConsumerGroup(topic, appId);
        
        if (existing != null) {
            log.info("Consumer group already exists: groupId={}, leader=broker-{}", 
                     existing.getGroupId(), existing.getGroupLeaderBrokerId());
            return buildResponse(existing);
        }

        // Group doesn't exist - create new one
        String groupId = ConsumerGroupEntity.generateGroupId(topic, appId);
        Integer selectedBroker = selectGroupLeaderBroker(topic);

        log.info("Creating new consumer group: groupId={}, topic={}, appId={}, leader=broker-{}", 
                 groupId, topic, appId, selectedBroker);

        // Submit RegisterConsumerGroupCommand to Raft
        RegisterConsumerGroupCommand command = RegisterConsumerGroupCommand.builder()
                .groupId(groupId)
                .topic(topic)
                .appId(appId)
                .groupLeaderBrokerId(selectedBroker)
                .timestamp(System.currentTimeMillis())
                .build();

        try {
            CompletableFuture<Void> future = raftController.appendCommand(command);
            future.get(10, TimeUnit.SECONDS);
            log.info("Consumer group {} registered via Raft consensus", groupId);
        } catch (Exception e) {
            log.error("Failed to register consumer group {} via Raft", groupId, e);
            throw new IllegalStateException("Failed to register consumer group via Raft consensus", e);
        }

        // Async persist to database (leader only, non-blocking)
        asyncPersistConsumerGroup(groupId);

        // Get the group from state machine and return
        ConsumerGroupInfo created = metadataStateMachine.getConsumerGroupById(groupId);
        if (created == null) {
            throw new RuntimeException("Consumer group registration failed - group not found in state machine after Raft consensus");
        }

        return buildResponse(created);
    }

    @Override
    public void updateGroupLeader(String groupId, Integer newGroupLeaderBrokerId) {
        log.info("Updating consumer group {} leader to broker-{}", groupId, newGroupLeaderBrokerId);

        // Only active controller can update consumer groups
        if (!raftController.isControllerLeader()) {
            throw new IllegalStateException("Only active controller can update consumer groups. Current leader: " +
                    raftController.getControllerLeaderId());
        }

        // Verify group exists
        ConsumerGroupInfo existing = metadataStateMachine.getConsumerGroupById(groupId);
        if (existing == null) {
            throw new IllegalArgumentException("Consumer group not found: " + groupId);
        }

        // Verify new broker exists and is online
        BrokerInfo broker = metadataStateMachine.getBroker(newGroupLeaderBrokerId);
        if (broker == null) {
            throw new IllegalArgumentException("Broker not found: " + newGroupLeaderBrokerId);
        }
        if (broker.getStatus() != BrokerStatus.ONLINE) {
            throw new IllegalStateException("Broker " + newGroupLeaderBrokerId + " is not online");
        }

        // Submit UpdateConsumerGroupLeaderCommand to Raft
        UpdateConsumerGroupLeaderCommand command = UpdateConsumerGroupLeaderCommand.builder()
                .groupId(groupId)
                .newGroupLeaderBrokerId(newGroupLeaderBrokerId)
                .timestamp(System.currentTimeMillis())
                .build();

        try {
            CompletableFuture<Void> future = raftController.appendCommand(command);
            future.get(10, TimeUnit.SECONDS);
            log.info("Consumer group {} leader updated to broker-{} via Raft consensus", groupId, newGroupLeaderBrokerId);
        } catch (Exception e) {
            log.error("Failed to update consumer group {} leader via Raft", groupId, e);
            throw new IllegalStateException("Failed to update consumer group leader via Raft consensus", e);
        }

        // Async update database
        asyncUpdateConsumerGroupLeader(groupId, newGroupLeaderBrokerId);
    }

    @Override
    @Transactional
    public void deleteGroup(String groupId, Integer requestingBrokerId) {
        log.info("Deleting consumer group: groupId={}, requestingBroker={}", groupId, requestingBrokerId);

        // Only active controller can delete consumer groups
        if (!raftController.isControllerLeader()) {
            throw new IllegalStateException("Only active controller can delete consumer groups. Current leader: " +
                    raftController.getControllerLeaderId());
        }

        // Verify group exists and requesting broker is the current leader
        ConsumerGroupInfo existing = metadataStateMachine.getConsumerGroupById(groupId);
        if (existing == null) {
            log.warn("Cannot delete non-existent consumer group: {}", groupId);
            return;  // Idempotent - already gone
        }

        if (!existing.getGroupLeaderBrokerId().equals(requestingBrokerId)) {
            throw new IllegalStateException(String.format(
                    "Only group leader can delete group. Current leader: broker-%d, Requesting: broker-%d",
                    existing.getGroupLeaderBrokerId(), requestingBrokerId));
        }

        // Submit DeleteConsumerGroupCommand to Raft
        DeleteConsumerGroupCommand command = DeleteConsumerGroupCommand.builder()
                .groupId(groupId)
                .timestamp(System.currentTimeMillis())
                .build();

        try {
            CompletableFuture<Void> future = raftController.appendCommand(command);
            future.get(10, TimeUnit.SECONDS);
            log.info("Consumer group {} deleted via Raft consensus", groupId);
        } catch (Exception e) {
            log.error("Failed to delete consumer group {} via Raft", groupId, e);
            throw new IllegalStateException("Failed to delete consumer group via Raft consensus", e);
        }

        // Async delete from database
        asyncDeleteConsumerGroup(groupId);
    }

    @Override
    public ConsumerGroupResponse getGroupById(String groupId) {
        ConsumerGroupInfo groupInfo = metadataStateMachine.getConsumerGroupById(groupId);
        if (groupInfo == null) {
            return null;
        }
        return buildResponse(groupInfo);
    }

    @Override
    public List<ConsumerGroupResponse> getAllGroups() {
        return metadataStateMachine.getAllConsumerGroups().values().stream()
                .map(this::buildResponse)
                .collect(Collectors.toList());
    }

    /**
     * Select a broker to act as group leader
     * Strategy: Round-robin or least-loaded broker selection
     * Future optimization: Prefer brokers that are partition leaders for this topic
     */
    private Integer selectGroupLeaderBroker(String topic) {
        // Get all ONLINE brokers
        List<BrokerInfo> onlineBrokers = metadataStateMachine.getAllBrokers().values().stream()
                .filter(b -> b.getStatus() == BrokerStatus.ONLINE)
                .collect(Collectors.toList());

        if (onlineBrokers.isEmpty()) {
            throw new IllegalStateException("No online brokers available to act as group leader");
        }

        // Simple strategy: Random selection among online brokers
        // TODO: Implement load-based selection (count consumer groups per broker)
        // TODO: Prefer brokers that are partition leaders for this topic (co-location)
        int randomIndex = ThreadLocalRandom.current().nextInt(onlineBrokers.size());
        Integer selectedBrokerId = onlineBrokers.get(randomIndex).getBrokerId();

        log.debug("Selected broker-{} as group leader for topic {} ({} online brokers available)", 
                 selectedBrokerId, topic, onlineBrokers.size());
        
        return selectedBrokerId;
    }

    /**
     * Build ConsumerGroupResponse from ConsumerGroupInfo
     */
    private ConsumerGroupResponse buildResponse(ConsumerGroupInfo groupInfo) {
        // Get broker info to construct URL
        BrokerInfo broker = metadataStateMachine.getBroker(groupInfo.getGroupLeaderBrokerId());
        if (broker == null) {
            throw new IllegalStateException("Group leader broker not found: " + groupInfo.getGroupLeaderBrokerId());
        }

        String groupLeaderUrl = broker.getHost() + ":" + broker.getPort();

        return ConsumerGroupResponse.builder()
                .groupId(groupInfo.getGroupId())
                .topic(groupInfo.getTopic())
                .appId(groupInfo.getAppId())
                .groupLeaderBrokerId(groupInfo.getGroupLeaderBrokerId())
                .groupLeaderUrl(groupLeaderUrl)
                .createdAt(groupInfo.getCreatedAt())
                .lastModifiedAt(groupInfo.getLastModifiedAt())
                .build();
    }

    /**
     * Async persist consumer group to database (leader only, non-blocking)
     */
    @Async("dbPersistenceExecutor")
    private void asyncPersistConsumerGroup(String groupId) {
        try {
            // Only leader persists to database
            if (!raftController.isControllerLeader()) {
                log.debug("Skipping async persist on non-leader node for consumer group: {}", groupId);
                return;
            }

            ConsumerGroupInfo groupInfo = metadataStateMachine.getConsumerGroupById(groupId);
            if (groupInfo == null) {
                log.warn("Cannot persist consumer group {} - not found in state machine", groupId);
                return;
            }

            ConsumerGroupEntity entity = ConsumerGroupEntity.builder()
                    .groupId(groupInfo.getGroupId())
                    .topic(groupInfo.getTopic())
                    .appId(groupInfo.getAppId())
                    .groupLeaderBrokerId(groupInfo.getGroupLeaderBrokerId())
                    .createdAt(groupInfo.getCreatedAt())
                    .lastModifiedAt(groupInfo.getLastModifiedAt())
                    .build();

            consumerGroupRepository.save(entity);
            log.info("Async persisted consumer group {} to database", groupId);
        } catch (Exception e) {
            log.error("Failed to async persist consumer group {} to database", groupId, e);
            // Don't throw - this is async backup only, Raft log is source of truth
        }
    }

    /**
     * Async update consumer group leader in database
     */
    @Async("dbPersistenceExecutor")
    private void asyncUpdateConsumerGroupLeader(String groupId, Integer newLeaderBrokerId) {
        try {
            if (!raftController.isControllerLeader()) {
                log.debug("Skipping async update on non-leader node for consumer group: {}", groupId);
                return;
            }

            consumerGroupRepository.findByGroupId(groupId).ifPresent(entity -> {
                entity.setGroupLeaderBrokerId(newLeaderBrokerId);
                consumerGroupRepository.save(entity);
                log.info("Async updated consumer group {} leader to broker-{} in database", groupId, newLeaderBrokerId);
            });
        } catch (Exception e) {
            log.error("Failed to async update consumer group {} in database", groupId, e);
        }
    }

    /**
     * Async delete consumer group from database
     */
    @Async("dbPersistenceExecutor")
    private void asyncDeleteConsumerGroup(String groupId) {
        try {
            if (!raftController.isControllerLeader()) {
                log.debug("Skipping async delete on non-leader node for consumer group: {}", groupId);
                return;
            }

            consumerGroupRepository.deleteByGroupId(groupId);
            log.info("Async deleted consumer group {} from database", groupId);
        } catch (Exception e) {
            log.error("Failed to async delete consumer group {} from database", groupId, e);
        }
    }
}
