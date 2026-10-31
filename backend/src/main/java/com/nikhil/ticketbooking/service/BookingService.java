package com.nikhil.ticketbooking.service;

import com.nikhil.ticketbooking.model.Booking;
import com.nikhil.ticketbooking.model.Seat;
import com.nikhil.ticketbooking.model.User;
import com.nikhil.ticketbooking.repository.BookingRepository;
import com.nikhil.ticketbooking.repository.SeatRepository;
import com.nikhil.ticketbooking.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class BookingService {

    private final BookingRepository bookingRepository;
    private final SeatRepository seatRepository;
    private final UserRepository userRepository;

    // Spring automatically supplies these three when it creates this class —
    // this pattern is called "constructor injection".
    public BookingService(BookingRepository bookingRepository,
                           SeatRepository seatRepository,
                           UserRepository userRepository) {
        this.bookingRepository = bookingRepository;
        this.seatRepository = seatRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public Booking bookSeat(Long seatId, Long userId) {
        Seat seat = seatRepository.findById(seatId)
            .orElseThrow(() -> new RuntimeException("Seat not found"));
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new RuntimeException("User not found"));

        Booking booking = new Booking();
        booking.setSeat(seat);
        booking.setUser(user);
        booking.setBookedAt(LocalDateTime.now());

        try {
            return bookingRepository.save(booking);
        } catch (DataIntegrityViolationException e) {
            // This is the exact error we saw in pgAdmin earlier —
            // Postgres rejecting a duplicate seat_id — just caught in Java now.
            throw new SeatAlreadyBookedException(
                "Seat " + seat.getSeatLabel() + " is already booked"
            );
        }
    }
}