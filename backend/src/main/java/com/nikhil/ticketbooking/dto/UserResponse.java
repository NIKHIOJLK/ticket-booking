package com.nikhil.ticketbooking.dto;

import com.nikhil.ticketbooking.model.User;

/** A user as the API shows it: no password hash. */
public record UserResponse(Long id, String email, String role) {
    public static UserResponse of(User u) {
        return new UserResponse(u.getId(), u.getEmail(), u.getRole().name());
    }
}
