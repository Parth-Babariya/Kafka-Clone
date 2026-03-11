package com.distributedmq.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Consumer member information for group coordination
 * Maintained in-memory by group leader broker
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConsumerMemberInfo {
    
    /**
     * Unique consumer identifier
     */
    private String consumerId;
    
    /**
     * Partitions currently assigned to this consumer
     */
    @Builder.Default
    private Set<Integer> assignedPartitions = new HashSet<>();
    
    /**
     * Last heartbeat timestamp (milliseconds)
     */
    private long lastHeartbeatTime;
    
    /**
     * Current offsets for assigned partitions
     * partition -> offset
     */
    @Builder.Default
    private Map<Integer, Long> offsets = new HashMap<>();
    
    /**
     * Consumer join timestamp
     */
    private long joinedAt;
    
    /**
     * Update last heartbeat time to current time
     */
    public void updateHeartbeat() {
        this.lastHeartbeatTime = System.currentTimeMillis();
    }
    
    /**
     * Check if consumer is alive based on timeout
     * @param timeoutMs Timeout in milliseconds (e.g., 15000 for 15 seconds)
     * @return true if consumer is alive, false if dead
     */
    public boolean isAlive(long timeoutMs) {
        long now = System.currentTimeMillis();
        return (now - lastHeartbeatTime) < timeoutMs;
    }
    
    /**
     * Update offset for a partition
     */
    public void updateOffset(Integer partition, Long offset) {
        this.offsets.put(partition, offset);
    }
}
