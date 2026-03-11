package com.distributedmq.metadata.service;

import com.distributedmq.common.dto.MetadataUpdateRequest;
import com.distributedmq.common.dto.MetadataUpdateResponse;
import com.distributedmq.metadata.config.ServicePairingConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.RestClientException;

/**
 * HTTP Client for communicating with paired storage nodes
 * Handles push and pull operations for metadata synchronization
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StorageNodeClient {

    private final RestTemplate restTemplate;
    private final ServicePairingConfig servicePairingConfig;

    /**
     * Push metadata update to the paired storage node
     */
    public MetadataUpdateResponse pushMetadata(MetadataUpdateRequest request) {
        String storageUrl = servicePairingConfig.getStorageNode().getUrl();
        String endpoint = storageUrl + "/api/v1/storage/metadata";

        log.info("Pushing metadata update to storage node: {} (brokerId: {})",
                storageUrl, servicePairingConfig.getStorageNode().getBrokerId());

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            HttpEntity<MetadataUpdateRequest> entity = new HttpEntity<>(request, headers);

            ResponseEntity<MetadataUpdateResponse> response = restTemplate.exchange(
                    endpoint,
                    HttpMethod.POST,
                    entity,
                    MetadataUpdateResponse.class
            );

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                log.info("Successfully pushed metadata to storage node. Response: success={}, brokerId={}",
                        response.getBody().isSuccess(), response.getBody().getBrokerId());
                return response.getBody();
            } else {
                log.warn("Failed to push metadata to storage node. Status: {}", response.getStatusCode());
                return MetadataUpdateResponse.builder()
                        .success(false)
                        .errorCode(MetadataUpdateResponse.ErrorCode.PROCESSING_ERROR)
                        .errorMessage("HTTP status: " + response.getStatusCode())
                        .processedTimestamp(System.currentTimeMillis())
                        .brokerId(servicePairingConfig.getStorageNode().getBrokerId())
                        .build();
            }

        } catch (RestClientException e) {
            log.error("Network error while pushing metadata to storage node: {}", storageUrl, e);
            return MetadataUpdateResponse.builder()
                    .success(false)
                    .errorCode(MetadataUpdateResponse.ErrorCode.PROCESSING_ERROR)
                    .errorMessage("Network error: " + e.getMessage())
                    .processedTimestamp(System.currentTimeMillis())
                    .brokerId(servicePairingConfig.getStorageNode().getBrokerId())
                    .build();
        } catch (Exception e) {
            log.error("Unexpected error while pushing metadata to storage node", e);
            return MetadataUpdateResponse.builder()
                    .success(false)
                    .errorCode(MetadataUpdateResponse.ErrorCode.PROCESSING_ERROR)
                    .errorMessage("Unexpected error: " + e.getMessage())
                    .processedTimestamp(System.currentTimeMillis())
                    .brokerId(servicePairingConfig.getStorageNode().getBrokerId())
                    .build();
        }
    }

    /**
     * Push metadata update to a specific storage node URL
     * Useful for controller operations that need to push to multiple nodes
     */
    public MetadataUpdateResponse pushMetadataToUrl(String storageNodeUrl, MetadataUpdateRequest request) {
        String endpoint = storageNodeUrl + "/api/v1/storage/metadata";

        log.debug("Pushing metadata update to storage node: {}", storageNodeUrl);

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            HttpEntity<MetadataUpdateRequest> entity = new HttpEntity<>(request, headers);

            ResponseEntity<MetadataUpdateResponse> response = restTemplate.exchange(
                    endpoint,
                    HttpMethod.POST,
                    entity,
                    MetadataUpdateResponse.class
            );

            return response.getBody();

        } catch (RestClientException e) {
            log.error("Network error while pushing metadata to: {}", storageNodeUrl, e);
            return MetadataUpdateResponse.builder()
                    .success(false)
                    .errorCode(MetadataUpdateResponse.ErrorCode.PROCESSING_ERROR)
                    .errorMessage("Network error: " + e.getMessage())
                    .processedTimestamp(System.currentTimeMillis())
                    .build();
        } catch (Exception e) {
            log.error("Unexpected error while pushing metadata to: {}", storageNodeUrl, e);
            return MetadataUpdateResponse.builder()
                    .success(false)
                    .errorCode(MetadataUpdateResponse.ErrorCode.PROCESSING_ERROR)
                    .errorMessage("Unexpected error: " + e.getMessage())
                    .processedTimestamp(System.currentTimeMillis())
                    .build();
        }
    }

    /**
     * Pull metadata from the paired storage node
     * Used for bootstrap or recovery scenarios
     */
    public MetadataUpdateResponse pullMetadata() {
        String storageUrl = servicePairingConfig.getStorageNode().getUrl();
        String endpoint = storageUrl + "/api/v1/storage/metadata";

        log.info("Pulling metadata from storage node: {} (brokerId: {})",
                storageUrl, servicePairingConfig.getStorageNode().getBrokerId());

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            HttpEntity<Void> entity = new HttpEntity<>(headers);

            ResponseEntity<MetadataUpdateResponse> response = restTemplate.exchange(
                    endpoint,
                    HttpMethod.GET,
                    entity,
                    MetadataUpdateResponse.class
            );

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                log.info("Successfully pulled metadata from storage node. Response: success={}, brokerId={}",
                        response.getBody().isSuccess(), response.getBody().getBrokerId());
                return response.getBody();
            } else {
                log.warn("Failed to pull metadata from storage node. Status: {}", response.getStatusCode());
                return MetadataUpdateResponse.builder()
                        .success(false)
                        .errorCode(MetadataUpdateResponse.ErrorCode.PROCESSING_ERROR)
                        .errorMessage("HTTP status: " + response.getStatusCode())
                        .processedTimestamp(System.currentTimeMillis())
                        .brokerId(servicePairingConfig.getStorageNode().getBrokerId())
                        .build();
            }

        } catch (RestClientException e) {
            log.error("Network error while pulling metadata from storage node: {}", storageUrl, e);
            return MetadataUpdateResponse.builder()
                    .success(false)
                    .errorCode(MetadataUpdateResponse.ErrorCode.PROCESSING_ERROR)
                    .errorMessage("Network error: " + e.getMessage())
                    .processedTimestamp(System.currentTimeMillis())
                    .brokerId(servicePairingConfig.getStorageNode().getBrokerId())
                    .build();
        } catch (Exception e) {
            log.error("Unexpected error while pulling metadata from storage node", e);
            return MetadataUpdateResponse.builder()
                    .success(false)
                    .errorCode(MetadataUpdateResponse.ErrorCode.PROCESSING_ERROR)
                    .errorMessage("Unexpected error: " + e.getMessage())
                    .processedTimestamp(System.currentTimeMillis())
                    .brokerId(servicePairingConfig.getStorageNode().getBrokerId())
                    .build();
        }
    }

    /**
     * Get the paired storage node URL
     */
    public String getPairedStorageNodeUrl() {
        return servicePairingConfig.getStorageNode().getUrl();
    }

    /**
     * Get the paired storage node broker ID
     */
    public Integer getPairedStorageNodeBrokerId() {
        return servicePairingConfig.getStorageNode().getBrokerId();
    }
}