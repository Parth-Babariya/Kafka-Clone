package com.distributedmq.client.cli.commands;

import com.distributedmq.client.cli.utils.ArgumentParser;
import com.distributedmq.client.cli.utils.MetadataServiceClient;
import com.distributedmq.common.dto.CreateTopicRequest;

import java.util.Map;

/**
 * Command to create a new topic
 * Usage: mycli create-topic --name <topic> --partitions <n> --replication-factor <n>
 */
public class CreateTopicCommand implements Command {
    
    @Override
    public void execute(String[] args) throws Exception {
        ArgumentParser parser = new ArgumentParser(args);
        
        if (parser.hasFlag("help") || parser.hasFlag("h")) {
            printHelp();
            return;
        }
        
        // Parse required arguments
        String topicName = parser.getOption("name");
        if (topicName == null) {
            throw new IllegalArgumentException("Missing required argument: --name");
        }
        
        String partitionsStr = parser.getOption("partitions");
        if (partitionsStr == null) {
            throw new IllegalArgumentException("Missing required argument: --partitions");
        }
        
        String replicationStr = parser.getOption("replication-factor");
        if (replicationStr == null) {
            throw new IllegalArgumentException("Missing required argument: --replication-factor");
        }
        
        int partitions;
        int replicationFactor;
        
        try {
            partitions = Integer.parseInt(partitionsStr);
            replicationFactor = Integer.parseInt(replicationStr);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Partitions and replication-factor must be numbers");
        }
        
        if (partitions < 1) {
            throw new IllegalArgumentException("Partitions must be at least 1");
        }
        
        if (replicationFactor < 1) {
            throw new IllegalArgumentException("Replication factor must be at least 1");
        }
        
        // Optional metadata service URL
        String metadataUrl = parser.getOption("metadata-url");
        
        // Create the topic
        System.out.println("Creating topic '" + topicName + "'...");
        System.out.println("  Partitions: " + partitions);
        System.out.println("  Replication Factor: " + replicationFactor);
        System.out.println();
        
        MetadataServiceClient client = new MetadataServiceClient(metadataUrl);
        
        CreateTopicRequest request = new CreateTopicRequest();
        request.setTopicName(topicName);
        request.setPartitionCount(partitions);
        request.setReplicationFactor(replicationFactor);
        
        client.createTopic(request);
        
        System.out.println("[OK] Topic '" + topicName + "' created successfully!");
    }
    
    @Override
    public void printHelp() {
        System.out.println("Create a new topic");
        System.out.println();
        System.out.println("Usage: mycli create-topic --name <topic> --partitions <n> --replication-factor <n>");
        System.out.println();
        System.out.println("Required Arguments:");
        System.out.println("  --name <topic>              Topic name");
        System.out.println("  --partitions <n>            Number of partitions");
        System.out.println("  --replication-factor <n>    Replication factor");
        System.out.println();
        System.out.println("Optional Arguments:");
        System.out.println("  --metadata-url <url>        Metadata service URL (default: from config)");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  mycli create-topic --name orders --partitions 3 --replication-factor 2");
        System.out.println("  mycli create-topic --name logs --partitions 10 --replication-factor 3 --metadata-url http://localhost:9091");
    }
}
