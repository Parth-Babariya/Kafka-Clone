package com.distributedmq.common.dto;

/**
 * Rebalance state enum for consumer groups
 */
public enum RebalanceState {
    /**
     * Group is stable, consumers are actively consuming
     */
    STABLE,
    
    /**
     * New consumer joined or consumer left, waiting for stabilization (5 seconds)
     */
    PREPARING_REBALANCE,
    
    /**
     * Actively calculating and assigning partitions
     */
    IN_REBALANCE,
    
    /**
     * Rebalance completed, waiting for consumers to acknowledge
     */
    AWAITING_SYNC
}
