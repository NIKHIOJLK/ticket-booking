package com.nikhil.ticketbooking.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "bookings")
public class Booking {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne
    @JoinColumn(name = "seat_id", unique = true)
    private Seat seat;

    @ManyToOne
    @JoinColumn(name = "user_id")
    private User user;

    @Column(name = "booked_at")
    private LocalDateTime bookedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Seat getSeat() { return seat; }
    public void setSeat(Seat seat) { this.seat = seat; }

    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }

    public LocalDateTime getBookedAt() { return bookedAt; }
    public void setBookedAt(LocalDateTime bookedAt) { this.bookedAt = bookedAt; }
}