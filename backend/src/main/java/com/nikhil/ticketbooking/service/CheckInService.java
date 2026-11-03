package com.nikhil.ticketbooking.service;

import com.nikhil.ticketbooking.model.Booking;
import com.nikhil.ticketbooking.model.BookingStatus;
import com.nikhil.ticketbooking.repository.BookingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Scanning tickets at the entrance. Each ticket lets one person in, once. */
@Service
public class CheckInService {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("h:mm a 'on' d MMM", Locale.ENGLISH);

    private final BookingRepository bookingRepository;

    public CheckInService(BookingRepository bookingRepository) {
        this.bookingRepository = bookingRepository;
    }

    public record TicketInfo(String reference, String status, String eventName, String venue,
                             LocalDateTime eventDate, String seatLabel, String holderEmail,
                             LocalDateTime checkedInAt) {
        static TicketInfo of(Booking b) {
            return new TicketInfo(b.getReference(), b.getStatus().name(), b.getEvent().getName(),
                    b.getEvent().getVenue(), b.getEvent().getEventDate(), b.getSeat().getSeatLabel(),
                    b.getUser().getEmail(), b.getCheckedInAt());
        }
    }

    @Transactional(readOnly = true)
    public TicketInfo lookUp(String reference) {
        return TicketInfo.of(find(reference));
    }

    @Transactional
    public TicketInfo checkIn(String reference) {
        String ref = normalize(reference);
        // One conditional UPDATE: if two staff scan the same ticket at the same moment,
        // exactly one of them gets "checked in" and the other gets "already used".
        int changed = bookingRepository.checkIn(ref, LocalDateTime.now());
        Booking booking = find(ref);
        if (changed == 1) return TicketInfo.of(booking);

        if (booking.getCheckedInAt() != null) {
            throw ApiException.conflict("Already used: this ticket was checked in at "
                    + TIME.format(booking.getCheckedInAt()));
        }
        if (booking.getStatus() == BookingStatus.CANCELLED) throw ApiException.conflict("This booking was cancelled");
        throw ApiException.conflict("This booking was never paid for (status " + booking.getStatus() + ")");
    }

    private Booking find(String reference) {
        return bookingRepository.findByReference(normalize(reference))
                .orElseThrow(() -> ApiException.notFound("No ticket with reference " + reference));
    }

    private static String normalize(String reference) {
        String r = reference == null ? "" : reference.trim().toUpperCase(Locale.ROOT);
        return r.startsWith("TKT-") ? r : "TKT-" + r;
    }
}
