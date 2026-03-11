package com.distributedmq.client.cli.commands;

import com.distributedmq.client.cli.utils.ArgumentParser;
import com.distributedmq.client.consumer.ConsumerConfig;
import com.distributedmq.client.consumer.DMQConsumer;
import com.distributedmq.common.model.Message;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;

/**
 * Command to consume messages using consumer groups
 * Usage: mycli consume-group --topic <topic> --app-id <app-id> [options]
 */
public class ConsumeGroupCommand implements Command {
    
    private static final DateTimeFormatter TIMESTAMP_FORMATTER = 
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());
    
    @Override
    public void execute(String[] args) throws Exception {
        ArgumentParser parser = new ArgumentParser(args);
        
        if (parser.hasFlag("help") || parser.hasFlag("h")) {
            printHelp();
            return;
        }
        
        // Parse required arguments
        String topic = parser.getOption("topic");
        if (topic == null) {
            throw new IllegalArgumentException("Missing required argument: --topic");
        }
        
        String appId = parser.getOption("app-id");
        if (appId == null) {
            throw new IllegalArgumentException("Missing required argument: --app-id");
        }
        
        // Optional arguments
        String metadataUrl = parser.getOption("metadata-url");
        String clientId = parser.getOption("client-id");
        String maxMessagesStr = parser.getOption("max-messages");
        String pollIntervalStr = parser.getOption("poll-interval");
        boolean continuous = parser.hasFlag("continuous");
        boolean showKey = !parser.hasFlag("no-key");
        boolean showTimestamp = !parser.hasFlag("no-timestamp");
        
        int maxMessages = 100;
        if (maxMessagesStr != null) {
            try {
                maxMessages = Integer.parseInt(maxMessagesStr);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Max messages must be a number");
            }
        }
        
        long pollInterval = 1000L; // 1 second default
        if (pollIntervalStr != null) {
            try {
                pollInterval = Long.parseLong(pollIntervalStr);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Poll interval must be a number");
            }
        }
        
        // Build consumer config
        ConsumerConfig.ConsumerConfigBuilder configBuilder = ConsumerConfig.builder()
                .groupId(appId)
                .enableAutoCommit(true)
                .autoCommitIntervalMs(5000L)
                .maxPollRecords(maxMessages)
                .heartbeatIntervalMs(3000L)
                .sessionTimeoutMs(10000L);
        
        if (metadataUrl != null) {
            configBuilder.metadataServiceUrl(metadataUrl);
        }
        
        if (clientId != null) {
            configBuilder.clientId(clientId);
        }
        
        ConsumerConfig config = configBuilder.build();
        
        System.out.println("========================================");
        System.out.println("Consumer Group Consumption");
        System.out.println("========================================");
        System.out.println("Topic:         " + topic);
        System.out.println("App ID:        " + appId);
        System.out.println("Max Messages:  " + maxMessages);
        System.out.println("Mode:          " + (continuous ? "Continuous" : "Single Poll"));
        System.out.println("========================================");
        System.out.println();
        
        // Create consumer
        DMQConsumer consumer = new DMQConsumer(config);
        
        try {
            // Subscribe to topic (this triggers group discovery, join, partition assignment)
            System.out.println("Subscribing to topic and joining consumer group...");
            consumer.subscribe(Collections.singleton(topic));
            System.out.println("[OK] Successfully joined consumer group");
            System.out.println();
            
            int totalConsumed = 0;
            int pollCount = 0;
            
            do {
                pollCount++;
                System.out.println("Poll #" + pollCount + "...");
                
                // Poll for messages
                List<Message> messages = consumer.poll(pollInterval);
                
                if (messages.isEmpty()) {
                    System.out.println("  No new messages");
                    if (continuous) {
                        System.out.println("  Waiting...");
                        Thread.sleep(pollInterval);
                        continue;
                    } else {
                        break;
                    }
                }
                
                // Print messages
                System.out.println("  Received " + messages.size() + " messages:");
                for (Message message : messages) {
                    printMessage(message, showKey, showTimestamp);
                    totalConsumed++;
                }
                
                System.out.println();
                
                if (!continuous) {
                    break;
                }
                
            } while (continuous);
            
            System.out.println("========================================");
            System.out.println("[OK] Consumed " + totalConsumed + " messages across " + pollCount + " poll(s)");
            System.out.println("========================================");
            
        } finally {
            // Graceful shutdown - leave group
            System.out.println();
            System.out.println("Leaving consumer group...");
            consumer.close();
            System.out.println("[OK] Consumer closed");
        }
    }
    
    private void printMessage(Message message, boolean showKey, boolean showTimestamp) {
        StringBuilder sb = new StringBuilder("    ");
        
        // Partition
        sb.append("P").append(message.getPartition());
        
        // Offset
        sb.append(" | Offset: ").append(message.getOffset());
        
        // Timestamp
        if (showTimestamp) {
            String timestamp = TIMESTAMP_FORMATTER.format(Instant.ofEpochMilli(message.getTimestamp()));
            sb.append(" | ").append(timestamp);
        }
        
        // Key
        if (showKey && message.getKey() != null) {
            sb.append(" | Key: ").append(message.getKey());
        }
        
        // Value
        sb.append(" | Value: ");
        if (message.getValue() != null) {
            sb.append(new String(message.getValue(), StandardCharsets.UTF_8));
        } else {
            sb.append("null");
        }
        
        System.out.println(sb.toString());
    }
    
    @Override
    public void printHelp() {
        System.out.println("Consume messages using consumer groups");
        System.out.println();
        System.out.println("Usage: mycli consume-group --topic <topic> --app-id <app-id> [options]");
        System.out.println();
        System.out.println("This command uses consumer groups for automatic partition assignment");
        System.out.println("and rebalancing. Multiple consumers with the same app-id will form");
        System.out.println("a consumer group and share partition assignments.");
        System.out.println();
        System.out.println("Required Arguments:");
        System.out.println("  --topic <topic>           Topic name");
        System.out.println("  --app-id <app-id>         Application ID (consumer group identifier)");
        System.out.println();
        System.out.println("Optional Arguments:");
        System.out.println("  --max-messages <n>        Maximum messages per poll (default: 100)");
        System.out.println("  --poll-interval <ms>      Poll interval in milliseconds (default: 1000)");
        System.out.println("  --continuous              Keep polling for new messages (Ctrl+C to stop)");
        System.out.println("  --client-id <id>          Client ID (default: dmq-consumer)");
        System.out.println("  --no-key                  Don't show message keys");
        System.out.println("  --no-timestamp            Don't show timestamps");
        System.out.println("  --metadata-url <url>      Metadata service URL (default: from config)");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  # Join consumer group and consume messages");
        System.out.println("  mycli consume-group --topic orders --app-id order-processor");
        System.out.println();
        System.out.println("  # Continuous consumption with consumer group");
        System.out.println("  mycli consume-group --topic logs --app-id log-processor --continuous");
        System.out.println();
        System.out.println("  # Multiple consumers in same group (run in separate terminals)");
        System.out.println("  mycli consume-group --topic events --app-id event-processor --continuous");
        System.out.println("  mycli consume-group --topic events --app-id event-processor --continuous");
    }
}
