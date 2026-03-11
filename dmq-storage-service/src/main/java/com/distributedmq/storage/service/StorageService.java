package com.distributedmq.storage.service;

import com.distributedmq.common.dto.ConsumeRequest;
import com.distributedmq.common.dto.ConsumeResponse;
import com.distributedmq.common.dto.PartitionStatus;
import com.distributedmq.common.dto.ProduceRequest;
import com.distributedmq.common.dto.ProduceResponse;

import java.util.List;

/**
 * Service interface for Storage operations
 */
public interface StorageService {

    /**
     * Append batch of messages to partition
     * Step 2: Append Messages to Partition Log
     */
    ProduceResponse appendMessages(ProduceRequest request);

    /**
     * Fetch messages from partition
     */
    ConsumeResponse fetch(ConsumeRequest request);

    /**
     * Get high water mark for partition
     */
    Long getHighWaterMark(String topic, Integer partition);

    /**
     * Flush all pending writes
     */
    void flush();

    /**
     * Check if this broker is leader for partition
     */
    boolean isLeaderForPartition(String topic, Integer partition);

    /**
     * Get log end offset for partition
     */
    Long getLogEndOffset(String topic, Integer partition);

    /**
     * Append messages for replication (used by followers)
     * Bypasses leadership check since messages come from leader
     */
    ProduceResponse replicateMessages(ProduceRequest request);

    /**
     * Collect partition status for all partitions this node manages
     * Used for status reporting and monitoring
     */
    List<PartitionStatus> collectPartitionStatus();

    // TODO: Add partition creation/deletion methods
}
