package com.nikhil.ticketbooking.mail;

import com.nikhil.ticketbooking.model.Booking;
import com.nikhil.ticketbooking.model.Event;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** The HTML for each email the app sends. Inline styles, because email clients ignore stylesheets. */
public final class EmailTemplates {

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy 'at' h:mm a", Locale.ENGLISH);

    private EmailTemplates() {}

    public static Email ticket(Booking b, byte[] pdf) {
        Event e = b.getEvent();
        String html = layout("You're going to " + esc(e.getName()) + "!", """
                <p>Your booking is confirmed. Your ticket is attached as a PDF.</p>
                %s
                <p>At the entrance, show the QR code on your ticket. Each ticket can be used once.</p>
                <p style="color:#777;font-size:13px">Plans changed? You can cancel from <b>My bookings</b> up to 2 hours before the show.</p>
                """.formatted(details(b)));
        return new Email(b.getUser().getEmail(),
                "Your ticket for " + e.getName() + " (seat " + b.getSeat().getSeatLabel() + ")", html,
                java.util.List.of(new Email.Attachment("ticket-" + b.getReference() + ".pdf", "application/pdf", pdf)));
    }

    public static Email reminder(Booking b, LocalDateTime now) {
        Event e = b.getEvent();
        long minutes = Math.max(0, Duration.between(now, e.getEventDate()).toMinutes());
        String startsIn = minutes >= 90 ? Math.round(minutes / 60.0) + " hours" : minutes + " minutes";
        String html = layout(esc(e.getName()) + " starts in " + startsIn, """
                <p>Just a reminder that your event starts soon.</p>
                %s
                <p>Keep your ticket QR code ready (it's in your confirmation email and in <b>My bookings</b>).</p>
                """.formatted(details(b)));
        return Email.of(b.getUser().getEmail(), "Reminder: " + e.getName() + " starts in " + startsIn, html);
    }

    public static Email cancellation(Booking b) {
        Event e = b.getEvent();
        String html = layout("Booking cancelled", """
                <p>Your booking for <b>%s</b>, seat <b>%s</b>, has been cancelled.</p>
                <p>A refund of <b>%s</b> would be on its way (this is a demo app, so no real payment was taken).</p>
                <p style="color:#777;font-size:13px">Booking reference: %s</p>
                """.formatted(esc(e.getName()), esc(b.getSeat().getSeatLabel()), money(e.getPrice()), b.getReference()));
        return Email.of(b.getUser().getEmail(), "Cancelled: " + e.getName() + " (seat " + b.getSeat().getSeatLabel() + ")", html);
    }

    public static Email waitlistSeatFree(String to, Event e, String eventUrl) {
        String html = layout("A seat just opened up", """
                <p>Good news: a seat is now available for <b>%s</b> (%s, %s).</p>
                <p>Seats go to whoever books first, so be quick:</p>
                <p><a href="%s" style="display:inline-block;background:#4a5cf0;color:#fff;padding:10px 18px;border-radius:6px;text-decoration:none">Book a seat</a></p>
                """.formatted(esc(e.getName()), esc(e.getVenue()), WHEN.format(e.getEventDate()), eventUrl));
        return Email.of(to, "A seat opened up for " + e.getName(), html);
    }

    // ------------------------------------------------------------------ helpers

    private static String details(Booking b) {
        Event e = b.getEvent();
        return """
                <table style="border-collapse:collapse;margin:16px 0;font-size:15px">
                  <tr><td style="color:#777;padding:4px 16px 4px 0">Event</td><td><b>%s</b></td></tr>
                  <tr><td style="color:#777;padding:4px 16px 4px 0">When</td><td>%s</td></tr>
                  <tr><td style="color:#777;padding:4px 16px 4px 0">Where</td><td>%s</td></tr>
                  <tr><td style="color:#777;padding:4px 16px 4px 0">Seat</td><td><b>%s</b></td></tr>
                  <tr><td style="color:#777;padding:4px 16px 4px 0">Price</td><td>%s</td></tr>
                  <tr><td style="color:#777;padding:4px 16px 4px 0">Reference</td><td style="font-family:monospace">%s</td></tr>
                </table>""".formatted(esc(e.getName()), WHEN.format(e.getEventDate()), esc(e.getVenue()),
                esc(b.getSeat().getSeatLabel()), money(e.getPrice()), b.getReference());
    }

    private static String layout(String title, String body) {
        return """
                <div style="font-family:'Segoe UI',system-ui,sans-serif;max-width:560px;margin:24px auto;color:#222;line-height:1.5">
                  <div style="font-weight:600;color:#4a5cf0;margin-bottom:8px">Ticket Booking</div>
                  <h2 style="margin:0 0 12px">%s</h2>
                  %s
                </div>""".formatted(title, body);
    }

    static String money(BigDecimal amount) {
        NumberFormat f = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("en-IN"));
        return f.format(amount == null ? BigDecimal.ZERO : amount);
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
