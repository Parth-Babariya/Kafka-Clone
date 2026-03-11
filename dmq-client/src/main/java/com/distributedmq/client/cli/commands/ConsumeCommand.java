package com.distributedmq.client.cli.commands;

import com.distributedmq.client.cli.utils.ArgumentParser;
import com.distributedmq.client.consumer.SimpleConsumer;
import com.distributedmq.common.model.Message;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;

/**
 * Command to consume messages from a topic
 * Usage: mycli consume --topic <topic> --partition <n> [options]
 */
public class ConsumeCommand implements Command {
    
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
        
        String partitionStr = parser.getOption("partition");
        if (partitionStr == null) {
            throw new IllegalArgumentException("Missing required argument: --partition");
        }
        
        int partition;
        try {
            partition = Integer.parseInt(partitionStr);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Partition must be a number");
        }
        
        // Optional arguments
        String offsetStr = parser.getOption("offset");
        String maxMessagesStr = parser.getOption("max-messages");
        String metadataUrl = parser.getOption("metadata-url");
        boolean fromBeginning = parser.hasFlag("from-beginning");
        boolean continuous = parser.hasFlag("continuous");
        boolean showKey = !parser.hasFlag("no-key");
        boolean showTimestamp = !parser.hasFlag("no-timestamp");
        String format = parser.getOption("format"); // text, json, hex, base64
        
        long offset = 0;
        if (offsetStr != null) {
            try {
                offset = Long.parseLong(offsetStr);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Offset must be a number");
            }
        } else if (fromBeginning) {
            offset = 0;
        }
        
        int maxMessages = 10; // Default
        if (maxMessagesStr != null) {
            try {
                maxMessages = Integer.parseInt(maxMessagesStr);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Max messages must be a number");
            }
        }
        
        if (format == null) {
            format = "text";
        }
        
        // Create consumer and fetch messages
        System.out.println("Consuming from topic '" + topic + "', partition " + partition + "...");
        if (fromBeginning) {
            System.out.println("  Starting from: beginning (offset 0)");
        } else if (offsetStr != null) {
            System.out.println("  Starting from: offset " + offset);
        } else {
            System.out.println("  Starting from: current position");
        }
        System.out.println("  Max messages: " + (continuous ? "continuous" : maxMessages));
        System.out.println();
        
        SimpleConsumer consumer = new SimpleConsumer(metadataUrl);
        
        try {
            int totalConsumed = 0;
            long currentOffset = offset;
            
            do {
                List<Message> messages = consumer.consume(topic, partition, currentOffset, maxMessages);
                
                if (messages.isEmpty()) {
                    if (continuous) {
                        System.out.println("No new messages, waiting...");
                        Thread.sleep(1000);
                        continue;
                    } else {
                        System.out.println("No messages found at offset " + currentOffset);
                        break;
                    }
                }
                
                for (Message message : messages) {
                    printMessage(message, showKey, showTimestamp, format);
                    currentOffset = message.getOffset() + 1;
                    totalConsumed++;
                }
                
                if (!continuous) {
                    break;
                }
                
            } while (continuous);
            
            System.out.println();
            System.out.println("[OK] Consumed " + totalConsumed + " messages");
            System.out.println("  Next offset: " + currentOffset);
            
        } finally {
            consumer.close();
        }
    }
    
    private void printMessage(Message message, boolean showKey, boolean showTimestamp, String format) {
        StringBuilder sb = new StringBuilder();
        
        // Timestamp
        if (showTimestamp) {
            String timestamp = TIMESTAMP_FORMATTER.format(Instant.ofEpochMilli(message.getTimestamp()));
            sb.append("[").append(timestamp).append("] ");
        }
        
        // Offset
        sb.append("Offset: ").append(message.getOffset());
        
        // Key
        if (showKey && message.getKey() != null) {
            sb.append(", Key: ").append(message.getKey());
        }
        
        // Value
        sb.append(", Value: ");
        sb.append(formatValue(message.getValue(), format));
        
        System.out.println(sb.toString());
    }
    
    private String formatValue(byte[] value, String format) {
        if (value == null) {
            return "null";
        }
        
        switch (format.toLowerCase()) {
            case "text":
                return new String(value, StandardCharsets.UTF_8);
            case "json":
                // Pretty print JSON if possible
                String text = new String(value, StandardCharsets.UTF_8);
                return text; // TODO: Add JSON formatting
            case "hex":
                StringBuilder hex = new StringBuilder();
                for (byte b : value) {
                    hex.append(String.format("%02x ", b));
                }
                return hex.toString().trim();
            case "base64":
                return Base64.getEncoder().encodeToString(value);
            default:
                return new String(value, StandardCharsets.UTF_8);
        }
    }
    
    @Override
    public void printHelp() {
        System.out.println("Consume messages from a topic");
        System.out.println();
        System.out.println("Usage: mycli consume --topic <topic> --partition <n> [options]");
        System.out.println();
        System.out.println("Required Arguments:");
        System.out.println("  --topic <topic>           Topic name");
        System.out.println("  --partition <n>           Partition number");
        System.out.println();
        System.out.println("Optional Arguments:");
        System.out.println("  --offset <n>              Start consuming from specific offset");
        System.out.println("  --from-beginning          Start from offset 0 (beginning of partition)");
        System.out.println("  --max-messages <n>        Maximum number of messages to consume (default: 10)");
        System.out.println("  --continuous              Keep consuming new messages (Ctrl+C to stop)");
        System.out.println("  --format <format>         Output format: text, json, hex, base64 (default: text)");
        System.out.println("  --no-key                  Don't show message keys");
        System.out.println("  --no-timestamp            Don't show timestamps");
        System.out.println("  --metadata-url <url>      Metadata service URL (default: from config)");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  # Consume 10 messages from partition 0");
        System.out.println("  mycli consume --topic orders --partition 0");
        System.out.println();
        System.out.println("  # Consume from beginning");
        System.out.println("  mycli consume --topic logs --partition 0 --from-beginning");
        System.out.println();
        System.out.println("  # Consume from specific offset");
        System.out.println("  mycli consume --topic events --partition 1 --offset 100");
        System.out.println();
        System.out.println("  # Continuous consumption");
        System.out.println("  mycli consume --topic metrics --partition 0 --continuous");
        System.out.println();
        System.out.println("  # Consume with hex format");
        System.out.println("  mycli consume --topic binary-data --partition 0 --format hex");
    }
}
