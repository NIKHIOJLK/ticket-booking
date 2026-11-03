package com.nikhil.ticketbooking.tickets;

import com.nikhil.ticketbooking.config.AppProperties;
import com.nikhil.ticketbooking.model.Booking;
import com.nikhil.ticketbooking.model.BookingStatus;
import com.nikhil.ticketbooking.model.Event;
import com.nikhil.ticketbooking.model.Seat;
import com.nikhil.ticketbooking.model.User;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/** No database or Spring needed: just draw a ticket. */
class TicketPdfTest {

    private final TicketPdf pdf = new TicketPdf(new AppProperties("http://localhost:5173/", null, null, null, null));

    @Test
    void rendersAPdfEvenWithCharactersTheBuiltInFontLacks() throws Exception {
        Event event = new Event();
        event.setName("Diljit Dosanjh – Dil-Luminati Tour, Mumbai Édition ₹ 🎤 with an extremely long name that wraps");
        event.setVenue("Jio World Garden, BKC");
        event.setEventDate(LocalDateTime.of(2026, 12, 20, 19, 30));
        event.setPrice(new BigDecimal("2499"));
        Seat seat = new Seat();
        seat.setEvent(event);
        seat.setSeatLabel("C12");
        User user = new User();
        user.setEmail("meera@example.com");
        Booking booking = new Booking();
        booking.setSeat(seat);
        booking.setUser(user);
        booking.setStatus(BookingStatus.CONFIRMED);
        booking.setReference("TKT-7Q4M2XKD");

        byte[] bytes = pdf.render(booking);
        assertThat(new String(bytes, 0, 5)).isEqualTo("%PDF-");
        assertThat(bytes.length).isGreaterThan(2000);
    }

    @Test
    void qrCodeLinksToTheCheckInPage() {
        assertThat(pdf.checkInUrl("TKT-ABC")).isEqualTo("http://localhost:5173/checkin?ref=TKT-ABC");
    }

    @Test
    void unsupportedCharactersAreReplacedNotCrashed() {
        assertThat(TicketPdf.safe("Café – ₹250")).isEqualTo("Cafe ? ?250");
    }
}
