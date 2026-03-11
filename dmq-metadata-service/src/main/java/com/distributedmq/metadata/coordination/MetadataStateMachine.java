package com.distributedmq.metadata.coordination;

import com.distributedmq.common.model.BrokerStatus;
import com.distributedmq.common.model.TopicConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Metadata State Machine
 * Applies committed Raft log entries to the metadata state
 * 
 * This is where controller decisions (from Raft consensus) 
 * are applied to actual metadata (topics, partitions, brokers)
 */
@Slf4j
@Component
public class MetadataStateMachine {

    // In-memory metadata storage (replicated via Raft consensus)
    private final Map<Integer, BrokerInfo> brokers = new ConcurrentHashMap<>();
    private final Map<String, TopicInfo> topics = new ConcurrentHashMap<>();
    private final Map<String, Map<Integer, PartitionInfo>> partitions = new ConcurrentHashMap<>();
    
    // Consumer group registry - minimal routing information only
    // Key: "topic:appId" -> ConsumerGroupInfo (with group leader broker ID)
    private final Map<String, ConsumerGroupInfo> consumerGroups = new ConcurrentHashMap<>();
    
    // Metadata version - incremented on every state change for tracking staleness
    private final AtomicLong metadataVersion = new AtomicLong(0);

    /**
     * Apply a committed log entry to the metadata state
     */
    public void apply(Object command) {
        if (command == null) {
            log.warn("Received null command, ignoring");
            return;
        }
        
        log.info("Applying command: {} of type {}", command, command.getClass().getSimpleName());
        
        boolean stateChanged = false;
        
        // Handle RegisterBrokerCommand
        if (command instanceof RegisterBrokerCommand) {
            RegisterBrokerCommand cmd = (RegisterBrokerCommand) command;
            log.info("Applying RegisterBrokerCommand: brokerId={}, host={}, port={}", 
                    cmd.getBrokerId(), cmd.getHost(), cmd.getPort());
            applyRegisterBroker(cmd);
            log.info("Successfully applied RegisterBrokerCommand for broker {}", cmd.getBrokerId());
            stateChanged = true;  // New broker always represents a change
        } 
        // Handle UnregisterBrokerCommand
        else if (command instanceof UnregisterBrokerCommand) {
            applyUnregisterBroker((UnregisterBrokerCommand) command);
            stateChanged = true;  // Broker removal always represents a change
        }
        // Handle RegisterTopicCommand
        else if (command instanceof RegisterTopicCommand) {
            RegisterTopicCommand cmd = (RegisterTopicCommand) command;
            log.info("Applying RegisterTopicCommand: topic={}, partitions={}, replicationFactor={}", 
                    cmd.getTopicName(), cmd.getPartitionCount(), cmd.getReplicationFactor());
            applyRegisterTopic(cmd);
            log.info("Successfully applied RegisterTopicCommand for topic {}", cmd.getTopicName());
            stateChanged = true;  // New topic always represents a change
        }
        // Handle AssignPartitionsCommand
        else if (command instanceof AssignPartitionsCommand) {
            AssignPartitionsCommand cmd = (AssignPartitionsCommand) command;
            log.info("Applying AssignPartitionsCommand: topic={}, assignments={}", 
                    cmd.getTopicName(), cmd.getAssignments().size());
            applyAssignPartitions(cmd);
            log.info("Successfully applied AssignPartitionsCommand for topic {}", cmd.getTopicName());
            stateChanged = true;  // Partition assignments always represent a change
        }
        // Handle UpdatePartitionLeaderCommand
        else if (command instanceof UpdatePartitionLeaderCommand) {
            UpdatePartitionLeaderCommand cmd = (UpdatePartitionLeaderCommand) command;
            log.info("Applying UpdatePartitionLeaderCommand: topic={}, partition={}, newLeader={}", 
                    cmd.getTopicName(), cmd.getPartitionId(), cmd.getNewLeaderId());
            stateChanged = applyUpdatePartitionLeader(cmd);
            if (stateChanged) {
                log.info("Successfully applied UpdatePartitionLeaderCommand for {}-{}", 
                        cmd.getTopicName(), cmd.getPartitionId());
            }
        }
        // Handle UpdateISRCommand
        else if (command instanceof UpdateISRCommand) {
            UpdateISRCommand cmd = (UpdateISRCommand) command;
            log.info("Applying UpdateISRCommand: topic={}, partitionId={}, newISR={}", 
                    cmd.getTopicName(), cmd.getPartitionId(), cmd.getNewISR());
            stateChanged = applyUpdateISR(cmd);
            if (stateChanged) {
                log.info("Successfully applied UpdateISRCommand for partition {}-{}", 
                        cmd.getTopicName(), cmd.getPartitionId());
            }
        }
        // Handle UpdateBrokerStatusCommand (Phase 4)
        else if (command instanceof UpdateBrokerStatusCommand) {
            UpdateBrokerStatusCommand cmd = (UpdateBrokerStatusCommand) command;
            log.info("Applying UpdateBrokerStatusCommand: brokerId={}, status={}, heartbeatTime={}", 
                    cmd.getBrokerId(), cmd.getStatus(), cmd.getLastHeartbeatTime());
            stateChanged = applyUpdateBrokerStatus(cmd);
            if (stateChanged) {
                log.info("Successfully applied UpdateBrokerStatusCommand for broker {}", cmd.getBrokerId());
            }
        }
        // Handle DeleteTopicCommand
        else if (command instanceof DeleteTopicCommand) {
            DeleteTopicCommand cmd = (DeleteTopicCommand) command;
            log.info("Applying DeleteTopicCommand: topic={}", cmd.getTopicName());
            applyDeleteTopic(cmd);
            log.info("Successfully applied DeleteTopicCommand for topic {}", cmd.getTopicName());
            stateChanged = true;  // Topic deletion always represents a change
        }
        // Handle RegisterConsumerGroupCommand
        else if (command instanceof RegisterConsumerGroupCommand) {
            RegisterConsumerGroupCommand cmd = (RegisterConsumerGroupCommand) command;
            log.info("Applying RegisterConsumerGroupCommand: groupId={}, topic={}, appId={}, leader={}", 
                    cmd.getGroupId(), cmd.getTopic(), cmd.getAppId(), cmd.getGroupLeaderBrokerId());
            applyRegisterConsumerGroup(cmd);
            log.info("Successfully applied RegisterConsumerGroupCommand for group {}", cmd.getGroupId());
            stateChanged = true;  // New consumer group always represents a change
        }
        // Handle UpdateConsumerGroupLeaderCommand
        else if (command instanceof UpdateConsumerGroupLeaderCommand) {
            UpdateConsumerGroupLeaderCommand cmd = (UpdateConsumerGroupLeaderCommand) command;
            log.info("Applying UpdateConsumerGroupLeaderCommand: groupId={}, newLeader={}", 
                    cmd.getGroupId(), cmd.getNewGroupLeaderBrokerId());
            stateChanged = applyUpdateConsumerGroupLeader(cmd);
            if (stateChanged) {
                log.info("Successfully applied UpdateConsumerGroupLeaderCommand for group {}", cmd.getGroupId());
            }
        }
        // Handle DeleteConsumerGroupCommand
        else if (command instanceof DeleteConsumerGroupCommand) {
            DeleteConsumerGroupCommand cmd = (DeleteConsumerGroupCommand) command;
            log.info("Applying DeleteConsumerGroupCommand: groupId={}", cmd.getGroupId());
            stateChanged = applyDeleteConsumerGroup(cmd);
            if (stateChanged) {
                log.info("Successfully applied DeleteConsumerGroupCommand for group {}", cmd.getGroupId());
            }
        }
        // Handle Map-based commands (fallback for serialization issues)
        else if (command instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) command;
            
            // Try RegisterBrokerCommand
            if (map.containsKey("brokerId") && map.containsKey("host") && map.containsKey("port")) {
                log.info("Applying Map-based RegisterBrokerCommand: {}", map);
                RegisterBrokerCommand cmd = RegisterBrokerCommand.builder()
                        .brokerId(((Number) map.get("brokerId")).intValue())
                        .host((String) map.get("host"))
                        .port(((Number) map.get("port")).intValue())
                        .timestamp(map.containsKey("timestamp") ? ((Number) map.get("timestamp")).longValue() : System.currentTimeMillis())
                        .build();
                applyRegisterBroker(cmd);
                log.info("Successfully applied Map-based RegisterBrokerCommand for broker {}", cmd.getBrokerId());
                stateChanged = true;
            } 
            // Try RegisterTopicCommand (has topicName, partitionCount, replicationFactor - NOT assignments)
            else if (map.containsKey("topicName") && map.containsKey("partitionCount") && 
                     map.containsKey("replicationFactor") && !map.containsKey("assignments")) {
                log.info("Applying Map-based RegisterTopicCommand: {}", map);
                
                // Extract config if present
                @SuppressWarnings("unchecked")
                Map<String, Object> configMap = (Map<String, Object>) map.get("config");
                TopicConfig config = null;
                if (configMap != null) {
                    Long retentionMs = configMap.containsKey("retentionMs") ? ((Number) configMap.get("retentionMs")).longValue() : 604800000L;
                    Long retentionBytes = configMap.containsKey("retentionBytes") ? ((Number) configMap.get("retentionBytes")).longValue() : -1L;
                    Integer segmentBytes = configMap.containsKey("segmentBytes") ? ((Number) configMap.get("segmentBytes")).intValue() : 1073741824;
                    String compressionType = configMap.containsKey("compressionType") ? (String) configMap.get("compressionType") : "none";
                    Integer minInsyncReplicas = configMap.containsKey("minInsyncReplicas") ? ((Number) configMap.get("minInsyncReplicas")).intValue() : 1;
                    
                    config = TopicConfig.builder()
                            .retentionMs(retentionMs)
                            .retentionBytes(retentionBytes)
                            .segmentBytes(segmentBytes)
                            .compressionType(compressionType)
                            .minInsyncReplicas(minInsyncReplicas)
                            .build();
                }
                
                RegisterTopicCommand cmd = RegisterTopicCommand.builder()
                        .topicName((String) map.get("topicName"))
                        .partitionCount(((Number) map.get("partitionCount")).intValue())
                        .replicationFactor(((Number) map.get("replicationFactor")).intValue())
                        .config(config)
                        .timestamp(map.containsKey("timestamp") ? ((Number) map.get("timestamp")).longValue() : System.currentTimeMillis())
                        .build();
                applyRegisterTopic(cmd);
                log.info("Successfully applied Map-based RegisterTopicCommand for topic {}", cmd.getTopicName());
                stateChanged = true;
            }
            // Try AssignPartitionsCommand (has topicName, assignments - NOT partitionCount/replicationFactor)
            else if (map.containsKey("topicName") && map.containsKey("assignments") && 
                     !map.containsKey("partitionCount") && !map.containsKey("replicationFactor")) {
                log.info("Applying Map-based AssignPartitionsCommand: {}", map);
                
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> assignmentsList = (List<Map<String, Object>>) map.get("assignments");
                List<PartitionAssignment> assignments = new ArrayList<>();
                
                for (Map<String, Object> assignmentMap : assignmentsList) {
                    @SuppressWarnings("unchecked")
                    List<Integer> replicaIds = ((List<Number>) assignmentMap.get("replicaIds")).stream()
                            .map(Number::intValue)
                            .collect(Collectors.toList());
                    @SuppressWarnings("unchecked")
                    List<Integer> isrIds = ((List<Number>) assignmentMap.get("isrIds")).stream()
                            .map(Number::intValue)
                            .collect(Collectors.toList());
                    
                    PartitionAssignment assignment = PartitionAssignment.builder()
                            .partitionId(((Number) assignmentMap.get("partitionId")).intValue())
                            .leaderId(((Number) assignmentMap.get("leaderId")).intValue())
                            .replicaIds(replicaIds)
                            .isrIds(isrIds)
                            .build();
                    assignments.add(assignment);
                }
                
                AssignPartitionsCommand cmd = AssignPartitionsCommand.builder()
                        .topicName((String) map.get("topicName"))
                        .assignments(assignments)
                        .timestamp(map.containsKey("timestamp") ? ((Number) map.get("timestamp")).longValue() : System.currentTimeMillis())
                        .build();
                applyAssignPartitions(cmd);
                log.info("Successfully applied Map-based AssignPartitionsCommand for topic {}", cmd.getTopicName());
                stateChanged = true;
            }
            // Try DeleteTopicCommand (has ONLY topicName and timestamp, nothing else)
            else if (map.containsKey("topicName") && map.size() <= 2 && 
                     !map.containsKey("partitionCount") && 
                     !map.containsKey("replicationFactor") && 
                     !map.containsKey("assignments") &&
                     !map.containsKey("partitionId")) {
                log.info("Applying Map-based DeleteTopicCommand: {}", map);
                
                DeleteTopicCommand cmd = DeleteTopicCommand.builder()
                        .topicName((String) map.get("topicName"))
                        .timestamp(map.containsKey("timestamp") ? ((Number) map.get("timestamp")).longValue() : System.currentTimeMillis())
                        .build();
                applyDeleteTopic(cmd);
                log.info("Successfully applied Map-based DeleteTopicCommand for topic {}", cmd.getTopicName());
                stateChanged = true;
            }
            // Try UpdateBrokerStatusCommand (has brokerId, status, lastHeartbeatTime)
            else if (map.containsKey("brokerId") && map.containsKey("status") && map.containsKey("lastHeartbeatTime")) {
                log.info("Applying Map-based UpdateBrokerStatusCommand: {}", map);
                
                // Parse BrokerStatus from string or enum
                BrokerStatus status;
                Object statusObj = map.get("status");
                if (statusObj instanceof String) {
                    status = BrokerStatus.valueOf((String) statusObj);
                } else {
                    // Assuming it's already a BrokerStatus enum
                    status = (BrokerStatus) statusObj;
                }
                
                UpdateBrokerStatusCommand cmd = UpdateBrokerStatusCommand.builder()
                        .brokerId(((Number) map.get("brokerId")).intValue())
                        .status(status)
                        .lastHeartbeatTime(((Number) map.get("lastHeartbeatTime")).longValue())
                        .timestamp(map.containsKey("timestamp") ? ((Number) map.get("timestamp")).longValue() : System.currentTimeMillis())
                        .build();
                stateChanged = applyUpdateBrokerStatus(cmd);
                if (stateChanged) {
                    log.info("Successfully applied Map-based UpdateBrokerStatusCommand for broker {}", cmd.getBrokerId());
                }
            }
            // Try UnregisterBrokerCommand (has brokerId but NOT host/port/status/lastHeartbeatTime)
            else if (map.containsKey("brokerId") && !map.containsKey("host") && !map.containsKey("port") && !map.containsKey("status")) {
                log.info("Applying Map-based UnregisterBrokerCommand: {}", map);
                
                UnregisterBrokerCommand cmd = UnregisterBrokerCommand.builder()
                        .brokerId(((Number) map.get("brokerId")).intValue())
                        .timestamp(map.containsKey("timestamp") ? ((Number) map.get("timestamp")).longValue() : System.currentTimeMillis())
                        .build();
                applyUnregisterBroker(cmd);
                log.info("Successfully applied Map-based UnregisterBrokerCommand for broker {}", cmd.getBrokerId());
                stateChanged = true;
            }
            // Try UpdatePartitionLeaderCommand (has topicName, partitionId, newLeaderId, leaderEpoch - NOT newISR)
            else if (map.containsKey("topicName") && map.containsKey("partitionId") && 
                     map.containsKey("newLeaderId") && map.containsKey("leaderEpoch") && !map.containsKey("newISR")) {
                log.info("Applying Map-based UpdatePartitionLeaderCommand: {}", map);
                
                UpdatePartitionLeaderCommand cmd = UpdatePartitionLeaderCommand.builder()
                        .topicName((String) map.get("topicName"))
                        .partitionId(((Number) map.get("partitionId")).intValue())
                        .newLeaderId(((Number) map.get("newLeaderId")).intValue())
                        .leaderEpoch(((Number) map.get("leaderEpoch")).longValue())
                        .timestamp(map.containsKey("timestamp") ? ((Number) map.get("timestamp")).longValue() : System.currentTimeMillis())
                        .build();
                stateChanged = applyUpdatePartitionLeader(cmd);
                if (stateChanged) {
                    log.info("Successfully applied Map-based UpdatePartitionLeaderCommand for {}-{}", 
                            cmd.getTopicName(), cmd.getPartitionId());
                }
            }
            // Try UpdateISRCommand (has topicName, partitionId, newISR - NOT newLeaderId/leaderEpoch)
            else if (map.containsKey("topicName") && map.containsKey("partitionId") && 
                     map.containsKey("newISR") && !map.containsKey("newLeaderId")) {
                log.info("Applying Map-based UpdateISRCommand: {}", map);
                
                @SuppressWarnings("unchecked")
                List<Integer> newISR = ((List<Number>) map.get("newISR")).stream()
                        .map(Number::intValue)
                        .collect(Collectors.toList());
                
                UpdateISRCommand cmd = UpdateISRCommand.builder()
                        .topicName((String) map.get("topicName"))
                        .partitionId(((Number) map.get("partitionId")).intValue())
                        .newISR(newISR)
                        .timestamp(map.containsKey("timestamp") ? ((Number) map.get("timestamp")).longValue() : System.currentTimeMillis())
                        .build();
                stateChanged = applyUpdateISR(cmd);
                if (stateChanged) {
                    log.info("Successfully applied Map-based UpdateISRCommand for {}-{}", 
                            cmd.getTopicName(), cmd.getPartitionId());
                }
            }
            // Try RegisterConsumerGroupCommand (has groupId, topic, appId, groupLeaderBrokerId)
            else if (map.containsKey("groupId") && map.containsKey("topic") && 
                     map.containsKey("appId") && map.containsKey("groupLeaderBrokerId")) {
                log.info("Applying Map-based RegisterConsumerGroupCommand: {}", map);
                
                RegisterConsumerGroupCommand cmd = RegisterConsumerGroupCommand.builder()
                        .groupId((String) map.get("groupId"))
                        .topic((String) map.get("topic"))
                        .appId((String) map.get("appId"))
                        .groupLeaderBrokerId(((Number) map.get("groupLeaderBrokerId")).intValue())
                        .timestamp(map.containsKey("timestamp") ? ((Number) map.get("timestamp")).longValue() : System.currentTimeMillis())
                        .build();
                applyRegisterConsumerGroup(cmd);
                log.info("Successfully applied Map-based RegisterConsumerGroupCommand for group {}", cmd.getGroupId());
                stateChanged = true;
            }
            // Try UpdateConsumerGroupLeaderCommand (has groupId, newGroupLeaderBrokerId - NOT topic/appId)
            else if (map.containsKey("groupId") && map.containsKey("newGroupLeaderBrokerId") && 
                     !map.containsKey("topic") && !map.containsKey("appId")) {
                log.info("Applying Map-based UpdateConsumerGroupLeaderCommand: {}", map);
                
                UpdateConsumerGroupLeaderCommand cmd = UpdateConsumerGroupLeaderCommand.builder()
                        .groupId((String) map.get("groupId"))
                        .newGroupLeaderBrokerId(((Number) map.get("newGroupLeaderBrokerId")).intValue())
                        .timestamp(map.containsKey("timestamp") ? ((Number) map.get("timestamp")).longValue() : System.currentTimeMillis())
                        .build();
                stateChanged = applyUpdateConsumerGroupLeader(cmd);
                if (stateChanged) {
                    log.info("Successfully applied Map-based UpdateConsumerGroupLeaderCommand for group {}", cmd.getGroupId());
                }
            }
            // Try DeleteConsumerGroupCommand (has ONLY groupId and timestamp, nothing else)
            else if (map.containsKey("groupId") && map.size() <= 2 && 
                     !map.containsKey("topic") && !map.containsKey("appId") && 
                     !map.containsKey("newGroupLeaderBrokerId")) {
                log.info("Applying Map-based DeleteConsumerGroupCommand: {}", map);
                
                DeleteConsumerGroupCommand cmd = DeleteConsumerGroupCommand.builder()
                        .groupId((String) map.get("groupId"))
                        .timestamp(map.containsKey("timestamp") ? ((Number) map.get("timestamp")).longValue() : System.currentTimeMillis())
                        .build();
                stateChanged = applyDeleteConsumerGroup(cmd);
                if (stateChanged) {
                    log.info("Successfully applied Map-based DeleteConsumerGroupCommand for group {}", cmd.getGroupId());
                }
            }
            else {
                log.error("Unknown Map command structure: {}", map);
            }
        }
        else {
            log.error("Unknown command type: {} - {}", command.getClass().getSimpleName(), command);
            log.error("Command toString: {}", command.toString());
        }
        
