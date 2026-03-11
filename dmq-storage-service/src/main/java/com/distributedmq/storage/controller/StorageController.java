package com.distributedmq.storage.controller;

import com.distributedmq.common.dto.ConsumeRequest;
import com.distributedmq.common.dto.ConsumeResponse;
import com.distributedmq.common.dto.ProduceRequest;
import com.distributedmq.common.dto.ProduceResponse;
import com.distributedmq.common.dto.ReplicationRequest;
import com.distributedmq.common.dto.ReplicationResponse;
import com.distributedmq.common.dto.MetadataUpdateRequest;
import com.distributedmq.common.dto.MetadataUpdateResponse;
import com.distributedmq.storage.config.StorageConfig;
import com.distributedmq.storage.config.StorageConfig;
import com.distributedmq.storage.replication.MetadataStore;
import com.distributedmq.storage.replication.ReplicationManager;
import com.distributedmq.common.security.JwtException;
import com.distributedmq.common.security.JwtValidator;
import com.distributedmq.common.security.UserPrincipal;
import com.distributedmq.storage.service.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;

/**
 * REST Controller for Storage operations
 * Entry point with request validation
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/storage")
@RequiredArgsConstructor
public class StorageController {

    private final StorageService storageService;
    private final ReplicationManager replicationManager;
    private final StorageConfig config;
    private final MetadataStore metadataStore;
    private final JwtValidator jwtValidator;
    /**
     * HealthCheck
     * Endpoint: GET /api/v1/storage/health
     */
    @GetMapping("/health")
    public ResponseEntity<String> healthCheck() {
        return ResponseEntity.ok("Storage Service is up and running");
    }

    /**
     * Produce messages to partition (leader only)
     * Endpoint: POST /api/v1/storage/messages
     */
    @PostMapping("/messages")
    public ResponseEntity<ProduceResponse> produceMessages(
            @Validated @RequestBody ProduceRequest request,
            HttpServletRequest httpRequest) {
        
        log.info("Received produce request for topic: {}, partition: {}, messageCount: {}", 
                request.getTopic(), request.getPartition(), 
                request.getMessages() != null ? request.getMessages().size() : 0);
        
        // Step 1: Broker Reception & Validation
        ProduceResponse.ErrorCode validationError = validateProduceRequest(request, httpRequest);
        if (validationError != ProduceResponse.ErrorCode.NONE) {
            return ResponseEntity.ok(ProduceResponse.builder()
                    .topic(request.getTopic())
                    .partition(request.getPartition())
                    .success(false)
                    .errorCode(validationError)
                    .errorMessage(validationError.getMessage())
                    .build());
        }
        
        // Check if this broker is the partition leader
        if (!storageService.isLeaderForPartition(request.getTopic(), request.getPartition())) {
            return ResponseEntity.ok(ProduceResponse.builder()
                    .topic(request.getTopic())
                    .partition(request.getPartition())
                    .success(false)
                    .errorCode(ProduceResponse.ErrorCode.NOT_LEADER_FOR_PARTITION)
                    .errorMessage("Not leader for partition")
                    .build());
        }
        
        // TODO: Validate producer ID and epoch for idempotent producers
        ProduceResponse.ErrorCode producerValidationError = validateProducerIdAndEpoch(request);
        if (producerValidationError != ProduceResponse.ErrorCode.NONE) {
            return ResponseEntity.ok(ProduceResponse.builder()
                    .topic(request.getTopic())
                    .partition(request.getPartition())
                    .success(false)
                    .errorCode(producerValidationError)
                    .errorMessage(producerValidationError.getMessage())
                    .build());
        }
        
        try {
            ProduceResponse response = storageService.appendMessages(request);
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("Error processing produce request", e);
            
            return ResponseEntity.ok(ProduceResponse.builder()
                    .topic(request.getTopic())
                    .partition(request.getPartition())
                    .success(false)
                    .errorCode(ProduceResponse.ErrorCode.INVALID_REQUEST)
                    .errorMessage("Internal server error: " + e.getMessage())
                    .build());
        }
    }

    /**
     * Fetch messages from partition
     */
    @PostMapping("/consume")
    public ResponseEntity<ConsumeResponse> consume(
            @Validated @RequestBody ConsumeRequest request,
            HttpServletRequest httpRequest) {
        
        log.debug("Received consume request for topic: {}, partition: {}, offset: {}", 
                request.getTopic(), request.getPartition(), request.getOffset());
        
        // JWT Authentication & Authorization
        try {
            UserPrincipal user = jwtValidator.validateRequest(httpRequest);
            if (!jwtValidator.hasAnyRole(user, "CONSUMER", "ADMIN")) {
                log.warn("User {} lacks CONSUMER/ADMIN role", user.getUsername());
                return ResponseEntity.status(403).build();
            }
        } catch (JwtException e) {
            log.warn("JWT validation failed: {}", e.getMessage());
            return ResponseEntity.status(401).build();
        }
        
        // Sanity checks
        if (request.getTopic() == null || request.getTopic().isEmpty()) {
            throw new IllegalArgumentException("Topic cannot be empty");
        }
        
        if (request.getOffset() < 0) {
            throw new IllegalArgumentException("Offset must be non-negative");
        }
        
        ConsumeResponse response = storageService.fetch(request);
        
        return ResponseEntity.ok(response);
    }

    /**
     * Get partition high water mark
     */
    @GetMapping("/partitions/{topic}/{partition}/high-water-mark")
    public ResponseEntity<Long> getHighWaterMark(
            @PathVariable String topic,
            @PathVariable Integer partition) {
        
        log.debug("Getting high water mark for topic: {}, partition: {}", topic, partition);
        
        Long highWaterMark = storageService.getHighWaterMark(topic, partition);
        
        return ResponseEntity.ok(highWaterMark);
    }

    /**
     * Validate produce request including JWT authentication
     */
    private ProduceResponse.ErrorCode validateProduceRequest(ProduceRequest request, HttpServletRequest httpRequest) {
        // JWT Authentication & Authorization
        try {
            UserPrincipal user = jwtValidator.validateRequest(httpRequest);
            if (!jwtValidator.hasAnyRole(user, "PRODUCER", "ADMIN")) {
                log.warn("User {} lacks PRODUCER/ADMIN role", user.getUsername());
                return ProduceResponse.ErrorCode.UNAUTHORIZED;
            }
        } catch (JwtException e) {
            log.warn("JWT validation failed: {}", e.getMessage());
            return ProduceResponse.ErrorCode.UNAUTHORIZED;
        }
        
        if (request.getTopic() == null || request.getTopic().isEmpty()) {
            return ProduceResponse.ErrorCode.INVALID_REQUEST;
        }
        
        if (request.getPartition() == null || request.getPartition() < 0) {
            return ProduceResponse.ErrorCode.INVALID_REQUEST;
        }
        
        if (request.getMessages() == null || request.getMessages().isEmpty()) {
            return ProduceResponse.ErrorCode.INVALID_REQUEST;
        }
        
        // Validate each message
        for (ProduceRequest.ProduceMessage message : request.getMessages()) {
            if (message.getValue() == null || message.getValue().length == 0) {
                return ProduceResponse.ErrorCode.INVALID_REQUEST;
            }
            
            // TODO: Check message size limits
            // 1. Check individual message size against max.message.bytes
            // 2. Check total batch size against max.request.size
            // 3. Return MESSAGE_TOO_LARGE error if exceeded
            // 4. Log size violation for monitoring

        }
        
        // Validate acks
        if (request.getRequiredAcks() != null && 
            request.getRequiredAcks() != StorageConfig.ACKS_NONE && 
            request.getRequiredAcks() != StorageConfig.ACKS_LEADER && 
            request.getRequiredAcks() != StorageConfig.ACKS_ALL) {
            return ProduceResponse.ErrorCode.INVALID_REQUEST;
        }

        return ProduceResponse.ErrorCode.NONE;
    }

    /**
     * Validate producer ID and epoch for idempotent producers
     */
    private ProduceResponse.ErrorCode validateProducerIdAndEpoch(ProduceRequest request) {
        // TODO: Validate producer ID and epoch for idempotent producers
        // 1. Check if producerId is provided (non-null)
        // 2. Query stored producer state for last epoch/sequence
        // 3. Validate sequence number is monotonically increasing
        // 4. Return OUT_OF_ORDER_SEQUENCE error if invalid or handle it as needed.

        // For now, return NONE (no validation implemented yet)
        return ProduceResponse.ErrorCode.NONE;
    }

    // TODO: Add remaining replication endpoints and features
    //  GET /replicate/status	Leader asking, “How much have you processed?”
    // POST /replicate/ack	Follower reporting, “I’ve stored up to here!”
    // Leader epoch validation	Follower asking, “Are you still the valid leader?”
    // ISR + lag detection	“You’re too slow — you're off the team (temporarily).”
    // Metadata sync	Everyone updating their records about who’s leader now
    // Replication timeout + retry	Retry delivery if confirmation doesn’t come in time

    /**
     * Receive replication requests from leader (follower endpoint)
     * Endpoint: POST /api/v1/storage/replicate
     */
    @PostMapping("/replicate")
    public ResponseEntity<ReplicationResponse> replicateMessages(
            @Validated @RequestBody ReplicationRequest request) {

        log.info("Received replication request from leader {} for topic: {}, partition: {}, messageCount: {}",
                request.getLeaderId(), request.getTopic(), request.getPartition(),
                request.getMessages() != null ? request.getMessages().size() : 0);

        try {
            ReplicationResponse response = replicationManager.processReplicationRequest(request, storageService);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Error processing replication request", e);

            return ResponseEntity.ok(ReplicationResponse.builder()
                    .topic(request.getTopic())
                    .partition(request.getPartition())
                    .followerId(config.getBroker().getId()) // Get from config
                    .success(false)
                    .errorCode(ReplicationResponse.ErrorCode.STORAGE_ERROR)
                    .errorMessage("Internal server error: " + e.getMessage())
                    .build());
        }
    }

    /**
     * Update metadata from metadata service (push model)
     * Endpoint: POST /api/v1/storage/metadata
     */
    @PostMapping("/metadata")
    public ResponseEntity<MetadataUpdateResponse> updateMetadata(
            @Validated @RequestBody MetadataUpdateRequest request) {

        log.info("Received metadata update from metadata service with {} brokers and {} partitions",
                request.getBrokers() != null ? request.getBrokers().size() : 0,
                request.getPartitions() != null ? request.getPartitions().size() : 0);

        try {
            // Update metadata in MetadataStore
            metadataStore.updateMetadata(request);

            return ResponseEntity.ok(MetadataUpdateResponse.builder()
                    .success(true)
                    .errorCode(MetadataUpdateResponse.ErrorCode.NONE)
                    .processedTimestamp(System.currentTimeMillis())
                    .brokerId(config.getBroker().getId())
                    .build());

        } catch (Exception e) {
            log.error("Error processing metadata update", e);

            return ResponseEntity.ok(MetadataUpdateResponse.builder()
                    .success(false)
                    .errorCode(MetadataUpdateResponse.ErrorCode.PROCESSING_ERROR)
                    .errorMessage("Failed to update metadata: " + e.getMessage())
                    .processedTimestamp(System.currentTimeMillis())
                    .brokerId(config.getBroker().getId())
                    .build());
        }
    }

    // TODO: Add endpoint to request metadata from metadata service (pull model)
    // This will be implemented when metadata service is available
    // GET /api/v1/storage/metadata/refresh - Request latest metadata from metadata service

    // TODO: Add partition management endpoints
    // 1. POST /api/v1/storage/partitions - Create new partition
    // 2. DELETE /api/v1/storage/partitions/{topic}/{partition} - Delete partition
    // 3. GET /api/v1/storage/partitions/{topic} - List topic partitions
    // 4. POST /api/v1/storage/partitions/leader - Update partition leadership
}
