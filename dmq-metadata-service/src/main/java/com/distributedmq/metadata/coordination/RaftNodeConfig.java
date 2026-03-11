package com.distributedmq.metadata.coordination;

import com.distributedmq.metadata.config.ClusterTopologyConfig;
import lombok.Getter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Raft Node Configuration
 * Provides cluster topology and node information for Raft consensus
 * Loads metadata service nodes from centralized config/services.json
 */
@Configuration
@Getter
@DependsOn("clusterTopologyConfig")
public class RaftNodeConfig {

    @Value("${kraft.node-id}")
    private Integer nodeId;

    @Value("${server.port:8080}")
    private Integer port;

    @Value("${kraft.cluster.host:localhost}")
    private String host;

    @Autowired
    private ClusterTopologyConfig clusterTopologyConfig;

    private List<NodeInfo> allNodes = new ArrayList<>();
    private List<NodeInfo> peers = new ArrayList<>();

    @PostConstruct
    public void init() {
        // Load metadata service nodes from centralized config
        List<ClusterTopologyConfig.MetadataServiceInfo> metadataServices = 
            clusterTopologyConfig.getMetadataServices();
        
        if (metadataServices == null || metadataServices.isEmpty()) {
            String errorMsg = "FATAL: No metadata services found in config/services.json! " +
                "Raft cluster requires at least one metadata service node.";
            System.err.println(errorMsg);
            throw new IllegalStateException(errorMsg);
        }

        // Convert metadata services to NodeInfo objects
        for (ClusterTopologyConfig.MetadataServiceInfo service : metadataServices) {
            NodeInfo nodeInfo = new NodeInfo(service.getId(), service.getHost(), service.getPort());
            allNodes.add(nodeInfo);

            // Peers are all nodes except this one
            if (service.getId() != nodeId) {
                peers.add(nodeInfo);
            }
        }

        // Verify current node exists in config
        boolean currentNodeExists = allNodes.stream()
            .anyMatch(node -> node.getNodeId() == nodeId);
        
        if (!currentNodeExists) {
            String errorMsg = "FATAL: Current node ID " + nodeId + 
                " not found in config/services.json metadata-services list! " +
                "Available node IDs: " + allNodes.stream()
                    .map(n -> String.valueOf(n.getNodeId()))
                    .collect(Collectors.joining(", "));
            System.err.println(errorMsg);
            throw new IllegalStateException(errorMsg);
        }

        // Log configuration
        System.out.println("=".repeat(80));
        System.out.println("Raft Node Configuration (loaded from config/services.json):");
        System.out.println("  Current Node: " + nodeId + " (" + host + ":" + port + ")");
        System.out.println("  All Metadata Service Nodes (" + allNodes.size() + "):");
        for (NodeInfo node : allNodes) {
            String marker = (node.getNodeId() == nodeId) ? " <- THIS NODE" : "";
            System.out.println("    - Node " + node.getNodeId() + ": " + 
                node.getHost() + ":" + node.getPort() + marker);
        }
        System.out.println("  Peer Nodes (" + peers.size() + "): " + peers.stream()
                .map(n -> "Node " + n.getNodeId())
                .collect(Collectors.joining(", ")));
        System.out.println("=".repeat(80));
    }

    /**
     * Get peer nodes (all nodes except this one)
     */
    public List<NodeInfo> getPeers() {
        return new ArrayList<>(peers);
    }

    /**
     * Get all nodes in the cluster
     */
    public List<NodeInfo> getAllNodes() {
        return new ArrayList<>(allNodes);
    }

    /**
     * Get current node info
     */
    public NodeInfo getCurrentNode() {
        return allNodes.stream()
                .filter(node -> node.getNodeId() == nodeId)
                .findFirst()
                .orElse(new NodeInfo(nodeId, host, port));
    }

    /**
     * Node information
     */
    public static class NodeInfo {
        private final int nodeId;
        private final String host;
        private final int port;

        public NodeInfo(int nodeId, String host, int port) {
            this.nodeId = nodeId;
            this.host = host;
            this.port = port;
        }

        public int getNodeId() { return nodeId; }
        public String getHost() { return host; }
        public int getPort() { return port; }

        public String getAddress() {
            return host + ":" + port;
        }

        @Override
        public String toString() {
            return nodeId + ":" + host + ":" + port;
        }
    }
}
