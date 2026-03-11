package com.distributedmq.client.cli.utils;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Simple command-line argument parser
 * Supports:
 *   --option value (for options with values)
 *   --flag (for boolean flags)
 */
public class ArgumentParser {
    
    private final Map<String, String> options = new HashMap<>();
    private final Set<String> flags = new HashSet<>();
    
    public ArgumentParser(String[] args) {
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            
            if (arg.startsWith("--")) {
                String key = arg.substring(2);
                
                // Check if there's a value following
                if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                    options.put(key, args[i + 1]);
                    i++; // Skip next arg since we consumed it
                } else {
                    // It's a flag
                    flags.add(key);
                }
            } else if (arg.startsWith("-")) {
                // Single dash flag
                String key = arg.substring(1);
                flags.add(key);
            }
        }
    }
    
    /**
     * Get option value
     * @param name Option name (without dashes)
     * @return Option value or null if not present
     */
    public String getOption(String name) {
        return options.get(name);
    }
    
    /**
     * Check if flag is present
     * @param name Flag name (without dashes)
     * @return true if flag is present
     */
    public boolean hasFlag(String name) {
        return flags.contains(name);
    }
    
    /**
     * Get all options
     */
    public Map<String, String> getAllOptions() {
        return new HashMap<>(options);
    }
    
    /**
     * Get all flags
     */
    public Set<String> getAllFlags() {
        return new HashSet<>(flags);
    }
}
