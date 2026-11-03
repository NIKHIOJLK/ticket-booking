package com.nikhil.ticketbooking.dto;

import java.time.LocalDateTime;

/**
 * @param status        AVAILABLE, HELD (someone is paying for it) or BOOKED
 * @param booked        true unless AVAILABLE (kept for older clients)
 * @param mine          the seat is held or booked by the person asking
 * @param bookingId     only for your own seats
 * @param holdExpiresAt only for your own held seats
 */
public record SeatResponse(Long id, String seatLabel, String status, boolean booked, boolean mine,
                           Long bookingId, LocalDateTime holdExpiresAt) {
}
