package com.distributedmq.client.cli.commands;

import com.distributedmq.client.cli.utils.ArgumentParser;
import com.distributedmq.client.cli.utils.MetadataServiceClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Command to list all brokers
 * Usage: mycli list-brokers
 */
public class ListBrokersCommand implements Command {
    
    @Override
    public void execute(String[] args) throws Exception {
        ArgumentParser parser = new ArgumentParser(args);
        
        if (parser.hasFlag("help") || parser.hasFlag("h")) {
            printHelp();
            return;
        }
        
        // Optional metadata service URL
        String metadataUrl = parser.getOption("metadata-url");
        
        // List brokers
        System.out.println("Fetching brokers...");
        System.out.println();
        
        MetadataServiceClient client = new MetadataServiceClient(metadataUrl);
        String brokersJson = client.listBrokers();
        
        // Parse and format output
        ObjectMapper mapper = new ObjectMapper();
        JsonNode brokers = mapper.readTree(brokersJson);
        
        if (brokers.isArray() && brokers.size() == 0) {
            System.out.println("No brokers registered.");
            return;
        }
        
        System.out.println("Brokers (" + brokers.size() + "):");
        System.out.println("========================================");
        
        for (JsonNode broker : brokers) {
            int id = broker.get("id").asInt();
            String host = broker.get("host").asText();
            int port = broker.get("port").asInt();
            String status = broker.has("status") ? broker.get("status").asText() : "UNKNOWN";
            String address = broker.has("address") ? broker.get("address").asText() : (host + ":" + port);
            
            System.out.println();
            System.out.println("  Broker ID: " + id);
            System.out.println("  Address:   " + address);
            System.out.println("  Status:    " + status);
            
            if (broker.has("registeredAt")) {
                long registeredAt = broker.get("registeredAt").asLong();
                System.out.println("  Registered: " + new java.util.Date(registeredAt));
            }
        }
        
        System.out.println();
        System.out.println("========================================");
    }
    
    @Override
    public void printHelp() {
        System.out.println("List all registered brokers");
        System.out.println();
        System.out.println("Usage: mycli list-brokers");
        System.out.println();
        System.out.println("Optional Arguments:");
        System.out.println("  --metadata-url <url>        Metadata service URL (default: from config)");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  mycli list-brokers");
        System.out.println("  mycli list-brokers --metadata-url http://localhost:9091");
    }
}
