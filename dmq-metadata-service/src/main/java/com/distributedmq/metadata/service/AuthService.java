package com.distributedmq.metadata.service;

import com.distributedmq.common.security.JwtTokenProvider;
import com.distributedmq.metadata.dto.LoginResponse;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Authentication service - manages user credentials and token generation
 * Uses hardcoded users from application.yml (no database)
 */
@Service
@Slf4j
public class AuthService {
    
    private final JwtTokenProvider tokenProvider;
    private final Map<String, UserCredentials> users = new HashMap<>();
    private final SecurityProperties securityProperties;
    
    public AuthService(JwtTokenProvider tokenProvider,
                      @Value("${dmq.security.jwt.issuer}") String issuer,
                      @Value("${dmq.security.jwt.audience}") String audience,
                      SecurityProperties securityProperties) {
        this.tokenProvider = tokenProvider;
        this.securityProperties = securityProperties;
    }
    
    @PostConstruct
    public void init() {
        loadUsersFromConfig();
        log.info("AuthService initialized with {} users", users.size());
    }
    
    private void loadUsersFromConfig() {
        List<UserConfig> userConfigs = securityProperties.getUsers();
        
        if (userConfigs == null || userConfigs.isEmpty()) {
            log.warn("No users configured in application.yml");
            return;
        }
        
        for (UserConfig config : userConfigs) {
            users.put(config.getUsername(), new UserCredentials(
                config.getUsername(),
                config.getPassword(),
                config.getRoles()
            ));
            log.debug("Loaded user: {} with roles: {}", config.getUsername(), config.getRoles());
        }
    }
    
    /**
     * Authenticate user and generate JWT token
     */
    public LoginResponse authenticate(String username, String password) {
        UserCredentials user = users.get(username);
        
        if (user == null || !user.getPassword().equals(password)) {
            log.warn("Authentication failed for user: {}", username);
            throw new RuntimeException("Invalid credentials");
        }
        
        String token = tokenProvider.generate(username, user.getRoles());
        long expiresIn = tokenProvider.getExpirySeconds();
        
        log.info("User {} authenticated successfully with roles: {}", username, user.getRoles());
        return new LoginResponse(token, expiresIn, username);
    }
    
    /**
     * Validate user credentials (without generating token)
     */
    public boolean validateCredentials(String username, String password) {
        UserCredentials user = users.get(username);
        return user != null && user.getPassword().equals(password);
    }
    
    /**
     * Get roles for user
     */
    public List<String> getRolesForUser(String username) {
        UserCredentials user = users.get(username);
        return user != null ? user.getRoles() : null;
    }
    
    /**
     * Refresh JWT token - validate existing token and issue new one
     * Allows expired tokens within grace period (for seamless renewal)
     */
    public LoginResponse refreshToken(String existingToken) {
        try {
            // Try to verify token (may be expired)
            com.distributedmq.common.security.UserPrincipal principal = tokenProvider.verify(existingToken);
            String username = principal.getUsername();
            
            // Verify user still exists in configuration
            UserCredentials user = users.get(username);
            if (user == null) {
                throw new RuntimeException("User no longer exists: " + username);
            }
            
            // Issue new token with fresh expiry
            String newToken = tokenProvider.generate(username, user.getRoles());
            long expiresIn = tokenProvider.getExpirySeconds();
            
            log.info("Token refreshed for user: {}", username);
            return new LoginResponse(newToken, expiresIn, username);
            
        } catch (io.jsonwebtoken.ExpiredJwtException e) {
            // Allow refresh of expired tokens (extract username from expired token)
            String username = e.getClaims().getSubject();
            
            // Verify user still exists
            UserCredentials user = users.get(username);
            if (user == null) {
                throw new RuntimeException("User no longer exists: " + username);
            }
            
            // Issue new token
            String newToken = tokenProvider.generate(username, user.getRoles());
            long expiresIn = tokenProvider.getExpirySeconds();
            
            log.info("Expired token refreshed for user: {}", username);
            return new LoginResponse(newToken, expiresIn, username);
            
        } catch (Exception e) {
            log.error("Token refresh failed: {}", e.getMessage());
            throw new RuntimeException("Invalid token: " + e.getMessage());
        }
    }
    
    // Inner classes for configuration
    @Data
    @AllArgsConstructor
    private static class UserCredentials {
        private String username;
        private String password;
        private List<String> roles;
    }
    
    @Data
    public static class UserConfig {
        private String username;
        private String password;
        private List<String> roles;
    }
    
    /**
     * Configuration properties for security settings
     */
    @Configuration
    @ConfigurationProperties(prefix = "dmq.security")
    @Data
    public static class SecurityProperties {
        private List<UserConfig> users;
    }
}
