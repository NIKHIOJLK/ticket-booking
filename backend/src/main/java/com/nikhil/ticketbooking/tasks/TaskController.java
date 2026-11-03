package com.nikhil.ticketbooking.tasks;

import com.nikhil.ticketbooking.config.AppProperties;
import com.nikhil.ticketbooking.tasks.TaskService.Result;
import com.nikhil.ticketbooking.tasks.TaskService.RetryLaterException;
import com.nikhil.ticketbooking.tasks.TaskService.UnknownTaskException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/**
 * Where Conveyor's HTTP worker delivers background tasks:
 *   POST /internal/tasks/{task name}   header X-Conveyor-Secret   body {jobId, attempt, payload}
 *
 * The status code tells Conveyor what to do next:
 *   200 done or nothing to do  |  503 / 500 try again later (with backoff)  |  other 4xx give up
 */
@RestController
public class TaskController {

    private static final Logger log = LoggerFactory.getLogger(TaskController.class);

    public record TaskRequest(Long jobId, Integer attempt, Map<String, Object> payload) {}

    private final TaskService tasks;
    private final byte[] secret;

    public TaskController(TaskService tasks, AppProperties props) {
        this.tasks = tasks;
        String s = props.tasks() == null ? null : props.tasks().secret();
        this.secret = s == null || s.isBlank() ? null : s.getBytes(StandardCharsets.UTF_8);
        if (this.secret == null) {
            log.warn("TASKS_SECRET is not set: background tasks will be refused until it is (see .env.example)");
        }
    }

    @PostMapping("/internal/tasks/{task}")
    public ResponseEntity<Map<String, Object>> run(@PathVariable String task,
                                                   @RequestHeader(value = "X-Conveyor-Secret", required = false) String given,
                                                   @RequestBody(required = false) TaskRequest request) {
        if (secret == null) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, "TASKS_SECRET is not configured on the booking service");
        }
        // Constant-time comparison, so the secret can't be guessed from response timings.
        if (given == null || !MessageDigest.isEqual(secret, given.getBytes(StandardCharsets.UTF_8))) {
            return error(HttpStatus.UNAUTHORIZED, "Wrong or missing X-Conveyor-Secret");
        }
        Map<String, Object> payload = request == null || request.payload() == null ? Map.of() : request.payload();
        Long jobId = request == null ? null : request.jobId();

        try {
            Result result = dispatch(task, payload);
            log.info("Task {} (job #{}): {} - {}", task, jobId, result.outcome(), result.message());
            return ResponseEntity.ok(Map.of("outcome", result.outcome(), "message", result.message()));
        } catch (UnknownTaskException e) {
            return error(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IllegalArgumentException e) {
            return error(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage());
        } catch (RetryLaterException e) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
        } catch (Exception e) {
            // e.g. the mail server is down: fail this attempt, Conveyor retries with backoff
            log.warn("Task {} (job #{}) failed: {}", task, jobId, e.toString());
            return error(HttpStatus.INTERNAL_SERVER_ERROR, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private Result dispatch(String task, Map<String, Object> payload) throws Exception {
        // Called through the Spring proxy, so each task's @Transactional applies.
        return switch (task) {
            case "expire-hold" -> tasks.expireHold(id(payload, "bookingId"));
            case "send-ticket-email" -> tasks.sendTicketEmail(id(payload, "bookingId"));
            case "send-reminder" -> tasks.sendReminder(id(payload, "bookingId"));
            case "send-cancellation-email" -> tasks.sendCancellationEmail(id(payload, "bookingId"));
            case "notify-waitlist" -> tasks.notifyWaitlist(id(payload, "eventId"));
            default -> throw new UnknownTaskException(task);
        };
    }

    private static Long id(Map<String, Object> payload, String key) {
        Object v = payload.get(key);
        if (v instanceof Number n) return n.longValue();
        if (v instanceof String s && s.matches("\\d+")) return Long.parseLong(s);
        throw new IllegalArgumentException("Task payload needs a numeric '" + key + "'");
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message));
    }
}
