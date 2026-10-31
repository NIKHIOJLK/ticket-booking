package com.nikhil.ticketbooking.repository;

import com.nikhil.ticketbooking.model.Booking;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BookingRepository extends JpaRepository<Booking, Long> {boolean existsBySeatId(Long seatId);
}