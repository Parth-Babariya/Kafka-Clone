package com.distributedmq.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory consumer group state maintained by group leader broker
 * Simplified design: broker only needs temporary coordination state
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConsumerGroupState {
    
    /**
     * Consumer group ID (topic + appId hash)
     */
    private String groupId;
    
    /**
     * Topic this group consumes from
     */
    private String topic;
    
    /**
     * Application ID (consumer group name)
     */
    private String appId;
    
    /**
     * Group members: consumerId -> ConsumerMemberInfo
     */
    @Builder.Default
    private Map<String, ConsumerMemberInfo> members = new ConcurrentHashMap<>();
    
    /**
     * Current rebalance state
     */
    @Builder.Default
    private RebalanceState rebalanceState = RebalanceState.STABLE;
    
    /**
     * Last rebalance timestamp
     */
    private long lastRebalanceTime;
    
    /**
     * Stabilization timer start time (for 5-second delay)
     */
    private Long stabilizationStartTime;
    
    /**
     * Group creation timestamp
     */
    private long createdAt;
    
    /**
     * Add or update consumer member
     */
    public void addMember(ConsumerMemberInfo member) {
        members.put(member.getConsumerId(), member);
    }
    
    /**
     * Remove consumer member
     */
    public void removeMember(String consumerId) {
        members.remove(consumerId);
    }
    
    /**
     * Get consumer member by ID
     */
    public ConsumerMemberInfo getMember(String consumerId) {
        return members.get(consumerId);
    }
    
    /**
     * Check if group is empty
     */
    public boolean isEmpty() {
        return members.isEmpty();
    }
    
    /**
     * Get member count
     */
    public int getMemberCount() {
        return members.size();
    }
    
    /**
     * Start stabilization period
     */
    public void startStabilization() {
        this.rebalanceState = RebalanceState.PREPARING_REBALANCE;
        this.stabilizationStartTime = System.currentTimeMillis();
    }
    
    /**
     * Check if stabilization period is over (5 seconds elapsed)
     */
    public boolean isStabilizationOver() {
        if (stabilizationStartTime == null) {
            return true;
        }
        long elapsed = System.currentTimeMillis() - stabilizationStartTime;
        return elapsed >= 5000; // 5 seconds
    }
    
    /**
     * Reset stabilization timer
     */
    public void resetStabilization() {
        this.stabilizationStartTime = System.currentTimeMillis();
    }
    
    /**
     * Mark rebalance complete
     */
    public void completeRebalance() {
        this.rebalanceState = RebalanceState.STABLE;
        this.stabilizationStartTime = null;
        this.lastRebalanceTime = System.currentTimeMillis();
    }
}
