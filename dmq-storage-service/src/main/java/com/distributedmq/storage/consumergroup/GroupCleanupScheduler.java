package com.distributedmq.storage.consumergroup;

import com.distributedmq.common.config.ServiceDiscovery;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;

/**
 * Background job to cleanup empty consumer groups
 * Runs every 30 seconds and deletes groups with 0 members
 * Also notifies metadata service to remove group entries
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GroupCleanupScheduler {
    
    private final ConsumerGroupManager groupManager;
    private final RestTemplate restTemplate;
    
    /**
     * Cleanup empty groups every 30 seconds
     */
    @Scheduled(fixedDelay = 30000, initialDelay = 30000)
    public void cleanupEmptyGroups() {
        try {
            List<String> deletedGroups = groupManager.cleanupEmptyGroups();
            
            if (!deletedGroups.isEmpty()) {
                log.info("Cleaned up {} empty consumer groups: {}", 
                         deletedGroups.size(), deletedGroups);
                
                // Notify metadata service to delete these groups
                notifyMetadataService(deletedGroups);
            }
            
        } catch (Exception e) {
            log.error("Error during group cleanup", e);
        }
    }
    
    /**
     * Notify metadata service to delete groups
     */
    private void notifyMetadataService(List<String> groupIds) {
        try {
            // Get active controller URL
            String controllerUrl = discoverController();
            if (controllerUrl == null) {
                log.warn("Cannot notify metadata service - controller not found");
                return;
            }
            
            // Delete each group from metadata service
            for (String groupId : groupIds) {
                try {
                    String url = controllerUrl + "/api/v1/consumer-groups/" + groupId;
                    restTemplate.delete(url);
                    log.info("Deleted group {} from metadata service", groupId);
                } catch (Exception e) {
                    log.warn("Failed to delete group {} from metadata service", groupId, e);
                }
            }
            
        } catch (Exception e) {
            log.error("Error notifying metadata service about deleted groups", e);
        }
    }
    
    /**
     * Discover active controller from metadata services
     */
    private String discoverController() {
        try {
            ServiceDiscovery.loadConfig();
            List<ServiceDiscovery.ServiceInfo> metadataServices = 
                    ServiceDiscovery.getAllMetadataServices();
            
            for (ServiceDiscovery.ServiceInfo service : metadataServices) {
                try {
                    String url = service.getUrl() + "/api/v1/controller/status";
                    var response = restTemplate.getForObject(url, String.class);
                    if (response != null && response.contains("LEADER")) {
                        return service.getUrl();
                    }
                } catch (Exception e) {
                    // Try next service
                }
            }
            
        } catch (Exception e) {
            log.error("Error discovering controller", e);
        }
        
        return null;
    }
}
