package com.nikhil.ticketbooking.mail;

import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;

/** Really sends the email, e.g. through Gmail (see backend/.env.example). */
public class SmtpEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(SmtpEmailSender.class);

    private final JavaMailSender mailSender;
    private final String from;

    public SmtpEmailSender(JavaMailSender mailSender, String from) {
        this.mailSender = mailSender;
        this.from = from;
    }

    @Override
    public String send(Email email) throws Exception {
        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, !email.attachments().isEmpty(), "UTF-8");
        helper.setFrom(from);
        helper.setTo(email.to());
        helper.setSubject(email.subject());
        helper.setText(email.html(), true);
        for (Email.Attachment a : email.attachments()) {
            helper.addAttachment(a.filename(), new ByteArrayResource(a.content()), a.contentType());
        }
        mailSender.send(message);
        log.info("Email '{}' sent to {}", email.subject(), email.to());
        return "sent to " + email.to();
    }
}
