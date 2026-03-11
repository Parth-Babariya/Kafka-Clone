package com.distributedmq.storage.consumergroup;

import com.distributedmq.common.constants.ConsumerGroupErrorCodes;
import com.distributedmq.common.dto.*;
import com.distributedmq.storage.replication.MetadataStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Manages consumer groups for which this broker is the leader
 * In-memory state only - consumers maintain durable offsets locally
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConsumerGroupManager {
    
    private final MetadataStore metadataStore;
    
    /**
     * Consumer groups this broker leads: groupId -> ConsumerGroupState
     */
    private final Map<String, ConsumerGroupState> groups = new ConcurrentHashMap<>();
    
    /**
     * Generation counter per group (increments on each rebalance)
     */
    private final Map<String, Integer> generationIds = new ConcurrentHashMap<>();
    
    /**
     * Handle consumer join request
     */
    public ConsumerGroupOperationResponse handleJoin(ConsumerJoinRequest request) {
        String groupId = request.getGroupId();
        String consumerId = request.getConsumerId();
        
        log.info("Consumer {} joining group {}", consumerId, groupId);
        
        // Get or create group state
        ConsumerGroupState group = groups.computeIfAbsent(groupId, k -> {
            log.info("Creating new group state for group {}", groupId);
            return ConsumerGroupState.builder()
                    .groupId(groupId)
                    .topic(request.getTopic())
                    .appId(request.getAppId())
                    .createdAt(System.currentTimeMillis())
                    .rebalanceState(RebalanceState.STABLE)
                    .build();
        });
        
        // Create or update consumer member
        ConsumerMemberInfo member = group.getMember(consumerId);
        if (member == null) {
            member = ConsumerMemberInfo.builder()
                    .consumerId(consumerId)
                    .joinedAt(System.currentTimeMillis())
                    .lastHeartbeatTime(System.currentTimeMillis())
                    .offsets(new HashMap<>(request.getOffsetState()))
                    .build();
            group.addMember(member);
            log.info("Added new member {} to group {}, total members: {}", 
                     consumerId, groupId, group.getMemberCount());
        } else {
            // Update existing member
            member.updateHeartbeat();
            member.getOffsets().putAll(request.getOffsetState());
            log.info("Updated existing member {} in group {}", consumerId, groupId);
        }
        
        // Trigger stabilization (5-second delay before rebalance)
        triggerStabilization(group);
        
        return ConsumerGroupOperationResponse.success(group.getRebalanceState());
    }
    
    /**
     * Handle consumer heartbeat/commit
     */
    public ConsumerGroupOperationResponse handleHeartbeat(ConsumerHeartbeatRequest request) {
        String groupId = request.getGroupId();
        String consumerId = request.getConsumerId();
        
        ConsumerGroupState group = groups.get(groupId);
        
        if (group == null) {
            log.warn("Group {} not found for heartbeat from consumer {}", groupId, consumerId);
            return ConsumerGroupOperationResponse.error(
                    ConsumerGroupErrorCodes.REBALANCE_IN_PROGRESS,
                    "Group not found - broker may have restarted, please rejoin");
        }
        
        ConsumerMemberInfo member = group.getMember(consumerId);
        
        if (member == null) {
            log.warn("Consumer {} not found in group {}", consumerId, groupId);
            return ConsumerGroupOperationResponse.error(
                    ConsumerGroupErrorCodes.UNKNOWN_MEMBER_ID,
                    "Consumer not in group, please rejoin");
        }
        
        // Update heartbeat and offsets
        member.updateHeartbeat();
        member.getOffsets().putAll(request.getOffsets());
        
        log.debug("Heartbeat from consumer {} in group {}, offsets: {}", 
                  consumerId, groupId, request.getOffsets());
        
        // If rebalance complete, return assignment
        if (group.getRebalanceState() == RebalanceState.STABLE) {
            return ConsumerGroupOperationResponse.success(
                    member.getAssignedPartitions(),
                    generationIds.getOrDefault(groupId, 0));
        } else {
            return ConsumerGroupOperationResponse.success(group.getRebalanceState());
        }
    }
    
    /**
     * Check for dead consumers (no heartbeat for 15 seconds)
     * Called periodically by HeartbeatMonitor
     */
    public void checkDeadConsumers() {
        long timeoutMs = 15000; // 15 seconds
        
        for (ConsumerGroupState group : groups.values()) {
            List<String> deadConsumers = new ArrayList<>();
            
            for (ConsumerMemberInfo member : group.getMembers().values()) {
                if (!member.isAlive(timeoutMs)) {
                    deadConsumers.add(member.getConsumerId());
                }
            }
            
            if (!deadConsumers.isEmpty()) {
                log.warn("Found {} dead consumers in group {}: {}", 
                         deadConsumers.size(), group.getGroupId(), deadConsumers);
                
                // Remove dead consumers
                for (String deadId : deadConsumers) {
                    group.removeMember(deadId);
                    log.info("Removed dead consumer {} from group {}", deadId, group.getGroupId());
                }
                
                // Trigger rebalance
                triggerStabilization(group);
            }
        }
    }
    
    /**
     * Check for groups ready to rebalance (stabilization period over)
     * Called periodically by RebalanceScheduler
     */
    public void checkPendingRebalances() {
        for (ConsumerGroupState group : groups.values()) {
            if (group.getRebalanceState() == RebalanceState.PREPARING_REBALANCE 
                    && group.isStabilizationOver()) {
                
                log.info("Stabilization complete for group {}, starting rebalance", group.getGroupId());
                executeRebalance(group);
            }
        }
    }
    
    /**
     * Delete groups with no members (cleanup job runs every 30 seconds)
     * Returns list of groupIds that were deleted
     */
    public List<String> cleanupEmptyGroups() {
        List<String> deletedGroups = new ArrayList<>();
        
        for (Map.Entry<String, ConsumerGroupState> entry : groups.entrySet()) {
            String groupId = entry.getKey();
            ConsumerGroupState group = entry.getValue();
            
            if (group.isEmpty()) {
                groups.remove(groupId);
                generationIds.remove(groupId);
                deletedGroups.add(groupId);
                log.info("Cleaned up empty group: {}", groupId);
            }
        }
        
        return deletedGroups;
    }
    
    /**
     * Trigger stabilization period (5-second delay before rebalance)
     */
    private void triggerStabilization(ConsumerGroupState group) {
        if (group.getRebalanceState() == RebalanceState.STABLE) {
            // First change, start stabilization
            group.startStabilization();
            log.info("Started stabilization for group {}, will rebalance in 5 seconds", 
                     group.getGroupId());
        } else if (group.getRebalanceState() == RebalanceState.PREPARING_REBALANCE) {
            // Reset stabilization timer (unlimited resets per simplified design)
            group.resetStabilization();
            log.info("Reset stabilization timer for group {}", group.getGroupId());
        }
    }
    
    /**
     * Execute rebalance: assign partitions to consumers using round-robin
     */
    private void executeRebalance(ConsumerGroupState group) {
        log.info("Executing rebalance for group {}, members: {}", 
                 group.getGroupId(), group.getMemberCount());
        
        group.setRebalanceState(RebalanceState.IN_REBALANCE);
        
        try {
            // Get topic partition count from metadata
            Integer partitionCount = metadataStore.getPartitionCount(group.getTopic());
            if (partitionCount == null || partitionCount == 0) {
                log.error("Cannot rebalance group {} - topic {} has no partitions", 
                          group.getGroupId(), group.getTopic());
                group.completeRebalance();
                return;
            }
            
            List<ConsumerMemberInfo> members = new ArrayList<>(group.getMembers().values());
            if (members.isEmpty()) {
                log.warn("No members in group {} to rebalance", group.getGroupId());
                group.completeRebalance();
                return;
            }
            
            // Simple round-robin assignment
            Map<String, Set<Integer>> assignment = assignPartitionsRoundRobin(partitionCount, members);
            
            // Update member assignments
            for (Map.Entry<String, Set<Integer>> entry : assignment.entrySet()) {
                ConsumerMemberInfo member = group.getMember(entry.getKey());
                if (member != null) {
                    member.setAssignedPartitions(entry.getValue());
                    log.info("Assigned partitions {} to consumer {} in group {}", 
                             entry.getValue(), entry.getKey(), group.getGroupId());
                }
            }
            
            // Increment generation
            int newGeneration = generationIds.compute(group.getGroupId(), 
                    (k, v) -> v == null ? 1 : v + 1);
            
            log.info("Rebalance complete for group {}, generation {}, assignments: {}", 
                     group.getGroupId(), newGeneration, assignment);
            
            group.completeRebalance();
            
        } catch (Exception e) {
            log.error("Rebalance failed for group {}", group.getGroupId(), e);
            group.completeRebalance();
        }
    }
    
    /**
     * Round-robin partition assignment
     * Simple strategy: distribute partitions evenly across consumers
     */
    private Map<String, Set<Integer>> assignPartitionsRoundRobin(int partitionCount, 
                                                                  List<ConsumerMemberInfo> members) {
        Map<String, Set<Integer>> assignment = new HashMap<>();
        
        // Initialize empty sets for each consumer
        for (ConsumerMemberInfo member : members) {
            assignment.put(member.getConsumerId(), new HashSet<>());
        }
        
        // Round-robin assignment
        for (int partition = 0; partition < partitionCount; partition++) {
            int memberIndex = partition % members.size();
            String consumerId = members.get(memberIndex).getConsumerId();
            assignment.get(consumerId).add(partition);
        }
        
        return assignment;
    }
    
    /**
     * Check if this broker is the leader for the given group
     * (Currently always true if group exists, since we only manage groups we lead)
     */
    public boolean isGroupLeader(String groupId) {
        return groups.containsKey(groupId);
    }
    
    /**
     * Get group state (for debugging/monitoring)
     */
    public ConsumerGroupState getGroupState(String groupId) {
        return groups.get(groupId);
    }
    
    /**
     * Get all managed group IDs
     */
    public Set<String> getManagedGroupIds() {
        return new HashSet<>(groups.keySet());
    }
}
