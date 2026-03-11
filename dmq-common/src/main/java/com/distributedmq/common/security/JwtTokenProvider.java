package com.distributedmq.common.security;

import com.distributedmq.common.config.ServiceDiscovery;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;

/**
 * JWT Token Provider - Generates and validates JWT tokens
 * Uses shared secret from services.json for HMAC-SHA256 signing
 */
@Slf4j
public class JwtTokenProvider {
    
    private final SecretKey secretKey;
    private final long expiryMillis;
    private final String issuer;
    private final String audience;
    
    public JwtTokenProvider(long expirySeconds, String issuer, String audience) {
        // Load shared secret from services.json
        String secret = ServiceDiscovery.getJwtSecret();
        if (secret == null || secret.length() < 32) {
            throw new IllegalStateException("JWT secret must be at least 32 characters");
        }
        
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expiryMillis = expirySeconds * 1000;
        this.issuer = issuer;
        this.audience = audience;
        
        log.info("JwtTokenProvider initialized with expiry: {} seconds", expirySeconds);
    }
    
    /**
     * Generate JWT token for user with roles
     */
    public String generate(String username, List<String> roles) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expiryMillis);
        
        String token = Jwts.builder()
                .subject(username)
                .claim("roles", roles)
                .issuer(issuer)
                .audience().add(audience).and()
                .issuedAt(now)
                .expiration(expiry)
                .signWith(secretKey, Jwts.SIG.HS256)
                .compact();
        
        log.debug("Generated JWT token for user: {} with roles: {}", username, roles);
        return token;
    }
    
    /**
     * Verify and parse JWT token
     * @return UserPrincipal if valid
     * @throws JwtException if token is invalid or expired
     */
    public UserPrincipal verify(String token) throws JwtException {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(secretKey)
                    .requireIssuer(issuer)
                    .requireAudience(audience)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            
            String username = claims.getSubject();
            @SuppressWarnings("unchecked")
            List<String> roles = claims.get("roles", List.class);
            
            log.debug("Verified JWT token for user: {}", username);
            return new UserPrincipal(username, roles);
            
        } catch (ExpiredJwtException e) {
            log.warn("JWT token expired: {}", e.getMessage());
            throw new JwtException("Token expired", e);
        } catch (UnsupportedJwtException e) {
            log.warn("Unsupported JWT token: {}", e.getMessage());
            throw new JwtException("Unsupported token format", e);
        } catch (MalformedJwtException e) {
            log.warn("Malformed JWT token: {}", e.getMessage());
            throw new JwtException("Malformed token", e);
        } catch (SecurityException e) {
            log.warn("JWT signature validation failed: {}", e.getMessage());
            throw new JwtException("Invalid token signature", e);
        } catch (IllegalArgumentException e) {
            log.warn("JWT token is empty or null: {}", e.getMessage());
            throw new JwtException("Token is empty or null", e);
        } catch (Exception e) {
            log.error("Unexpected error validating JWT token", e);
            throw new JwtException("Token validation failed", e);
        }
    }
    
    /**
     * Extract username from token without full validation (for logging/debugging)
     */
    public String extractUsername(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return claims.getSubject();
        } catch (Exception e) {
            return null;
        }
    }
    
    public long getExpirySeconds() {
        return expiryMillis / 1000;
    }
}
