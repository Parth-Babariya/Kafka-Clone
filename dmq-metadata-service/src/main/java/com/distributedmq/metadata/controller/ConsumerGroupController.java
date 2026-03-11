package com.distributedmq.metadata.controller;

import com.distributedmq.common.dto.ConsumerGroupResponse;
import com.distributedmq.common.dto.FindGroupRequest;
import com.distributedmq.common.security.JwtException;
import com.distributedmq.common.security.JwtValidator;
import com.distributedmq.common.security.UserPrincipal;
import com.distributedmq.metadata.service.ConsumerGroupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;

/**
 * REST Controller for Consumer Group operations
 * Handles minimal consumer group registry operations
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/metadata/consumer-groups")
@RequiredArgsConstructor
public class ConsumerGroupController {

    private final ConsumerGroupService consumerGroupService;
    private final JwtValidator jwtValidator;

    /**
     * Find existing consumer group or create new one
     * Called by consumers via bootstrap metadata service
     * 
     * POST /api/v1/metadata/consumer-groups/find-or-create
     * Request: { "topic": "orders", "appId": "order-processor" }
     * Response: { "groupId": "G_orders_order-processor", "groupLeaderUrl": "localhost:8081", ... }
     */
    @PostMapping("/find-or-create")
    public ResponseEntity<ConsumerGroupResponse> findOrCreateGroup(
            @RequestBody FindGroupRequest request,
            HttpServletRequest httpRequest) {
        
        // JWT Authentication & Authorization
        try {
            UserPrincipal user = jwtValidator.validateRequest(httpRequest);
            if (!jwtValidator.hasAnyRole(user, "CONSUMER", "ADMIN")) {
                log.warn("User {} lacks CONSUMER/ADMIN role for consumer group discovery", user.getUsername());
                return ResponseEntity.status(403).build();
            }
        } catch (JwtException e) {
            log.warn("JWT validation failed: {}", e.getMessage());
            return ResponseEntity.status(401).build();
        }
        
        log.info("Find or create consumer group request: topic={}, appId={}", request.getTopic(), request.getAppId());

        try {
            // Validate request
            if (request.getTopic() == null || request.getTopic().trim().isEmpty()) {
                return ResponseEntity.badRequest().build();
            }
            if (request.getAppId() == null || request.getAppId().trim().isEmpty()) {
                return ResponseEntity.badRequest().build();
            }

            ConsumerGroupResponse response = consumerGroupService.findOrCreateGroup(
                    request.getTopic(),
                    request.getAppId()
            );

            log.info("Consumer group response: groupId={}, leader=broker-{}", 
                     response.getGroupId(), response.getGroupLeaderBrokerId());

            return ResponseEntity.ok(response);

        } catch (IllegalStateException e) {
            // Not the active controller
            log.warn("Cannot process consumer group request: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        } catch (Exception e) {
            log.error("Error finding/creating consumer group", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Delete consumer group
     * Called by group leader broker when the last member leaves
     * 
     * DELETE /api/v1/metadata/consumer-groups/{groupId}?brokerId=1
     */
    @DeleteMapping("/{groupId}")
    public ResponseEntity<Void> deleteGroup(
            @PathVariable String groupId,
            @RequestParam Integer brokerId) {
        
        log.info("Delete consumer group request: groupId={}, requestingBroker={}", groupId, brokerId);

        try {
            consumerGroupService.deleteGroup(groupId, brokerId);
            log.info("Consumer group {} deleted successfully", groupId);
            return ResponseEntity.ok().build();

        } catch (IllegalStateException e) {
            // Not the active controller or not the group leader
            log.warn("Cannot delete consumer group: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        } catch (IllegalArgumentException e) {
            // Group not found
            log.warn("Consumer group not found: {}", groupId);
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            log.error("Error deleting consumer group: {}", groupId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Reassign group leader to a new broker
     * Called internally when a group leader broker fails
     * 
     * POST /api/v1/metadata/consumer-groups/{groupId}/reassign-leader?newLeaderBrokerId=2
     */
    @PostMapping("/{groupId}/reassign-leader")
    public ResponseEntity<Void> reassignLeader(
            @PathVariable String groupId,
            @RequestParam Integer newLeaderBrokerId) {
        
        log.info("Reassign leader request: groupId={}, newLeader=broker-{}", groupId, newLeaderBrokerId);

        try {
            consumerGroupService.updateGroupLeader(groupId, newLeaderBrokerId);
            log.info("Consumer group {} leader reassigned to broker-{}", groupId, newLeaderBrokerId);
            return ResponseEntity.ok().build();

        } catch (IllegalStateException e) {
            // Not the active controller or broker not online
            log.warn("Cannot reassign group leader: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        } catch (IllegalArgumentException e) {
            // Group not found or broker not found
            log.warn("Cannot reassign group leader: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        } catch (Exception e) {
            log.error("Error reassigning group leader for: {}", groupId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Get consumer group information by group ID
     * 
     * GET /api/v1/metadata/consumer-groups/{groupId}
     */
    @GetMapping("/{groupId}")
    public ResponseEntity<ConsumerGroupResponse> getGroup(
            @PathVariable String groupId,
            HttpServletRequest httpRequest) {
        
        // JWT Authentication (any authenticated user can read)
        try {
            jwtValidator.validateRequest(httpRequest);
        } catch (JwtException e) {
            log.warn("JWT validation failed: {}", e.getMessage());
            return ResponseEntity.status(401).build();
        }
        
        log.debug("Get consumer group request: groupId={}", groupId);

        try {
            ConsumerGroupResponse response = consumerGroupService.getGroupById(groupId);
            
            if (response == null) {
                return ResponseEntity.notFound().build();
            }

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Error getting consumer group: {}", groupId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * List all consumer groups
     * 
     * GET /api/v1/metadata/consumer-groups
     */
    @GetMapping
    public ResponseEntity<java.util.List<ConsumerGroupResponse>> listGroups(HttpServletRequest httpRequest) {
        
        // JWT Authentication (any authenticated user can read)
        try {
            jwtValidator.validateRequest(httpRequest);
        } catch (JwtException e) {
            log.warn("JWT validation failed: {}", e.getMessage());
            return ResponseEntity.status(401).build();
        }
        
        log.debug("List all consumer groups request");

        try {
            java.util.List<ConsumerGroupResponse> groups = consumerGroupService.getAllGroups();
            return ResponseEntity.ok(groups);

        } catch (Exception e) {
            log.error("Error listing consumer groups", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
}
