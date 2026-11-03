package com.nikhil.ticketbooking.controller;

import com.nikhil.ticketbooking.dto.BookingRequest;
import com.nikhil.ticketbooking.dto.BookingResponse;
import com.nikhil.ticketbooking.model.Booking;
import com.nikhil.ticketbooking.security.CurrentUser;
import com.nikhil.ticketbooking.service.BookingService;
import com.nikhil.ticketbooking.tickets.TicketPdf;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    private final BookingService bookingService;
    private final TicketPdf ticketPdf;

    public BookingController(BookingService bookingService, TicketPdf ticketPdf) {
        this.bookingService = bookingService;
        this.ticketPdf = ticketPdf;
    }

    /** Step 1: hold a seat for a few minutes. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BookingResponse holdSeat(@RequestBody BookingRequest request, Authentication authentication) {
        Long userId = CurrentUser.id(authentication);
        return bookingService.holdSeat(request.getSeatId(), userId);
    }

    /** Step 2: pay (demo) and confirm. Queues the ticket email and the reminder. */
    @PostMapping("/{id}/confirm")
    public BookingResponse confirm(@PathVariable Long id, Authentication authentication) {
        return bookingService.confirm(id, CurrentUser.id(authentication));
    }

    /** Give a held seat back before paying. */
    @PostMapping("/{id}/release")
    public BookingResponse release(@PathVariable Long id, Authentication authentication) {
        return bookingService.release(id, CurrentUser.id(authentication));
    }

    @PostMapping("/{id}/cancel")
    public BookingResponse cancel(@PathVariable Long id, Authentication authentication) {
        return bookingService.cancel(id, CurrentUser.id(authentication));
    }

    @GetMapping("/me")
    public List<BookingResponse> myBookings(Authentication authentication) {
        return bookingService.listForUser(CurrentUser.id(authentication));
    }

    @GetMapping("/{id}/ticket.pdf")
    public ResponseEntity<byte[]> ticket(@PathVariable Long id, Authentication authentication) throws IOException {
        Booking booking = bookingService.getConfirmedForUser(id, CurrentUser.id(authentication));
        byte[] pdf = ticketPdf.render(booking);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("ticket-" + booking.getReference() + ".pdf").build().toString())
                .body(pdf);
    }
}
