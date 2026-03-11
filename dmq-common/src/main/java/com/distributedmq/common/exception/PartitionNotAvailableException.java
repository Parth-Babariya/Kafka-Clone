package com.distributedmq.common.exception;

/**
 * Exception thrown when a partition is not available
 */
public class PartitionNotAvailableException extends DMQException {
    
    public PartitionNotAvailableException(String topic, int partition) {
        super("Partition not available: " + topic + "-" + partition);
    }
}
