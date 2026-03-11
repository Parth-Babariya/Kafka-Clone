package com.distributedmq.client.consumer;

import com.distributedmq.common.model.Message;

import java.util.Collection;
import java.util.List;

/**
 * Consumer interface for receiving messages
 */
public interface Consumer {

    /**
     * Subscribe to topics
     */
    void subscribe(Collection<String> topics);

    /**
     * Subscribe to specific partitions
     */
    void assign(Collection<TopicPartition> partitions);

    /**
     * Poll for new messages
     */
    List<Message> poll(long timeoutMs);

    /**
     * Commit offsets synchronously
     */
    void commitSync();

    /**
     * Commit offsets asynchronously
     */
    void commitAsync();

    /**
     * Seek to a specific offset
     */
    void seek(TopicPartition partition, long offset);

    /**
     * Seek to beginning
     */
    void seekToBeginning(Collection<TopicPartition> partitions);

    /**
     * Seek to end
     */
    void seekToEnd(Collection<TopicPartition> partitions);

    /**
     * Close the consumer
     */
    void close();

    // TODO: Add pause/resume support
    // TODO: Add consumer group rebalancing
}
