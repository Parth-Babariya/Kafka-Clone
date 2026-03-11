package com.distributedmq.metadata.service;

import com.distributedmq.common.dto.ConsumerGroupResponse;

/**
 * Service for managing consumer groups
 * Handles minimal consumer group registry: (topic, app_id) -> group_leader_broker_id
 */
public interface ConsumerGroupService {
    
    /**
     * Find existing consumer group or create new one
     * This is the primary operation called by consumers
     * 
     * @param topic Topic the consumer wants to subscribe to
     * @param appId Application ID (equivalent to Kafka's group.id)
     * @return Consumer group information with group leader details
     */
    ConsumerGroupResponse findOrCreateGroup(String topic, String appId);
    
    /**
     * Update consumer group leader
     * Called when the current group leader broker fails
     * 
     * @param groupId The group ID to update
     * @param newGroupLeaderBrokerId New broker ID to act as group leader
     */
    void updateGroupLeader(String groupId, Integer newGroupLeaderBrokerId);
    
    /**
     * Delete consumer group
     * Called by group leader broker when the last member leaves
     * 
     * @param groupId The group ID to delete
     * @param requestingBrokerId Broker ID making the request (must be current group leader)
     */
    void deleteGroup(String groupId, Integer requestingBrokerId);
    
    /**
     * Get consumer group information by group ID
     * 
     * @param groupId The group ID
     * @return Consumer group information or null if not found
     */
    ConsumerGroupResponse getGroupById(String groupId);
    
    /**
     * Get all consumer groups
     * 
     * @return List of all consumer groups
     */
    java.util.List<ConsumerGroupResponse> getAllGroups();
}
