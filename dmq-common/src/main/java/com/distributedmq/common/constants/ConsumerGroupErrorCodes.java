package com.distributedmq.common.constants;

/**
 * Error codes for consumer group operations
 */
public class ConsumerGroupErrorCodes {
    
    /**
     * Rebalance is in progress, consumer should retry
     * - Broker just restarted (rebuilding state)
     * - Consumer not in member list yet
     * - Rebalance currently happening
     * - Group is in transition state
     */
    public static final String REBALANCE_IN_PROGRESS = "REBALANCE_IN_PROGRESS";
    
    /**
     * This broker is not the coordinator for this group
     * Consumer should re-discover group leader from metadata service
     */
    public static final String NOT_COORDINATOR = "NOT_COORDINATOR";
    
    /**
     * Unknown consumer ID - not part of the group
     */
    public static final String UNKNOWN_MEMBER_ID = "UNKNOWN_MEMBER_ID";
    
    /**
     * Invalid request parameters
     */
    public static final String INVALID_REQUEST = "INVALID_REQUEST";
    
    /**
     * Internal server error
     */
    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";
    
    private ConsumerGroupErrorCodes() {
        // Utility class, no instantiation
    }
}
