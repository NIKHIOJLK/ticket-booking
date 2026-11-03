package com.nikhil.ticketbooking.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Development mode: instead of sending, each email becomes a folder in outbox/ with
 * email.html (open it in a browser) and its attachments (e.g. ticket.pdf).
 */
public class OutboxEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(OutboxEmailSender.class);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final AtomicInteger COUNTER = new AtomicInteger();

    private final Path dir;
    private final String from;

    public OutboxEmailSender(Path dir, String from) {
        this.dir = dir;
        this.from = from;
    }

    @Override
    public String send(Email email) throws IOException {
        String slug = email.subject().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        if (slug.length() > 50) slug = slug.substring(0, 50);
        Path folder = dir.resolve(STAMP.format(LocalDateTime.now()) + "-" + COUNTER.incrementAndGet() + "-" + slug);
        Files.createDirectories(folder);

        String header = """
                <div style="font:13px system-ui,sans-serif;background:#f4f5f7;border-bottom:1px solid #ddd;padding:12px 16px;color:#444">
                  <div><b>From:</b> %s</div><div><b>To:</b> %s</div><div><b>Subject:</b> %s</div>%s
                </div>
                """.formatted(escape(from), escape(email.to()), escape(email.subject()),
                email.attachments().isEmpty() ? "" : "<div><b>Attachments:</b> " + String.join(", ",
                        email.attachments().stream().map(a -> "<a href=\"" + a.filename() + "\">" + a.filename() + "</a>").toList())
                        + "</div>");
        Files.writeString(folder.resolve("email.html"),
                "<!doctype html><meta charset=\"utf-8\"><title>" + escape(email.subject()) + "</title>"
                        + header + email.html(), StandardCharsets.UTF_8);
        for (Email.Attachment a : email.attachments()) {
            Files.write(folder.resolve(a.filename()), a.content());
        }
        log.info("Email to {} saved to {}", email.to(), folder.toAbsolutePath());
        return "saved to " + folder;
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
