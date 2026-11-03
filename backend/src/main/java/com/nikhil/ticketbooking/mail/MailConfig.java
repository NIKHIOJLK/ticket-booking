package com.nikhil.ticketbooking.mail;

import com.nikhil.ticketbooking.config.AppProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;

import java.nio.file.Path;

@Configuration
public class MailConfig {

    @Bean
    public EmailSender emailSender(AppProperties props, ObjectProvider<JavaMailSender> javaMailSender) {
        AppProperties.Mail mail = props.mail();
        if ("smtp".equalsIgnoreCase(mail.mode())) {
            JavaMailSender sender = javaMailSender.getIfAvailable();
            if (sender == null) {
                throw new IllegalStateException("MAIL_MODE=smtp but no SMTP settings were found (set SMTP_HOST etc. in .env)");
            }
            return new SmtpEmailSender(sender, mail.from());
        }
        return new OutboxEmailSender(Path.of(mail.outboxDir()), mail.from());
    }
}
