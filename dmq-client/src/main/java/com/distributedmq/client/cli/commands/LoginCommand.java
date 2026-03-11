package com.distributedmq.client.cli.commands;

import com.distributedmq.client.cli.utils.ArgumentParser;
import com.distributedmq.client.cli.utils.TokenManager;
import com.distributedmq.common.config.ServiceDiscovery;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.Console;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Command to authenticate with metadata service and obtain JWT token
 * Usage: mycli login --username <user> --password <pass>
 *        mycli login --username <user>  (prompts for password)
 */
public class LoginCommand implements Command {
    
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    
    public LoginCommand() {
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
        
        // Parse arguments
        String username = parser.getOption("username");
        if (username == null) {
            throw new IllegalArgumentException("Missing required argument: --username");
        }
        
        String password = parser.getOption("password");
        
        // If password not provided, prompt for it
        if (password == null) {
            Console console = System.console();
            if (console != null) {
                char[] passwordChars = console.readPassword("Password: ");
                password = new String(passwordChars);
            } else {
                // Fallback for environments without console (IDE)
                System.out.print("Password: ");
                password = new java.util.Scanner(System.in).nextLine();
            }
        }
        
        if (password == null || password.isEmpty()) {
            throw new IllegalArgumentException("Password cannot be empty");
        }
        
        // Optional metadata service URL
        String metadataUrl = parser.getOption("metadata-url");
        
        // Discover leader if URL not provided
        if (metadataUrl == null) {
            metadataUrl = discoverLeader();
        }
        
        System.out.println("Authenticating with " + metadataUrl + "...");
        System.out.println("Username: " + username);
        System.out.println();
        
        // Call login endpoint
        String loginUrl = metadataUrl + "/api/v1/auth/login";
        
        // Create request body
        Map<String, String> loginRequest = new HashMap<>();
        loginRequest.put("username", username);
        loginRequest.put("password", password);
        
        String requestBody = objectMapper.writeValueAsString(loginRequest);
        
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(loginUrl))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
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
            throw new RuntimeException("Authentication failed: Invalid username or password");
        }
        
        if (response.statusCode() != 200) {
            throw new RuntimeException("Login failed: HTTP " + response.statusCode() + " - " + response.body());
        }
        
        // Parse response
        Map<String, Object> loginResponse = objectMapper.readValue(response.body(), Map.class);
        String token = (String) loginResponse.get("token");
        
        if (token == null || token.isEmpty()) {
            throw new RuntimeException("No token received from server");
        }
        
        // Save token
        TokenManager.getInstance().saveToken(token, username);
        
        System.out.println("[OK] Authentication successful!");
        System.out.println("Token saved to ~/.dmq/token.properties");
        System.out.println();
        System.out.println("You can now use other commands without authentication.");
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
        System.out.println("Authenticate with metadata service and obtain JWT token");
        System.out.println();
        System.out.println("Usage: mycli login --username <user> [--password <pass>]");
        System.out.println();
        System.out.println("Required Arguments:");
        System.out.println("  --username <user>        Username for authentication");
        System.out.println();
        System.out.println("Optional Arguments:");
        System.out.println("  --password <pass>        Password (will prompt if not provided)");
        System.out.println("  --metadata-url <url>     Metadata service URL (default: auto-discover leader)");
        System.out.println();
        System.out.println("Available Users (configured in application.yml):");
        System.out.println("  admin       - Full access (ADMIN role)");
        System.out.println("  producer1   - Produce messages (PRODUCER role)");
        System.out.println("  consumer1   - Consume messages (CONSUMER role)");
        System.out.println("  app1        - Produce and consume (PRODUCER, CONSUMER roles)");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  mycli login --username admin --password admin123");
        System.out.println("  mycli login --username producer1");
        System.out.println("  mycli login --username admin --metadata-url http://localhost:9091");
        System.out.println();
        System.out.println("Note: Token is saved to ~/.dmq/token.properties for subsequent commands");
    }
}
