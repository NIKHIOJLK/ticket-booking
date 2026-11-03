package com.nikhil.ticketbooking.dto;

import com.nikhil.ticketbooking.model.Booking;
import com.nikhil.ticketbooking.model.BookingStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** What the API returns for a booking (never the raw entity, which includes the user's password hash). */
public record BookingResponse(
        Long id,
        String reference,
        BookingStatus status,
        Long seatId,
        String seatLabel,
        Long eventId,
        String eventName,
        String venue,
        LocalDateTime eventDate,
        BigDecimal price,
        LocalDateTime bookedAt,
        LocalDateTime holdExpiresAt,
        LocalDateTime confirmedAt,
        LocalDateTime cancelledAt,
        LocalDateTime checkedInAt,
        boolean cancellable) {

    public static BookingResponse of(Booking b, boolean cancellable) {
        var seat = b.getSeat();
        var event = seat.getEvent();
        return new BookingResponse(b.getId(), b.getReference(), b.getStatus(), seat.getId(), seat.getSeatLabel(),
                event.getId(), event.getName(), event.getVenue(), event.getEventDate(), event.getPrice(),
                b.getBookedAt(), b.getStatus() == BookingStatus.HELD ? b.getHoldExpiresAt() : null,
                b.getConfirmedAt(), b.getCancelledAt(), b.getCheckedInAt(), cancellable);
    }
}
