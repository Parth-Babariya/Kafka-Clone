package com.distributedmq.storage.config;

import com.distributedmq.storage.heartbeat.HeartbeatSender;
import com.distributedmq.storage.replication.MetadataStore;
import com.distributedmq.storage.service.ControllerDiscoveryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/**
 * Configuration for storage service components
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class StorageServiceConfig {

    private final StorageConfig storageConfig;
    private final ClusterTopologyConfig clusterTopologyConfig;

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }

    @Bean
    public MetadataStore metadataStore() {
        MetadataStore metadataStore = new MetadataStore();
        
        Integer brokerId = storageConfig.getBroker().getId();
        metadataStore.setLocalBrokerId(brokerId);
        
        // Get broker configuration from config/services.json
        ClusterTopologyConfig.StorageServiceInfo brokerInfo = 
            clusterTopologyConfig.getBrokerById(brokerId);
        
        if (brokerInfo != null) {
            // Use configuration from services.json
            metadataStore.setLocalBrokerHost(brokerInfo.getHost());
            metadataStore.setLocalBrokerPort(brokerInfo.getPort());
            log.info("Loaded broker {} configuration from services.json: {}:{}", 
                brokerId, brokerInfo.getHost(), brokerInfo.getPort());
        } else {
            // Fallback to StorageConfig values
            log.warn("Broker {} not found in services.json, using fallback configuration", brokerId);
            metadataStore.setLocalBrokerHost(storageConfig.getBroker().getHost());
            metadataStore.setLocalBrokerPort(storageConfig.getBroker().getPort());
        }

        // NOTE: Do NOT call registerWithMetadataService() or pullInitialMetadata() here
        // These will be called by HeartbeatSender.init() after controller discovery
        
        log.info("MetadataStore bean created for broker {}, deferring registration until controller discovery", brokerId);

        return metadataStore;
    }

    /**
     * Heartbeat Sender Bean
     * Automatically discovers controller and sends heartbeats every 5 seconds
     * Also performs metadata version checking and periodic refresh
     */
    @Bean
    public HeartbeatSender heartbeatSender(RestTemplate restTemplate, MetadataStore metadataStore, 
                                          ControllerDiscoveryService controllerDiscoveryService) {
        Integer brokerId = storageConfig.getBroker().getId();
        
        HeartbeatSender sender = new HeartbeatSender(brokerId, restTemplate, metadataStore, controllerDiscoveryService);
        log.info("Heartbeat sender configured for broker {} with dynamic controller discovery", brokerId);
        
        return sender;
    }
}