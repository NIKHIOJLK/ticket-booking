package com.nikhil.ticketbooking.dto;

import java.time.LocalDateTime;

public class CreateEventRequest {
    private String name;
    private String venue;
    private LocalDateTime eventDate;
    private int rows;
    private int seatsPerRow;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getVenue() { return venue; }
    public void setVenue(String venue) { this.venue = venue; }

    public LocalDateTime getEventDate() { return eventDate; }
    public void setEventDate(LocalDateTime eventDate) { this.eventDate = eventDate; }

    public int getRows() { return rows; }
    public void setRows(int rows) { this.rows = rows; }

    public int getSeatsPerRow() { return seatsPerRow; }
    public void setSeatsPerRow(int seatsPerRow) { this.seatsPerRow = seatsPerRow; }
}