package com.distributedmq.client.cli.commands;

/**
 * Base interface for CLI commands
 */
public interface Command {
    
    /**
     * Execute the command with given arguments
     * @param args Command arguments (excluding the command name itself)
     * @throws Exception if command execution fails
     */
    void execute(String[] args) throws Exception;
    
    /**
     * Print help information for this command
     */
    void printHelp();
}
