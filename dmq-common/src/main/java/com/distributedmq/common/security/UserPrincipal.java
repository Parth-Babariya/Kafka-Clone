package com.distributedmq.common.security;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Represents an authenticated user with roles
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserPrincipal {
    
    private String username;
    private List<String> roles;
    
    /**
     * Check if user has a specific role
     */
    public boolean hasRole(String role) {
        return roles != null && roles.contains(role);
    }
    
    /**
     * Check if user has any of the specified roles
     */
    public boolean hasAnyRole(String... rolesToCheck) {
        if (roles == null || rolesToCheck == null) {
            return false;
        }
        for (String role : rolesToCheck) {
            if (roles.contains(role)) {
                return true;
            }
        }
        return false;
    }
}
