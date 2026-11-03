package com.nikhil.ticketbooking.security;

import org.springframework.security.core.Authentication;

/** Reads the logged-in user's id that JwtAuthFilter put into the security context. */
public final class CurrentUser {

    private CurrentUser() {}

    /** The user id, or null when the request has no valid token. */
    public static Long idOrNull(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof Long id)) return null;
        return id;
    }

    public static Long id(Authentication authentication) {
        Long id = idOrNull(authentication);
        if (id == null) throw new IllegalStateException("No logged-in user");
        return id;
    }
}
