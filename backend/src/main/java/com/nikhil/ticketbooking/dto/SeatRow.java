package com.nikhil.ticketbooking.dto;

import com.nikhil.ticketbooking.model.BookingStatus;

import java.time.LocalDateTime;

/** A seat plus the booking currently occupying it (all booking fields are null if it's free). */
public record SeatRow(Long seatId, String seatLabel, Long bookingId, BookingStatus status, Long userId,
                      LocalDateTime holdExpiresAt) {
}
