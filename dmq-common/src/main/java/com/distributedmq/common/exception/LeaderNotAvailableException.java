package com.distributedmq.common.exception;

/**
 * Exception thrown when no leader is available for a partition
 */
public class LeaderNotAvailableException extends DMQException {
    
    public LeaderNotAvailableException(String topic, int partition) {
        super("Leader not available for partition: " + topic + "-" + partition);
    }
}
