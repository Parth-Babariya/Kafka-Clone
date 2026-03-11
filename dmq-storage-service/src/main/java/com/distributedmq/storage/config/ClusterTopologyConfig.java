package com.distributedmq.storage.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * Loads cluster topology configuration from centralized config file
 * Reads config/services.json to get broker (storage node) and metadata node topology
 */
@Slf4j
@Component
@Data
public class ClusterTopologyConfig {

    /**
     * Path to centralized cluster configuration file
     */
    private static final String CLUSTER_CONFIG_FILE_PATH = "../config/services.json";

    private TopologyData topology;

    @PostConstruct
    public void loadTopology() {
        ObjectMapper objectMapper = new ObjectMapper();
        
        try {
            File configFile = new File(CLUSTER_CONFIG_FILE_PATH);
            
            if (!configFile.exists()) {
                String absolutePath = configFile.getAbsolutePath();
                log.error("Cluster configuration file not found at: {}", absolutePath);
                log.error("Expected location: {}", CLUSTER_CONFIG_FILE_PATH);
                log.error("Please ensure config/services.json exists at project root");
                throw new IllegalStateException(
                    "FATAL: Cluster configuration file not found at: " + CLUSTER_CONFIG_FILE_PATH + 
                    "\nExpected absolute path: " + absolutePath +
                    "\nApplication cannot start without cluster topology configuration."
                );
            }
            
            log.info("Loading cluster topology from: {}", configFile.getAbsolutePath());
            ServicesConfig config = objectMapper.readValue(configFile, ServicesConfig.class);
            this.topology = config.getServices();
            
            if (topology == null) {
                throw new IllegalStateException("Invalid config file: 'services' section not found");
            }
            
            log.info("Successfully loaded cluster topology:");
            log.info("  - Metadata services: {}", 
                topology.getMetadataServices() != null ? topology.getMetadataServices().size() : 0);
            log.info("  - Storage services (brokers): {}", 
                topology.getStorageServices() != null ? topology.getStorageServices().size() : 0);
            
            // Log storage service (broker) details
            if (topology.getStorageServices() != null) {
                topology.getStorageServices().forEach(broker -> {
                    log.info("    Storage Service (Broker) {}: {}:{} @ {}", 
                        broker.getId(), broker.getHost(), broker.getPort(), broker.getUrl());
                });
            }
            
        } catch (IOException e) {
            log.error("Failed to load cluster topology configuration from {}: {}", 
                CLUSTER_CONFIG_FILE_PATH, e.getMessage(), e);
            throw new RuntimeException(
                "FATAL: Failed to load cluster topology from " + CLUSTER_CONFIG_FILE_PATH + 
                ": " + e.getMessage(), e);
        }
    }

    /**
     * Get broker info by ID from config
     */
    public StorageServiceInfo getBrokerById(Integer brokerId) {
        if (topology == null || topology.getStorageServices() == null) {
            return null;
        }
        
        return topology.getStorageServices().stream()
            .filter(broker -> broker.getId().equals(brokerId))
            .findFirst()
            .orElse(null);
    }

    /**
     * Get all storage services (brokers) from config
     */
    public List<StorageServiceInfo> getBrokers() {
        return topology != null && topology.getStorageServices() != null 
            ? topology.getStorageServices() 
            : List.of();
    }

    /**
     * Get all metadata services from config
     */
    public List<MetadataServiceInfo> getMetadataServices() {
        return topology != null && topology.getMetadataServices() != null 
            ? topology.getMetadataServices() 
            : List.of();
    }

    /**
     * Get primary metadata service (first one in the list)
     */
    public MetadataServiceInfo getPrimaryMetadataService() {
        List<MetadataServiceInfo> services = getMetadataServices();
        return services.isEmpty() ? null : services.get(0);
    }

    // Inner classes matching the JSON structure

    @Data
    private static class ServicesConfig {
        private TopologyData services;
        private ControllerConfig controller;
        private MetadataConfig metadata;
        private JwtConfig jwt;  // JWT configuration section
    }

    @Data
    public static class TopologyData {
        @JsonProperty("metadata-services")
        private List<MetadataServiceInfo> metadataServices;
        
        @JsonProperty("storage-services")
        private List<StorageServiceInfo> storageServices;
    }

    @Data
    public static class MetadataServiceInfo {
        private Integer id;
        private String host;
        private Integer port;
        private String url;
    }

    @Data
    public static class StorageServiceInfo {
        private Integer id;
        private String host;
        private Integer port;
        private String url;
        private Integer pairedMetadataServiceId;
    }

    @Data
    private static class ControllerConfig {
        private Long electionTimeoutMs;
    }

    @Data
    private static class MetadataConfig {
        private SyncConfig sync;
    }

    @Data
    private static class SyncConfig {
        private Long syncTimeoutMs;
    }
    
    @Data
    private static class JwtConfig {
        private String secret;
        private String algorithm;
        @JsonProperty("access-token-expiry-seconds")
        private Long accessTokenExpirySeconds;
    }
}
