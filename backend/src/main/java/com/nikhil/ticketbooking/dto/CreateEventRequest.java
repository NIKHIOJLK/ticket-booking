package com.nikhil.ticketbooking.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class CreateEventRequest {
    @NotBlank(message = "Give the event a name")
    @Size(max = 200)
    private String name;

    @NotBlank(message = "Enter the venue")
    @Size(max = 200)
    private String venue;

    @NotNull(message = "Pick a date and time")
    @Future(message = "The event must be in the future")
    private LocalDateTime eventDate;

    @Min(value = 1, message = "At least 1 row")
    @Max(value = 26, message = "At most 26 rows (A to Z)")
    private int rows;

    @Min(value = 1, message = "At least 1 seat per row")
    @Max(value = 30, message = "At most 30 seats per row")
    private int seatsPerRow;

    /** Ticket price in rupees. Optional: defaults to 250. */
    @DecimalMin(value = "0", message = "Price can't be negative")
    private BigDecimal price;

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

    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }
}
