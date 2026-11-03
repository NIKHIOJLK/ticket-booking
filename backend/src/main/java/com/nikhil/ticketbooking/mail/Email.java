package com.nikhil.ticketbooking.mail;

import java.util.List;

public record Email(String to, String subject, String html, List<Attachment> attachments) {

    public record Attachment(String filename, String contentType, byte[] content) {}

    public static Email of(String to, String subject, String html) {
        return new Email(to, subject, html, List.of());
    }
}
