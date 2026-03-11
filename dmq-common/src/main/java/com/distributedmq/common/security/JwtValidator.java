package com.distributedmq.common.security;

import lombok.extern.slf4j.Slf4j;

import javax.servlet.http.HttpServletRequest;
import java.util.Arrays;

/**
 * JWT Validator - Validates JWT tokens from HTTP requests
 * Uses JwtTokenProvider for actual token verification
 */
@Slf4j
public class JwtValidator {
    
    private final JwtTokenProvider tokenProvider;
    
    public JwtValidator(JwtTokenProvider tokenProvider) {
        this.tokenProvider = tokenProvider;
    }
    
    /**
     * Validate JWT from request Authorization header
     * Returns UserPrincipal if valid, throws JwtException if invalid
     * 
     * Exempts internal and public endpoints from JWT validation
     */
    public UserPrincipal validateRequest(HttpServletRequest request) throws JwtException {
        String path = request.getRequestURI();
        
        // Internal service endpoints - no JWT required
        if (isInternalEndpoint(path)) {
            log.debug("Internal endpoint accessed: {}", path);
            return new UserPrincipal("INTERNAL_SERVICE", Arrays.asList("SERVICE"));
        }
        
        // Public endpoints - no JWT required
        if (isPublicEndpoint(path)) {
            log.debug("Public endpoint accessed: {}", path);
            return new UserPrincipal("ANONYMOUS", Arrays.asList());
        }
        
        // All other endpoints require JWT
        String authHeader = request.getHeader("Authorization");
        
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new JwtException("Missing or invalid Authorization header");
        }
        
        String token = authHeader.substring(7);
        return tokenProvider.verify(token);
    }
    
    /**
     * Check if user has required role
     */
    public boolean hasRole(UserPrincipal user, String role) {
        return user != null && user.hasRole(role);
    }
    
    /**
     * Check if user has any of the required roles
     */
    public boolean hasAnyRole(UserPrincipal user, String... roles) {
        return user != null && user.hasAnyRole(roles);
    }
    
    /**
     * Check if endpoint is internal (service-to-service communication)
     */
    private boolean isInternalEndpoint(String path) {
        return path.startsWith("/api/v1/raft/") ||
               path.startsWith("/api/v1/metadata/heartbeat/") ||
               path.startsWith("/api/v1/storage/replication/") ||
               path.startsWith("/api/v1/isr/") ||
               path.equals("/api/v1/metadata/controller");
    }
    
    /**
     * Check if endpoint is public (no authentication required)
     */
    private boolean isPublicEndpoint(String path) {
        return path.equals("/api/v1/auth/login") ||
               path.equals("/api/v1/auth/refresh") ||
               path.equals("/api/v1/storage/health") ||
               path.startsWith("/actuator/");
    }
}
