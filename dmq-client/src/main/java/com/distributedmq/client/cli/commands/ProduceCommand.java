package com.distributedmq.client.cli.commands;

import com.distributedmq.client.cli.utils.ArgumentParser;
import com.distributedmq.client.producer.SimpleProducer;
import com.distributedmq.common.dto.ProduceResponse;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Command to produce messages to a topic
 * Supports single message or batch mode with file input
 * Usage: mycli produce --topic <topic> --value <value>
 *        mycli produce --topic <topic> --batch-file <file>
 */
public class ProduceCommand implements Command {
    
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
        
        // Check for batch mode
        String batchFile = parser.getOption("batch-file");
        String value = parser.getOption("value");
        
        if (batchFile == null && value == null) {
            throw new IllegalArgumentException("Missing required argument: --value or --batch-file");
        }
        
        if (batchFile != null && value != null) {
            throw new IllegalArgumentException("Cannot use both --value and --batch-file");
        }
        
        // Optional arguments
        String key = parser.getOption("key");
        String partitionStr = parser.getOption("partition");
        String metadataUrl = parser.getOption("metadata-url");
        String acksStr = parser.getOption("acks");
        
        Integer partition = null;
        if (partitionStr != null) {
            try {
                partition = Integer.parseInt(partitionStr);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Partition must be a number");
            }
        }
        
        int acks = -1; // Default: wait for all replicas
        if (acksStr != null) {
            try {
                acks = Integer.parseInt(acksStr);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Acks must be a number (-1, 0, or 1)");
            }
        }
        
        SimpleProducer producer = new SimpleProducer(metadataUrl);
        
        try {
            if (batchFile != null) {
                // Batch mode: read messages from file
                executeBatchMode(producer, topic, batchFile, partition, acks);
            } else {
                // Single message mode
                executeSingleMode(producer, topic, key, value, partition, acks);
            }
        } finally {
            producer.close();
        }
    }
    
    /**
     * Execute single message mode
     */
    private void executeSingleMode(SimpleProducer producer, String topic, String key, 
                                   String value, Integer partition, int acks) throws Exception {
        System.out.println("Producing message to topic '" + topic + "'...");
        if (key != null) {
            System.out.println("  Key: " + key);
        }
        System.out.println("  Value: " + value);
        if (partition != null) {
            System.out.println("  Partition: " + partition);
        }
        System.out.println();
        
        ProduceResponse response = producer.send(topic, key, value, partition, acks);
        
        if (response.isSuccess() && response.getResults() != null && !response.getResults().isEmpty()) {
            ProduceResponse.ProduceResult result = response.getResults().get(0);
            System.out.println("[OK] Message sent successfully!");
            System.out.println("  Topic: " + response.getTopic());
            System.out.println("  Partition: " + response.getPartition());
            System.out.println("  Offset: " + result.getOffset());
            System.out.println("  Timestamp: " + result.getTimestamp());
        } else {
            System.err.println("[ERROR] Failed to send message");
            System.err.println("  Error: " + response.getErrorMessage());
            System.exit(1);
        }
    }
    
    /**
     * Execute batch mode: read messages from file and send in batch
     * File format: each line contains key:value or just value
     */
    private void executeBatchMode(SimpleProducer producer, String topic, String batchFile,
                                  Integer partition, int acks) throws Exception {
        System.out.println("Producing batch to topic '" + topic + "' from file '" + batchFile + "'...");
        if (partition != null) {
            System.out.println("  Target Partition: " + partition);
        }
        System.out.println();
        
        // Read messages from file
        List<SimpleProducer.MessageEntry> messages = readMessagesFromFile(batchFile);
        
        if (messages.isEmpty()) {
            System.err.println("[ERROR] No messages found in file");
            System.exit(1);
            return;
        }
        
        System.out.println("Read " + messages.size() + " messages from file");
        System.out.println("Sending batch...");
        System.out.println();
        
        // Send batch
        ProduceResponse response = producer.sendBatch(topic, messages, partition, acks);
        
        if (response.isSuccess() && response.getResults() != null) {
            System.out.println("[OK] Batch sent successfully!");
            System.out.println("  Topic: " + response.getTopic());
            System.out.println("  Partition: " + response.getPartition());
            System.out.println("  Messages: " + response.getResults().size());
            System.out.println();
            
            // Show first and last offsets
            if (!response.getResults().isEmpty()) {
                ProduceResponse.ProduceResult first = response.getResults().get(0);
                ProduceResponse.ProduceResult last = response.getResults().get(response.getResults().size() - 1);
                System.out.println("  First Offset: " + first.getOffset());
                System.out.println("  Last Offset: " + last.getOffset());
                System.out.println("  First Timestamp: " + first.getTimestamp());
            }
        } else {
            System.err.println("[ERROR] Failed to send batch");
            System.err.println("  Error: " + response.getErrorMessage());
            System.exit(1);
        }
    }
    
    /**
     * Read messages from file
     * Format: key:value or just value (one per line)
     */
    private List<SimpleProducer.MessageEntry> readMessagesFromFile(String filePath) throws Exception {
        List<SimpleProducer.MessageEntry> messages = new ArrayList<>();
        
        try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
            String line;
            int lineNum = 0;
            while ((line = reader.readLine()) != null) {
                lineNum++;
                line = line.trim();
                
                // Skip empty lines and comments
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                
                // Parse key:value or just value
                String key = null;
                String value;
                
                int colonIndex = line.indexOf(':');
                if (colonIndex > 0 && colonIndex < line.length() - 1) {
                    // Has key
                    key = line.substring(0, colonIndex);
                    value = line.substring(colonIndex + 1);
                } else {
                    // No key, just value
                    value = line;
                }
                
                messages.add(new SimpleProducer.MessageEntry(key, value));
            }
        }
        
        return messages;
    }
    
    @Override
    public void printHelp() {
        System.out.println("Produce messages to a topic (single or batch mode)");
        System.out.println();
        System.out.println("Usage:");
        System.out.println("  Single:  mycli produce --topic <topic> --value <value> [options]");
        System.out.println("  Batch:   mycli produce --topic <topic> --batch-file <file> [options]");
        System.out.println();
        System.out.println("Required Arguments:");
        System.out.println("  --topic <topic>       Topic name");
        System.out.println("  --value <value>       Message value (single mode)");
        System.out.println("  --batch-file <file>   File with messages (batch mode)");
        System.out.println();
        System.out.println("Optional Arguments:");
        System.out.println("  --key <key>           Message key (single mode only)");
        System.out.println("  --partition <n>       Target partition (overrides key-based partitioning)");
        System.out.println("  --acks <n>            Acknowledgment mode: -1 (all), 1 (leader), 0 (none)");
        System.out.println("  --metadata-url <url>  Metadata service URL (default: from config)");
        System.out.println();
        System.out.println("Batch File Format:");
        System.out.println("  Each line: key:value  or  value");
        System.out.println("  Lines starting with # are comments");
        System.out.println("  Empty lines are ignored");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  # Single message");
        System.out.println("  mycli produce --topic orders --value \"Order data\"");
        System.out.println("  mycli produce --topic orders --key order-123 --value \"Order data\"");
        System.out.println();
        System.out.println("  # Batch messages from file");
        System.out.println("  mycli produce --topic logs --batch-file messages.txt");
        System.out.println("  mycli produce --topic events --batch-file events.txt --acks 1");
        System.out.println("  mycli produce --topic orders --batch-file orders.txt --partition 0");
    }
}
