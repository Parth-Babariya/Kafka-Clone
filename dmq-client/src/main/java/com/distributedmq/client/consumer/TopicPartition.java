package com.distributedmq.client.consumer;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents a topic-partition pair
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TopicPartition {
    private String topic;
    private int partition;

    @Override
    public String toString() {
        return topic + "-" + partition;
    }
}
