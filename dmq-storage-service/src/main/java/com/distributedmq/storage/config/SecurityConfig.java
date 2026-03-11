package com.distributedmq.storage.config;

import com.distributedmq.common.config.ServiceDiscovery;
import com.distributedmq.common.security.JwtTokenProvider;
import com.distributedmq.common.security.JwtValidator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Security configuration for JWT authentication
 */
@Configuration
public class SecurityConfig {
    
    @Value("${dmq.security.jwt.issuer}")
    private String issuer;
    
    @Value("${dmq.security.jwt.audience}")
    private String audience;
    
    @Bean
    public JwtTokenProvider jwtTokenProvider() {
        // Load ServiceDiscovery config to get JWT secret from services.json
        ServiceDiscovery.loadConfig();
        long expirySeconds = ServiceDiscovery.getJwtExpirySeconds();
        return new JwtTokenProvider(expirySeconds, issuer, audience);
    }
    
    @Bean
    public JwtValidator jwtValidator(JwtTokenProvider tokenProvider) {
        return new JwtValidator(tokenProvider);
    }
}
