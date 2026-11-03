package com.nikhil.ticketbooking.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;
import java.util.Locale;

/** Everything under "app." in application.properties, as typed values. */
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        String frontendUrl,
        List<String> adminEmails,
        Booking booking,
        Tasks tasks,
        Mail mail) {

    public record Booking(Duration holdDuration, int maxSeatsPerUser, Duration cancelCutoff, Duration reminderBefore) {}

    public record Tasks(String secret) {}

    public record Mail(String mode, String from, String outboxDir) {}

    public boolean isAdminEmail(String email) {
        if (adminEmails == null || email == null) return false;
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        return adminEmails.stream()
                .map(e -> e.trim().toLowerCase(Locale.ROOT))
                .anyMatch(e -> !e.isEmpty() && e.equals(normalized));
    }
}
