package com.distributedmq.client.cli.commands;

import com.distributedmq.client.cli.utils.ArgumentParser;
import com.distributedmq.client.cli.utils.TokenManager;
import com.distributedmq.common.config.ServiceDiscovery;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Command to refresh JWT token
 * Requires existing valid or expired token
 * Usage: mycli refresh-token
 */
public class RefreshTokenCommand implements Command {
    
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    
    public RefreshTokenCommand() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.objectMapper = new ObjectMapper();
    }
    
    @Override
    public void execute(String[] args) throws Exception {
        ArgumentParser parser = new ArgumentParser(args);
        
        if (parser.hasFlag("help") || parser.hasFlag("h")) {
            printHelp();
            return;
        }
        
        // Check if token exists
        TokenManager tokenManager = TokenManager.getInstance();
        if (!tokenManager.hasToken()) {
            throw new IllegalStateException("No token found. Please login first using: mycli login --username <user>");
        }
        
        String currentToken = tokenManager.getToken();
        String currentUser = tokenManager.getUsername();
        
        System.out.println("Refreshing token for user: " + currentUser);
        System.out.println();
        
        // Optional metadata service URL
        String metadataUrl = parser.getOption("metadata-url");
        
        // Discover leader if URL not provided
        if (metadataUrl == null) {
            metadataUrl = discoverLeader();
        }
        
        // Call refresh endpoint
        String refreshUrl = metadataUrl + "/api/v1/auth/refresh";
        
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(refreshUrl))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + currentToken)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        
        if (response.statusCode() == 503) {
            // Not the leader, retry with actual leader
            String leaderHeader = response.headers().firstValue("X-Leader-Id").orElse(null);
            if (leaderHeader != null) {
                System.out.println("⚠️  Node is not the leader. Discovering actual leader...");
                metadataUrl = discoverLeader();
                // Retry
                execute(args);
                return;
            }
        }
        
        if (response.statusCode() == 401) {
            throw new RuntimeException("Token refresh failed: Token is invalid or user no longer exists. Please login again.");
        }
        
        if (response.statusCode() != 200) {
            throw new RuntimeException("Token refresh failed: HTTP " + response.statusCode() + " - " + response.body());
        }
        
        // Parse response
        Map<String, Object> refreshResponse = objectMapper.readValue(response.body(), Map.class);
        String newToken = (String) refreshResponse.get("token");
        Object expiresInObj = refreshResponse.get("expiresIn");
        
        if (newToken == null || newToken.isEmpty()) {
            throw new RuntimeException("No token received from server");
        }
        
        // Save new token
        tokenManager.saveToken(newToken, currentUser);
        
        long expiresIn = 0;
        if (expiresInObj instanceof Number) {
            expiresIn = ((Number) expiresInObj).longValue();
        }
        
        System.out.println("[OK] Token refreshed successfully!");
        System.out.println("New token expires in: " + expiresIn + " seconds (" + (expiresIn / 60) + " minutes)");
        System.out.println();
    }
    
    /**
     * Discover Raft leader from configured metadata services
     */
    private String discoverLeader() throws Exception {
        ServiceDiscovery.loadConfig();
        List<ServiceDiscovery.ServiceInfo> metadataServices = ServiceDiscovery.getAllMetadataServices();
        
        for (ServiceDiscovery.ServiceInfo service : metadataServices) {
            try {
                String url = service.getUrl() + "/api/v1/metadata/controller";
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(5))
                        .GET()
                        .build();
                
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                
                if (response.statusCode() == 200) {
                    // Parse response to get actual controller URL
                    try {
                        var controllerInfo = objectMapper.readTree(response.body());
                        String actualControllerUrl = controllerInfo.get("controllerUrl").asText();
                        System.out.println("📡 Connected to leader: " + actualControllerUrl);
                        return actualControllerUrl;
                    } catch (Exception e) {
                        System.out.println("📡 Connected to leader: " + service.getUrl());
                        return service.getUrl();
                    }
                }
            } catch (Exception e) {
                // Try next service
            }
        }
        
        throw new RuntimeException("Cannot connect to any metadata service. Please check if services are running.");
    }
    
    @Override
    public void printHelp() {
        System.out.println("Refresh JWT token for current authenticated user");
        System.out.println();
        System.out.println("Usage: mycli refresh-token");
        System.out.println();
        System.out.println("Optional Arguments:");
        System.out.println("  --metadata-url <url>     Metadata service URL (default: auto-discover leader)");
        System.out.println();
        System.out.println("Requirements:");
        System.out.println("  - Must be logged in (token stored in ~/.dmq/token.properties)");
        System.out.println("  - Token can be valid or expired (grace period allowed)");
        System.out.println("  - User must still exist in server configuration");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  mycli refresh-token");
        System.out.println("  mycli refresh-token --metadata-url http://localhost:9091");
        System.out.println();
        System.out.println("Note: New token is automatically saved to ~/.dmq/token.properties");
    }
}
