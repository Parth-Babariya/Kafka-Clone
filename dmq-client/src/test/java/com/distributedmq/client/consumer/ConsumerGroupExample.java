package com.distributedmq.client.consumer;

import com.distributedmq.client.consumer.ConsumerConfig;
import com.distributedmq.common.model.Message;

import java.util.Collections;
import java.util.List;

/**
 * Example demonstrating consumer group usage with subscribe pattern
 * 
 * This shows how to use DMQConsumer with consumer groups for automatic:
 * - Partition assignment
 * - Rebalancing when consumers join/leave
 * - Offset management via heartbeat
 */
public class ConsumerGroupExample {

    public static void main(String[] args) throws Exception {
        // Create consumer configuration
        ConsumerConfig config = ConsumerConfig.builder()
                .metadataServiceUrl("http://localhost:9091")  // Metadata service
                .groupId("my-consumer-group")                 // Consumer group ID
                .heartbeatIntervalMs(3000L)                   // Heartbeat every 3 seconds
                .enableAutoCommit(true)                       // Auto-commit via heartbeat
                .autoCommitIntervalMs(5000L)                  // Auto-commit interval
                .build();

        // Create consumer instance
        DMQConsumer consumer = new DMQConsumer(config);

        // Subscribe to topic (joins consumer group)
        String topic = "test-topic";
        consumer.subscribe(Collections.singleton(topic));
        
        System.out.println("Consumer started. Polling for messages...");
        System.out.println("Consumer group: " + config.getGroupId());
        System.out.println("Topic: " + topic);
        System.out.println("Press Ctrl+C to exit");
        
        // Add shutdown hook for graceful shutdown
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                System.out.println("\nShutting down consumer...");
                consumer.close();
                System.out.println("Consumer closed successfully");
            } catch (Exception e) {
                System.err.println("Error during shutdown: " + e.getMessage());
            }
        }));

        // Poll for messages continuously
        try {
            while (true) {
                List<Message> messages = consumer.poll(1000);
                
                if (!messages.isEmpty()) {
                    System.out.println("\n=== Received " + messages.size() + " messages ===");
                    for (Message message : messages) {
                        System.out.println("Partition: " + message.getPartition() + 
                                         ", Offset: " + message.getOffset() + 
                                         ", Value: " + message.getValue());
                    }
                    
                    // Offsets are automatically committed via heartbeat
                    // Or explicitly commit:
                    // consumer.commitSync();
                }
                
                // Small sleep to avoid tight loop
                Thread.sleep(100);
            }
        } catch (InterruptedException e) {
            System.out.println("\nConsumer interrupted");
        } catch (Exception e) {
            System.err.println("Error while polling: " + e.getMessage());
            e.printStackTrace();
        } finally {
            consumer.close();
        }
    }
}
