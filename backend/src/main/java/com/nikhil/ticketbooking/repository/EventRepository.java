package com.nikhil.ticketbooking.repository;

import com.nikhil.ticketbooking.model.Event;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EventRepository extends JpaRepository<Event, Long> {
}