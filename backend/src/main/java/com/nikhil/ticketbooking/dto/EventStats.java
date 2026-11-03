package com.nikhil.ticketbooking.dto;

public record EventStats(Long eventId, long totalSeats, long takenSeats) {
    public long availableSeats() {
        return totalSeats - takenSeats;
    }
}
