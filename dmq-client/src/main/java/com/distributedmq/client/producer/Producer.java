package com.distributedmq.client.producer;

import com.distributedmq.common.dto.ProduceRequest;
import com.distributedmq.common.dto.ProduceResponse;
import com.distributedmq.common.model.Message;

import java.util.concurrent.Future;

/**
 * Producer interface for sending messages
 */
public interface Producer {

    /**
     * Send a message asynchronously
     */
    Future<ProduceResponse> send(String topic, String key, byte[] value);

    /**
     * Send a message with specific partition
     */
    Future<ProduceResponse> send(String topic, Integer partition, String key, byte[] value);

    /**
     * Send a message synchronously
     */
    ProduceResponse sendSync(String topic, String key, byte[] value);

    /**
     * Flush any buffered messages
     */
    void flush();

    /**
     * Close the producer
     */
    void close();

    // TODO: Add batch send support
    // TODO: Add transactional support
    // TODO: Add idempotent producer support
}
