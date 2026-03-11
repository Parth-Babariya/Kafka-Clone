package com.distributedmq.storage.controller;

import com.distributedmq.common.constants.ConsumerGroupErrorCodes;
import com.distributedmq.common.dto.ConsumerGroupOperationResponse;
import com.distributedmq.common.dto.ConsumerGroupState;
import com.distributedmq.common.dto.ConsumerHeartbeatRequest;
import com.distributedmq.common.dto.ConsumerJoinRequest;
import com.distributedmq.common.security.JwtException;
import com.distributedmq.common.security.JwtValidator;
import com.distributedmq.common.security.UserPrincipal;
import com.distributedmq.storage.consumergroup.ConsumerGroupManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;

/**
 * REST API for consumer group coordination
 * Handles consumer join, heartbeat, and assignment requests
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/consumer-groups")
@RequiredArgsConstructor
public class ConsumerGroupController {
    
    private final ConsumerGroupManager groupManager;
    private final JwtValidator jwtValidator;
    
    /**
     * Consumer join group request
     * POST /api/v1/consumer-groups/join
     */
    @PostMapping("/join")
    public ResponseEntity<ConsumerGroupOperationResponse> joinGroup(
            @RequestBody ConsumerJoinRequest request,
            HttpServletRequest httpRequest) {
        
        try {
            // JWT Authentication & Authorization
            try {
                UserPrincipal user = jwtValidator.validateRequest(httpRequest);
                if (!jwtValidator.hasAnyRole(user, "CONSUMER", "ADMIN")) {
                    log.warn("User {} lacks CONSUMER/ADMIN role for consumer group join", user.getUsername());
                    return ResponseEntity.status(403).build();
                }
            } catch (JwtException e) {
                log.warn("JWT validation failed: {}", e.getMessage());
                return ResponseEntity.status(401).build();
            }
            
            log.info("Consumer {} requesting to join group {}", 
                     request.getConsumerId(), request.getGroupId());
            
            // Validate request
            if (request.getConsumerId() == null || request.getGroupId() == null) {
                return ResponseEntity.badRequest().body(
                        ConsumerGroupOperationResponse.error(
                                ConsumerGroupErrorCodes.INVALID_REQUEST,
                                "consumerId and groupId are required"));
            }
            
            ConsumerGroupOperationResponse response = groupManager.handleJoin(request);
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("Error handling join request", e);
            return ResponseEntity.internalServerError().body(
                    ConsumerGroupOperationResponse.error(
                            ConsumerGroupErrorCodes.INTERNAL_ERROR,
                            e.getMessage()));
        }
    }
    
    /**
     * Consumer heartbeat/commit request
     * POST /api/v1/consumer-groups/heartbeat
     * 
     * NOTE: No JWT validation - heartbeats are internal lifecycle operations
     * after the consumer has already authenticated during join. Validating JWT
     * on every heartbeat (every 3 seconds) would cause performance issues and
     * token expiry problems.
     */
    @PostMapping("/heartbeat")
    public ResponseEntity<ConsumerGroupOperationResponse> heartbeat(
            @RequestBody ConsumerHeartbeatRequest request) {
        
        try {
            log.debug("Heartbeat from consumer {} in group {}", 
                      request.getConsumerId(), request.getGroupId());
            
            // Validate request
            if (request.getConsumerId() == null || request.getGroupId() == null) {
                return ResponseEntity.badRequest().body(
                        ConsumerGroupOperationResponse.error(
                                ConsumerGroupErrorCodes.INVALID_REQUEST,
                                "consumerId and groupId are required"));
            }
            
            ConsumerGroupOperationResponse response = groupManager.handleHeartbeat(request);
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("Error handling heartbeat request", e);
            return ResponseEntity.internalServerError().body(
                    ConsumerGroupOperationResponse.error(
                            ConsumerGroupErrorCodes.INTERNAL_ERROR,
                            e.getMessage()));
        }
    }
    
    /**
     * Get group state (for debugging/monitoring)
     * GET /api/v1/consumer-groups/{groupId}
     */
    @GetMapping("/{groupId}")
    public ResponseEntity<ConsumerGroupState> getGroupState(
            @PathVariable String groupId,
            HttpServletRequest httpRequest) {
        
        // JWT Authentication (any authenticated user can read)
        try {
            jwtValidator.validateRequest(httpRequest);
        } catch (JwtException e) {
            log.warn("JWT validation failed: {}", e.getMessage());
            return ResponseEntity.status(401).build();
        }
        
        try {
            ConsumerGroupState state = groupManager.getGroupState(groupId);
            if (state == null) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(state);
        } catch (Exception e) {
            log.error("Error getting group state for {}", groupId, e);
            return ResponseEntity.internalServerError().build();
        }
    }
}
