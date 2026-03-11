package com.distributedmq.client.cli.commands;

import com.distributedmq.client.cli.utils.ArgumentParser;
import com.distributedmq.client.cli.utils.MetadataServiceClient;

/**
 * Command to delete a topic
 * Usage: mycli delete-topic --name <topic>
 */
public class DeleteTopicCommand implements Command {
    
    @Override
    public void execute(String[] args) throws Exception {
        ArgumentParser parser = new ArgumentParser(args);
        
        if (parser.hasFlag("help") || parser.hasFlag("h")) {
            printHelp();
            return;
        }
        
        // Parse required argument
        String topicName = parser.getOption("name");
        if (topicName == null) {
            throw new IllegalArgumentException("Missing required argument: --name");
        }
        
        // Optional metadata service URL
        String metadataUrl = parser.getOption("metadata-url");
        
        // Confirm deletion
        System.out.println("WARNING: This will permanently delete topic '" + topicName + "'");
        System.out.print("Are you sure? (yes/no): ");
        
        // Read confirmation from stdin
        java.util.Scanner scanner = new java.util.Scanner(System.in);
        String confirmation = scanner.nextLine().trim().toLowerCase();
        
        if (!confirmation.equals("yes") && !confirmation.equals("y")) {
            System.out.println("Deletion cancelled.");
            return;
        }
        
        // Delete the topic
        System.out.println("Deleting topic '" + topicName + "'...");
        
        MetadataServiceClient client = new MetadataServiceClient(metadataUrl);
        client.deleteTopic(topicName);
        
        System.out.println("[OK] Topic '" + topicName + "' deleted successfully!");
    }
    
    @Override
    public void printHelp() {
        System.out.println("Delete a topic");
        System.out.println();
        System.out.println("Usage: mycli delete-topic --name <topic>");
        System.out.println();
        System.out.println("Required Arguments:");
        System.out.println("  --name <topic>              Topic name to delete");
        System.out.println();
        System.out.println("Optional Arguments:");
        System.out.println("  --metadata-url <url>        Metadata service URL (default: from config)");
        System.out.println();
        System.out.println("Note: Requires ADMIN role. This operation is permanent.");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  mycli delete-topic --name orders");
        System.out.println("  mycli delete-topic --name logs --metadata-url http://localhost:9091");
    }
}
