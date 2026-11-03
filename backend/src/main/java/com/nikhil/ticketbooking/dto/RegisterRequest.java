package com.nikhil.ticketbooking.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class RegisterRequest {
    @NotBlank(message = "Enter your email")
    @Email(message = "That doesn't look like an email address")
    @Size(max = 255)
    private String email;

    @NotBlank(message = "Choose a password")
    @Size(min = 8, max = 100, message = "Use at least 8 characters")
    private String password;

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
}
