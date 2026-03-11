package com.distributedmq.client.cli.commands;

import com.distributedmq.client.cli.utils.ArgumentParser;
import com.distributedmq.client.cli.utils.MetadataServiceClient;
import com.distributedmq.common.dto.ConsumerGroupResponse;

import java.util.List;

/**
 * Command to list all consumer groups
 * Usage: mycli list-groups [options]
 */
public class ListGroupsCommand implements Command {
    
    @Override
    public void execute(String[] args) throws Exception {
        ArgumentParser parser = new ArgumentParser(args);
        
        if (parser.hasFlag("help") || parser.hasFlag("h")) {
            printHelp();
            return;
        }
        
        String metadataUrl = parser.getOption("metadata-url");
        
        System.out.println("Fetching consumer groups...");
        System.out.println();
        
        MetadataServiceClient client = new MetadataServiceClient(metadataUrl);
        List<ConsumerGroupResponse> groups = client.listConsumerGroups();
        
        if (groups.isEmpty()) {
            System.out.println("No consumer groups found");
            return;
        }
        
        System.out.println("================================================================================");
        System.out.println("                           Consumer Groups                                   ");
        System.out.println("================================================================================");
        System.out.printf("%-40s %-30s%n", "Group ID", "Topic");
        System.out.println("--------------------------------------------------------------------------------");
        
        for (ConsumerGroupResponse group : groups) {
            System.out.printf("%-40s %-30s%n",
                    truncate(group.getGroupId(), 40),
                    truncate(group.getTopic(), 30));
        }
        
        System.out.println("================================================================================");
        System.out.println();
        System.out.println("Total groups: " + groups.size());
        System.out.println();
        System.out.println("Use 'mycli describe-group --topic <topic> --app-id <app-id>' for detailed information");
    }
    
    private String truncate(String str, int maxLength) {
        if (str == null) {
            return "";
        }
        if (str.length() <= maxLength) {
            return str;
        }
        return str.substring(0, maxLength - 3) + "...";
    }
    
    @Override
    public void printHelp() {
        System.out.println("List all consumer groups");
        System.out.println();
        System.out.println("Usage: mycli list-groups [options]");
        System.out.println();
        System.out.println("Optional Arguments:");
        System.out.println("  --metadata-url <url>      Metadata service URL (default: from config)");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  mycli list-groups");
        System.out.println("  mycli list-groups --metadata-url http://localhost:9091");
    }
}
