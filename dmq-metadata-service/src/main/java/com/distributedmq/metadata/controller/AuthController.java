package com.distributedmq.metadata.controller;

import com.distributedmq.metadata.coordination.RaftController;
import com.distributedmq.metadata.dto.LoginRequest;
import com.distributedmq.metadata.dto.LoginResponse;
import com.distributedmq.metadata.service.AuthService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Authentication controller
 * Only Raft leader can issue JWT tokens
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {
    
    private final AuthService authService;
    private final RaftController raftController;
    
    /**
     * Login endpoint - generate JWT token
     * Only leader can issue tokens to maintain consistency
     */
    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@RequestBody LoginRequest request) {
        log.info("Login request for user: {}", request.getUsername());
        
        // Only leader issues tokens
        if (!raftController.isControllerLeader()) {
            Integer leaderId = raftController.getControllerLeaderId();
            log.warn("Not leader (current leader: {}), rejecting login request", leaderId);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .header("X-Leader-Id", leaderId != null ? leaderId.toString() : "unknown")
                    .header("X-Error-Message", "Only controller leader can issue tokens. Redirect to leader.")
                    .build();
        }
        
        try {
            LoginResponse response = authService.authenticate(
                request.getUsername(), 
                request.getPassword()
            );
            log.info("Login successful for user: {}", request.getUsername());
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.warn("Authentication failed for user: {} - {}", request.getUsername(), e.getMessage());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .header("X-Error-Message", "Invalid credentials")
                    .build();
        }
    }
    
    /**
     * Refresh token endpoint - issues new JWT token for authenticated user
     * Requires valid (or recently expired) JWT token in Authorization header
     * Only leader can issue tokens
     */
    @PostMapping("/refresh")
    public ResponseEntity<LoginResponse> refresh(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        log.info("Token refresh request");
        
        // Only leader issues tokens
        if (!raftController.isControllerLeader()) {
            Integer leaderId = raftController.getControllerLeaderId();
            log.warn("Not leader (current leader: {}), rejecting refresh request", leaderId);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .header("X-Leader-Id", leaderId != null ? leaderId.toString() : "unknown")
                    .header("X-Error-Message", "Only controller leader can issue tokens. Redirect to leader.")
                    .build();
        }
        
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            log.warn("Token refresh failed: Missing or invalid Authorization header");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .header("X-Error-Message", "Missing or invalid Authorization header")
                    .build();
        }
        
        String token = authHeader.substring(7);
        
        try {
            LoginResponse response = authService.refreshToken(token);
            log.info("Token refreshed successfully for user: {}", response.getUsername());
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.warn("Token refresh failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .header("X-Error-Message", e.getMessage())
                    .build();
        }
    }
    
    /**
     * Health check endpoint for auth service
     */
    @GetMapping("/health")
    public ResponseEntity<String> health() {
        boolean isLeader = raftController.isControllerLeader();
        Integer leaderId = raftController.getControllerLeaderId();
        return ResponseEntity.ok(String.format(
            "{\"status\":\"UP\",\"isLeader\":%s,\"leaderId\":%d}", 
            isLeader, leaderId
        ));
    }
}
