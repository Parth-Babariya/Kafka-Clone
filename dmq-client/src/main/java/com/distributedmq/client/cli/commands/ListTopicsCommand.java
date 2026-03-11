package com.distributedmq.client.cli.commands;

import com.distributedmq.client.cli.utils.ArgumentParser;
import com.distributedmq.client.cli.utils.MetadataServiceClient;

import java.util.List;

/**
 * Command to list all topics
 * Usage: mycli list-topics
 */
public class ListTopicsCommand implements Command {
    
    @Override
    public void execute(String[] args) throws Exception {
        ArgumentParser parser = new ArgumentParser(args);
        
        if (parser.hasFlag("help") || parser.hasFlag("h")) {
            printHelp();
            return;
        }
        
        // Optional metadata service URL
        String metadataUrl = parser.getOption("metadata-url");
        
        MetadataServiceClient client = new MetadataServiceClient(metadataUrl);
        
        System.out.println("Fetching topics...");
        System.out.println();
        
        List<String> topics = client.listTopics();
        
        if (topics.isEmpty()) {
            System.out.println("No topics found.");
        } else {
            System.out.println("========================================");
            System.out.println("TOPICS (" + topics.size() + " total)");
            System.out.println("========================================");
            for (int i = 0; i < topics.size(); i++) {
                System.out.println("  " + (i + 1) + ". " + topics.get(i));
            }
            System.out.println("========================================");
        }
    }
    
    @Override
    public void printHelp() {
        System.out.println("List all topics");
        System.out.println();
        System.out.println("Usage: mycli list-topics [options]");
        System.out.println();
        System.out.println("Optional Arguments:");
        System.out.println("  --metadata-url <url>    Metadata service URL (default: from config)");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  mycli list-topics");
        System.out.println("  mycli list-topics --metadata-url http://localhost:9091");
    }
}
