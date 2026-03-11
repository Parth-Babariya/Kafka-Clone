package com.distributedmq.client.cli.utils;

import java.io.*;
import java.nio.file.*;
import java.util.Properties;

/**
 * Manages JWT tokens for CLI and client applications
 * Stores token in user home directory: ~/.dmq/token
 */
public class TokenManager {
    
    private static final String DMQ_DIR = System.getProperty("user.home") + File.separator + ".dmq";
    private static final String TOKEN_FILE = DMQ_DIR + File.separator + "token.properties";
    
    private static TokenManager instance;
    private String currentToken;
    private String currentUser;
    
    private TokenManager() {
        loadToken();
    }
    
    public static synchronized TokenManager getInstance() {
        if (instance == null) {
            instance = new TokenManager();
        }
        return instance;
    }
    
    /**
     * Save token to file
     */
    public void saveToken(String token, String username) throws IOException {
        this.currentToken = token;
        this.currentUser = username;
        
        // Create directory if doesn't exist
        File dir = new File(DMQ_DIR);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        
        // Save to properties file
        Properties props = new Properties();
        props.setProperty("token", token);
        props.setProperty("username", username);
        props.setProperty("timestamp", String.valueOf(System.currentTimeMillis()));
        props.setProperty("saved-at", new java.util.Date().toString());
        
        try (FileWriter writer = new FileWriter(TOKEN_FILE)) {
            props.store(writer, "DMQ Authentication Token");
        }
        
        System.out.println("✓ Token saved for user: " + username);
    }
    
    /**
     * Load token from file
     */
    private void loadToken() {
        File tokenFile = new File(TOKEN_FILE);
        if (!tokenFile.exists()) {
            return;
        }
        
        try {
            Properties props = new Properties();
            try (FileReader reader = new FileReader(tokenFile)) {
                props.load(reader);
            }
            
            this.currentToken = props.getProperty("token");
            this.currentUser = props.getProperty("username");
            
        } catch (IOException e) {
            // Ignore load errors
        }
    }
    
    /**
     * Get current token
     */
    public String getToken() {
        return currentToken;
    }
    
    /**
     * Get current username
     */
    public String getUsername() {
        return currentUser;
    }
    
    /**
     * Check if token exists
     */
    public boolean hasToken() {
        return currentToken != null && !currentToken.isEmpty();
    }
    
    /**
     * Clear token
     */
    public void clearToken() throws IOException {
        this.currentToken = null;
        this.currentUser = null;
        
        File tokenFile = new File(TOKEN_FILE);
        if (tokenFile.exists()) {
            tokenFile.delete();
        }
        
        System.out.println("✓ Token cleared");
    }
    
    /**
     * Get Authorization header value
     */
    public String getAuthorizationHeader() {
        if (currentToken == null) {
            return null;
        }
        return "Bearer " + currentToken;
    }
    
    /**
     * Check if token is likely expired (based on 15 minute default expiry)
     * This is an estimate - actual expiry is validated server-side
     */
    public boolean isTokenLikelyExpired() {
        File tokenFile = new File(TOKEN_FILE);
        if (!tokenFile.exists() || currentToken == null) {
            return true;
        }
        
        try {
            Properties props = new Properties();
            try (FileReader reader = new FileReader(tokenFile)) {
                props.load(reader);
            }
            
            String timestampStr = props.getProperty("timestamp");
            if (timestampStr == null) {
                return false; // Cannot determine, assume valid
            }
            
            long savedTimestamp = Long.parseLong(timestampStr);
            long currentTime = System.currentTimeMillis();
            long ageMinutes = (currentTime - savedTimestamp) / 1000 / 60;
            
            // Assume 15 minute expiry (default config)
            return ageMinutes >= 15;
            
        } catch (Exception e) {
            return false; // Cannot determine, assume valid
        }
    }
    
    /**
     * Get token age in minutes
     */
    public long getTokenAgeMinutes() {
        File tokenFile = new File(TOKEN_FILE);
        if (!tokenFile.exists()) {
            return -1;
        }
        
        try {
            Properties props = new Properties();
            try (FileReader reader = new FileReader(tokenFile)) {
                props.load(reader);
            }
            
            String timestampStr = props.getProperty("timestamp");
            if (timestampStr == null) {
                return -1;
            }
            
            long savedTimestamp = Long.parseLong(timestampStr);
            long currentTime = System.currentTimeMillis();
            return (currentTime - savedTimestamp) / 1000 / 60;
            
        } catch (Exception e) {
            return -1;
        }
    }
}
