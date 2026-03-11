package com.distributedmq.client.cli.commands;

import com.distributedmq.client.cli.utils.ArgumentParser;
import com.distributedmq.client.cli.utils.TokenManager;

/**
 * Command to logout and clear stored JWT token
 * Usage: mycli logout
 */
public class LogoutCommand implements Command {
    
    @Override
    public void execute(String[] args) throws Exception {
        ArgumentParser parser = new ArgumentParser(args);
        
        if (parser.hasFlag("help") || parser.hasFlag("h")) {
            printHelp();
            return;
        }
        
        TokenManager tokenManager = TokenManager.getInstance();
        
        if (!tokenManager.hasToken()) {
            System.out.println("No active session found.");
            return;
        }
        
        String username = tokenManager.getUsername();
        tokenManager.clearToken();
        
        System.out.println("[OK] Logged out successfully!");
        if (username != null) {
            System.out.println("User: " + username);
        }
    }
    
    @Override
    public void printHelp() {
        System.out.println("Logout and clear stored JWT token");
        System.out.println();
        System.out.println("Usage: mycli logout");
        System.out.println();
        System.out.println("This will remove the stored token from ~/.dmq/token.properties");
        System.out.println("You will need to login again to use authenticated commands.");
    }
}
