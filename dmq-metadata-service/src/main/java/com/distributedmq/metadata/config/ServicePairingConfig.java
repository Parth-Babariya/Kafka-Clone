package com.distributedmq.metadata.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration for service pairing between metadata and storage nodes
 */
@Component
@ConfigurationProperties(prefix = "dmq.service-pairing")
@Data
public class ServicePairingConfig {

    private StorageNodeConfig storageNode;

    @Data
    public static class StorageNodeConfig {
        /**
         * URL of the paired storage node
         * Example: http://localhost:8081
         */
        private String url;

        /**
         * Broker ID of the paired storage node
         */
        private Integer brokerId;
    }
}