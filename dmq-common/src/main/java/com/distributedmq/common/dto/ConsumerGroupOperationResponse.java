package com.distributedmq.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashSet;
import java.util.Set;

/**
 * Response to consumer join/heartbeat requests
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConsumerGroupOperationResponse {
    
    /**
     * Success flag
     */
    private boolean success;
    
    /**
     * Error code if failed
     */
    private String errorCode;
    
    /**
     * Error message if failed
     */
    private String errorMessage;
    
    /**
     * Assigned partitions for this consumer
     * Only populated after rebalance completes
     */
    @Builder.Default
    private Set<Integer> assignedPartitions = new HashSet<>();
    
    /**
     * Current rebalance state
     */
    private RebalanceState rebalanceState;
    
    /**
     * Generation ID (increments on each rebalance)
     */
    private Integer generation;
    
    /**
     * Create success response
     */
    public static ConsumerGroupOperationResponse success(RebalanceState state) {
        return ConsumerGroupOperationResponse.builder()
                .success(true)
                .rebalanceState(state)
                .build();
    }
    
    /**
     * Create success response with assignment
     */
    public static ConsumerGroupOperationResponse success(Set<Integer> partitions, Integer generation) {
        return ConsumerGroupOperationResponse.builder()
                .success(true)
                .rebalanceState(RebalanceState.STABLE)
                .assignedPartitions(partitions)
                .generation(generation)
                .build();
    }
    
    /**
     * Create error response
     */
    public static ConsumerGroupOperationResponse error(String errorCode, String errorMessage) {
        return ConsumerGroupOperationResponse.builder()
                .success(false)
                .errorCode(errorCode)
                .errorMessage(errorMessage)
                .build();
    }
}
