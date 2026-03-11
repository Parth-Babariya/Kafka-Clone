package com.distributedmq.common.exception;

/**
 * Base exception for all DMQ exceptions
 */
public class DMQException extends RuntimeException {
    
    public DMQException(String message) {
        super(message);
    }

    public DMQException(String message, Throwable cause) {
        super(message, cause);
    }
}
