package com.nikhil.ticketbooking.dto;

public class SeatResponse {
    private Long id;
    private String seatLabel;
    private boolean booked;

    public SeatResponse(Long id, String seatLabel, boolean booked) {
        this.id = id;
        this.seatLabel = seatLabel;
        this.booked = booked;
    }

    public Long getId() { return id; }
    public String getSeatLabel() { return seatLabel; }
    public boolean isBooked() { return booked; }
}