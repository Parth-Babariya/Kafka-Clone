package com.distributedmq.common.security;

/**
 * Custom exception for JWT validation errors
 */
public class JwtException extends Exception {
    
    public JwtException(String message) {
        super(message);
    }
    
    public JwtException(String message, Throwable cause) {
        super(message, cause);
    }
}
