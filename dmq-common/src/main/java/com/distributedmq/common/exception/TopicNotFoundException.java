package com.distributedmq.common.exception;

/**
 * Exception thrown when a topic is not found
 */
public class TopicNotFoundException extends DMQException {
    
    public TopicNotFoundException(String topicName) {
        super("Topic not found: " + topicName);
    }
}