        // Only increment metadata version if state actually changed
        if (stateChanged) {
            long newVersion = metadataVersion.incrementAndGet();
            log.info("✅ Metadata version incremented to {} (state changed)", newVersion);
        } else {
            log.debug("⏭️ No state change, metadata version remains at {}", metadataVersion.get());
        }
    }

    /**
     * Get current metadata version
     */
    public long getMetadataVersion() {
        return metadataVersion.get();
    }

    /**
     * Apply broker registration
     */
    private void applyRegisterBroker(RegisterBrokerCommand command) {
        BrokerInfo brokerInfo = BrokerInfo.builder()
                .brokerId(command.getBrokerId())
                .host(command.getHost())
                .port(command.getPort())
                .registrationTime(command.getTimestamp())
                .status(com.distributedmq.common.model.BrokerStatus.OFFLINE)  // Start as OFFLINE - becomes ONLINE on first heartbeat
                .lastHeartbeatTime(0L)  // No heartbeat yet - will be set when first heartbeat arrives
                .build();

        brokers.put(command.getBrokerId(), brokerInfo);
        log.info("Registered broker: id={}, address={}:{}, status=OFFLINE (awaiting first heartbeat), registeredAt={}",
                command.getBrokerId(), command.getHost(), command.getPort(), command.getTimestamp());
    }

    /**
     * Apply broker status update (Phase 4)
     * Updates broker status and heartbeat timestamp
     * @return true if state changed, false if no change
     */
    private boolean applyUpdateBrokerStatus(UpdateBrokerStatusCommand command) {
        BrokerInfo broker = brokers.get(command.getBrokerId());
        if (broker == null) {
            log.warn("Cannot update status for unknown broker: id={}", command.getBrokerId());
            return false;
        }

        // Check if status is actually changing (idempotency)
        if (broker.getStatus() == command.getStatus() && 
            broker.getLastHeartbeatTime() == command.getLastHeartbeatTime()) {
            log.debug("Broker {} status unchanged ({}) and heartbeat unchanged, skipping version increment",
                    command.getBrokerId(), command.getStatus());
            return false;
        }

        BrokerStatus oldStatus = broker.getStatus();
        broker.setStatus(command.getStatus());
        broker.setLastHeartbeatTime(command.getLastHeartbeatTime());
        
        log.info("Updated broker status: id={}, status={} → {}, lastHeartbeat={}",
                command.getBrokerId(), oldStatus, command.getStatus(), command.getLastHeartbeatTime());
        return true;
    }

    /**
     * Apply broker unregistration
     */
    private void applyUnregisterBroker(UnregisterBrokerCommand command) {
        BrokerInfo removed = brokers.remove(command.getBrokerId());
        if (removed != null) {
            log.info("Unregistered broker: id={}", command.getBrokerId());
        } else {
            log.warn("Attempted to unregister unknown broker: id={}", command.getBrokerId());
        }
    }

    /**
     * Apply topic registration
     */
    private void applyRegisterTopic(RegisterTopicCommand command) {
        TopicInfo topicInfo = TopicInfo.builder()
                .topicName(command.getTopicName())
                .partitionCount(command.getPartitionCount())
                .replicationFactor(command.getReplicationFactor())
                .config(command.getConfig())
                .createdAt(command.getTimestamp())
                .build();

        topics.put(command.getTopicName(), topicInfo);
        log.info("Registered topic: name={}, partitions={}, replicationFactor={}, createdAt={}",
                command.getTopicName(), command.getPartitionCount(), 
                command.getReplicationFactor(), command.getTimestamp());
    }

    /**
     * Apply partition assignments
     */
    private void applyAssignPartitions(AssignPartitionsCommand command) {
        Map<Integer, PartitionInfo> topicPartitions = partitions.computeIfAbsent(
                command.getTopicName(), k -> new ConcurrentHashMap<>());

        for (PartitionAssignment assignment : command.getAssignments()) {
            PartitionInfo partitionInfo = PartitionInfo.builder()
                    .topicName(command.getTopicName())
                    .partitionId(assignment.getPartitionId())
                    .leaderId(assignment.getLeaderId())
                    .replicaIds(assignment.getReplicaIds())
                    .isrIds(assignment.getIsrIds())
                    .startOffset(0L)
                    .endOffset(0L)
                    .leaderEpoch(0L)
                    .build();

            topicPartitions.put(assignment.getPartitionId(), partitionInfo);
            log.debug("Assigned partition: topic={}, partition={}, leader={}, replicas={}, isr={}",
                    command.getTopicName(), assignment.getPartitionId(), 
                    assignment.getLeaderId(), assignment.getReplicaIds(), assignment.getIsrIds());
        }

        log.info("Assigned {} partitions for topic: {}", 
                command.getAssignments().size(), command.getTopicName());
    }

    /**
     * Apply partition leader update
     * @return true if state changed, false if no change
     */
    private boolean applyUpdatePartitionLeader(UpdatePartitionLeaderCommand command) {
        Map<Integer, PartitionInfo> topicPartitions = partitions.get(command.getTopicName());
        if (topicPartitions == null) {
            log.warn("Cannot update partition leader - topic not found: {}", command.getTopicName());
            return false;
        }

        PartitionInfo partition = topicPartitions.get(command.getPartitionId());
        if (partition == null) {
            log.warn("Cannot update partition leader - partition not found: {}-{}", 
                    command.getTopicName(), command.getPartitionId());
            return false;
        }

        // Check if leader is actually changing (idempotency)
        if (partition.getLeaderId() == command.getNewLeaderId() && 
            partition.getLeaderEpoch() >= command.getLeaderEpoch()) {
            log.debug("Partition leader unchanged for {}-{}: leader={}, epoch={}, skipping version increment",
                    command.getTopicName(), command.getPartitionId(), 
                    partition.getLeaderId(), partition.getLeaderEpoch());
            return false;
        }

        int oldLeader = partition.getLeaderId();
        long oldEpoch = partition.getLeaderEpoch();
        partition.setLeaderId(command.getNewLeaderId());
        partition.setLeaderEpoch(command.getLeaderEpoch());
        log.info("Updated partition leader: topic={}, partition={}, leader={} → {}, epoch={} → {}",
                command.getTopicName(), command.getPartitionId(), 
                oldLeader, command.getNewLeaderId(), oldEpoch, command.getLeaderEpoch());
        return true;
    }

    /**
     * Apply ISR update
     * @return true if state changed, false if no change
     */
    private boolean applyUpdateISR(UpdateISRCommand command) {
        Map<Integer, PartitionInfo> topicPartitions = partitions.get(command.getTopicName());
        if (topicPartitions == null) {
            log.warn("Cannot update ISR - topic not found: {}", command.getTopicName());
            return false;
        }

        PartitionInfo partition = topicPartitions.get(command.getPartitionId());
        if (partition == null) {
            log.warn("Cannot update ISR - partition not found: {}-{}", 
                    command.getTopicName(), command.getPartitionId());
            return false;
        }

        // Check if ISR is actually changing (idempotency)
        List<Integer> currentISR = partition.getIsrIds();
        if (currentISR != null && currentISR.equals(command.getNewISR())) {
            log.debug("ISR unchanged for {}-{}: {}, skipping version increment",
                    command.getTopicName(), command.getPartitionId(), currentISR);
            return false;
        }

        partition.setIsrIds(command.getNewISR());
        log.info("Updated ISR: topic={}, partition={}, {} → {}",
                command.getTopicName(), command.getPartitionId(), currentISR, command.getNewISR());
        return true;
    }

    /**
     * Apply topic deletion
     */
    private void applyDeleteTopic(DeleteTopicCommand command) {
        TopicInfo removed = topics.remove(command.getTopicName());
        if (removed != null) {
            // Also remove all partition information
            partitions.remove(command.getTopicName());
            log.info("Deleted topic: name={}", command.getTopicName());
        } else {
            log.warn("Attempted to delete unknown topic: name={}", command.getTopicName());
        }
    }

    /**
     * Get broker information by ID
     */
    public BrokerInfo getBroker(int brokerId) {
        return brokers.get(brokerId);
    }

    /**
     * Get all registered brokers
     */
    public Map<Integer, BrokerInfo> getAllBrokers() {
        return new ConcurrentHashMap<>(brokers);
    }

    /**
     * Get topic information by name
     */
    public TopicInfo getTopic(String topicName) {
        return topics.get(topicName);
    }

    /**
     * Get all topics
     */
    public Map<String, TopicInfo> getAllTopics() {
        return new ConcurrentHashMap<>(topics);
    }

    /**
     * Get partition information for a specific partition
     */
    public PartitionInfo getPartition(String topicName, int partitionId) {
        Map<Integer, PartitionInfo> topicPartitions = partitions.get(topicName);
        if (topicPartitions == null) {
            return null;
        }
        return topicPartitions.get(partitionId);
    }

    /**
     * Get all partitions for a topic
     */
    public Map<Integer, PartitionInfo> getPartitions(String topicName) {
        Map<Integer, PartitionInfo> topicPartitions = partitions.get(topicName);
        if (topicPartitions == null) {
            return new ConcurrentHashMap<>();
        }
        return new ConcurrentHashMap<>(topicPartitions);
    }

    /**
     * Get all partitions across all topics
     */
    public Map<String, Map<Integer, PartitionInfo>> getAllPartitions() {
        return new ConcurrentHashMap<>(partitions);
    }

    /**
     * Check if a topic exists
     */
    public boolean topicExists(String topicName) {
        return topics.containsKey(topicName);
    }

    /**
     * Check if a broker exists
     */
    public boolean brokerExists(int brokerId) {
        return brokers.containsKey(brokerId);
    }

    /**
     * Get count of registered brokers
     */
    public int getBrokerCount() {
        return brokers.size();
    }

    /**
     * Get count of topics
     */
    public int getTopicCount() {
        return topics.size();
    }

    /**
     * Create a snapshot of current metadata state
     */
    public byte[] createSnapshot() {
        log.debug("Creating metadata state snapshot");
        
        // TODO: Serialize all topics metadata
        // TODO: Serialize all partition metadata
        // TODO: Serialize all broker metadata
        // TODO: Serialize consumer group metadata
        // TODO: Return serialized snapshot
        
        return new byte[0]; // Placeholder
    }

    /**
     * Restore metadata state from snapshot
     */
    public void restoreFromSnapshot(byte[] snapshot) {
        log.info("Restoring metadata state from snapshot");
        
        // TODO: Deserialize snapshot
        // TODO: Restore topics metadata
        // TODO: Restore partition metadata
        // TODO: Restore broker metadata
        // TODO: Restore consumer group metadata
    }

    // ==================== CONSUMER GROUP OPERATIONS ====================

    /**
     * Apply consumer group registration
     */
    private void applyRegisterConsumerGroup(RegisterConsumerGroupCommand command) {
        String groupKey = generateConsumerGroupKey(command.getTopic(), command.getAppId());
        
        ConsumerGroupInfo groupInfo = ConsumerGroupInfo.builder()
                .groupId(command.getGroupId())
                .topic(command.getTopic())
                .appId(command.getAppId())
                .groupLeaderBrokerId(command.getGroupLeaderBrokerId())
                .createdAt(command.getTimestamp())
                .lastModifiedAt(command.getTimestamp())
                .build();
        
        consumerGroups.put(groupKey, groupInfo);
        log.info("Registered consumer group: groupId={}, topic={}, appId={}, leader=broker-{}", 
                command.getGroupId(), command.getTopic(), command.getAppId(), command.getGroupLeaderBrokerId());
    }

    /**
     * Apply consumer group leader update
     * @return true if state changed, false if no change
     */
    private boolean applyUpdateConsumerGroupLeader(UpdateConsumerGroupLeaderCommand command) {
        // Find the group by groupId
        ConsumerGroupInfo groupInfo = consumerGroups.values().stream()
                .filter(g -> g.getGroupId().equals(command.getGroupId()))
                .findFirst()
                .orElse(null);
        
        if (groupInfo == null) {
            log.warn("Cannot update leader for non-existent consumer group: {}", command.getGroupId());
            return false;
        }
        
        Integer oldLeader = groupInfo.getGroupLeaderBrokerId();
        if (oldLeader.equals(command.getNewGroupLeaderBrokerId())) {
            log.debug("Consumer group {} leader already set to broker-{}, no change", 
                     command.getGroupId(), command.getNewGroupLeaderBrokerId());
            return false;
        }
        
        groupInfo.setGroupLeaderBrokerId(command.getNewGroupLeaderBrokerId());
        groupInfo.setLastModifiedAt(command.getTimestamp());
        
        log.info("Updated consumer group {} leader: broker-{} -> broker-{}", 
                command.getGroupId(), oldLeader, command.getNewGroupLeaderBrokerId());
        return true;
    }

    /**
     * Apply consumer group deletion
     * @return true if state changed, false if group didn't exist
     */
    private boolean applyDeleteConsumerGroup(DeleteConsumerGroupCommand command) {
        // Find and remove the group by groupId
        String removedKey = null;
        for (Map.Entry<String, ConsumerGroupInfo> entry : consumerGroups.entrySet()) {
            if (entry.getValue().getGroupId().equals(command.getGroupId())) {
                removedKey = entry.getKey();
                break;
            }
        }
        
        if (removedKey != null) {
            ConsumerGroupInfo removed = consumerGroups.remove(removedKey);
            log.info("Deleted consumer group: groupId={}, topic={}, appId={}", 
                    removed.getGroupId(), removed.getTopic(), removed.getAppId());
            return true;
        } else {
            log.warn("Cannot delete non-existent consumer group: {}", command.getGroupId());
            return false;
        }
    }

    /**
     * Get consumer group by topic and app ID
     */
    public ConsumerGroupInfo getConsumerGroup(String topic, String appId) {
        String groupKey = generateConsumerGroupKey(topic, appId);
        return consumerGroups.get(groupKey);
    }

    /**
     * Get consumer group by group ID
     */
    public ConsumerGroupInfo getConsumerGroupById(String groupId) {
        return consumerGroups.values().stream()
                .filter(g -> g.getGroupId().equals(groupId))
                .findFirst()
                .orElse(null);
    }

    /**
     * Get all consumer groups
     */
    public Map<String, ConsumerGroupInfo> getAllConsumerGroups() {
        return new ConcurrentHashMap<>(consumerGroups);
    }

    /**
     * Get all consumer groups coordinated by a specific broker
     */
    public List<ConsumerGroupInfo> getConsumerGroupsByLeader(Integer brokerId) {
        return consumerGroups.values().stream()
                .filter(g -> g.getGroupLeaderBrokerId().equals(brokerId))
                .collect(Collectors.toList());
    }

    /**
     * Check if a consumer group exists
     */
    public boolean consumerGroupExists(String topic, String appId) {
        String groupKey = generateConsumerGroupKey(topic, appId);
        return consumerGroups.containsKey(groupKey);
    }

    /**
     * Get count of consumer groups
     */
    public int getConsumerGroupCount() {
        return consumerGroups.size();
    }

    /**
     * Generate consumer group key from topic and app ID
     */
    private String generateConsumerGroupKey(String topic, String appId) {
        return topic + ":" + appId;
    }
}
