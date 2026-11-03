package com.nikhil.ticketbooking.dto;

import com.nikhil.ticketbooking.model.Event;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record EventResponse(
        Long id,
        String name,
        String venue,
        LocalDateTime eventDate,
        BigDecimal price,
        long totalSeats,
        long availableSeats,
        boolean soldOut,
        boolean ended,
        /** Only filled in for a logged-in user on the single-event endpoint. */
        Boolean onWaitlist) {

    public static EventResponse of(Event e, EventStats stats, LocalDateTime now, Boolean onWaitlist) {
        long total = stats == null ? 0 : stats.totalSeats();
        long available = stats == null ? 0 : stats.availableSeats();
        boolean ended = !e.getEventDate().isAfter(now);
        return new EventResponse(e.getId(), e.getName(), e.getVenue(), e.getEventDate(), e.getPrice(),
                total, available, total > 0 && available == 0, ended, onWaitlist);
    }
}
