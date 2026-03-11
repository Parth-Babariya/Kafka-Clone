package com.distributedmq.client.cli.commands;

import com.distributedmq.client.cli.utils.ArgumentParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.HttpURLConnection;
import java.net.URL;
import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Command to get Raft leader information
 * Usage: mycli get-leader
 */
public class GetLeaderCommand implements Command {
    
    private static final String DEFAULT_METADATA_URL = "http://localhost:9091";
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    @Override
    public void execute(String[] args) throws Exception {
        ArgumentParser parser = new ArgumentParser(args);
        
        if (parser.hasFlag("help") || parser.hasFlag("h")) {
            printHelp();
            return;
        }
        
        // Optional metadata service URL
        String metadataUrl = parser.getOption("metadata-url");
        if (metadataUrl == null) {
            metadataUrl = DEFAULT_METADATA_URL;
        }
        
        System.out.println("Querying: " + metadataUrl);
        System.out.println();
        
        String endpoint = metadataUrl + "/api/v1/raft/status";
        URL url = new URL(endpoint);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        
        int responseCode = conn.getResponseCode();
        
        if (responseCode != 200) {
            throw new RuntimeException("Failed to get Raft status: HTTP " + responseCode);
        }
        
        BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
        StringBuilder response = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            response.append(line);
        }
        reader.close();
        
        // Parse JSON response
        JsonNode json = objectMapper.readTree(response.toString());
        
        System.out.println("========================================");
        System.out.println("RAFT CLUSTER STATUS");
        System.out.println("========================================");
        System.out.println();
        
        // Safely get node ID
        int nodeId = json.has("nodeId") ? json.get("nodeId").asInt() : -1;
        System.out.println("Queried Node:    Node " + nodeId + " (" + metadataUrl + ")");
        
        // Safely get term
        long term = json.has("currentTerm") ? json.get("currentTerm").asLong() : 0;
        System.out.println("Current Term:    " + term);
        
        // Safely get state
        String state = json.has("state") ? json.get("state").asText() : "UNKNOWN";
        System.out.println("State:           " + state);
        
        // Safely get isLeader (backend returns "leader", not "isLeader")
        boolean isLeader = json.has("leader") && json.get("leader").asBoolean();
        System.out.println("Is Leader:       " + (isLeader ? "YES" : "NO"));
        System.out.println();
        
        // Leader information
        System.out.println("LEADER INFO:");
        if (json.has("leaderId") && !json.get("leaderId").isNull()) {
            int leaderId = json.get("leaderId").asInt();
            System.out.println("  Leader Node:   Node " + leaderId);
            if (isLeader) {
                System.out.println("  Status:        THIS NODE IS THE LEADER");
            } else {
                System.out.println("  Status:        This node is a FOLLOWER");
            }
        } else {
            System.out.println("  Status:        NO LEADER ELECTED");
            System.out.println("  Note:          Cluster may be starting up or in election");
        }
        
        System.out.println();
        
        // Additional metrics
        if (json.has("commitIndex") && !json.get("commitIndex").isNull()) {
            System.out.println("Commit Index:    " + json.get("commitIndex").asLong());
        }
        if (json.has("lastApplied") && !json.get("lastApplied").isNull()) {
            System.out.println("Last Applied:    " + json.get("lastApplied").asLong());
        }
        
        System.out.println("========================================");
    }
    
    @Override
    public void printHelp() {
        System.out.println("Get Raft leader information");
        System.out.println();
        System.out.println("Usage: mycli get-leader [options]");
        System.out.println();
        System.out.println("Optional Arguments:");
        System.out.println("  --metadata-url <url>    Metadata service URL (default: http://localhost:9091)");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  mycli get-leader");
        System.out.println("  mycli get-leader --metadata-url http://localhost:9092");
    }
}
