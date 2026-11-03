package com.nikhil.ticketbooking.mail;

/**
 * Sends an email or throws. Throwing matters: the background task then fails and
 * Conveyor retries it with backoff, instead of the email silently going missing.
 */
public interface EmailSender {
    /** @return a short description of where the email went (stored as the task's result) */
    String send(Email email) throws Exception;
}
