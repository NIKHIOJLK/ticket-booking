package com.nikhil.ticketbooking;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.json.JsonParser;
import org.springframework.boot.json.JsonParserFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * Starts the whole app on a random port against the ticket_booking_test database and
 * talks to it over real HTTP, the way the React frontend does.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class IntegrationTest {

    protected static final String ADMIN_EMAIL = "admin@test.local";
    protected static final String TASK_SECRET = "test-task-secret";
    private static final JsonParser JSON = JsonParserFactory.getJsonParser();

    @Value("${local.server.port}")
    protected int port;

    @Autowired
    protected JdbcTemplate jdbc;

    protected final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void cleanDatabase() {
        jdbc.execute("TRUNCATE waitlist_entries, bookings, seats, events, users, job_events, jobs, queues "
                + "RESTART IDENTITY CASCADE");
    }

    // ------------------------------------------------------------------ HTTP helpers

    protected record Res(int status, String body) {
        public Map<String, Object> json() {
            return JSON.parseMap(body);
        }

        public List<Object> list() {
            return JSON.parseList(body);
        }

        public String error() {
            return String.valueOf(json().get("error"));
        }
    }

    protected Res call(String method, String path, String token, String jsonBody) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, jsonBody == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(jsonBody))
                .header("Content-Type", "application/json");
        if (token != null) b.header("Authorization", "Bearer " + token);
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Res(r.statusCode(), r.body());
    }

    protected Res get(String path, String token) throws Exception {
        return call("GET", path, token, null);
    }

    protected Res post(String path, String token, String jsonBody) throws Exception {
        return call("POST", path, token, jsonBody == null ? "{}" : jsonBody);
    }

    /** What Conveyor's HTTP worker does: POST /internal/tasks/{name} with the shared secret. */
    protected Res runTask(String name, String payloadJson) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/internal/tasks/" + name))
                .POST(HttpRequest.BodyPublishers.ofString("{\"jobId\":1,\"attempt\":1,\"payload\":" + payloadJson + "}"))
                .header("Content-Type", "application/json")
                .header("X-Conveyor-Secret", TASK_SECRET)
                .build();
        HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
        return new Res(r.statusCode(), r.body());
    }

    // ------------------------------------------------------------------ domain helpers

    protected String signUp(String email) throws Exception {
        Res reg = post("/api/auth/register", null, "{\"email\":\"" + email + "\",\"password\":\"password123\"}");
        if (reg.status() != 201) throw new AssertionError("register failed: " + reg);
        return login(email);
    }

    protected String login(String email) throws Exception {
        Res res = post("/api/auth/login", null, "{\"email\":\"" + email + "\",\"password\":\"password123\"}");
        if (res.status() != 200) throw new AssertionError("login failed: " + res);
        return String.valueOf(res.json().get("token"));
    }

    protected long createEvent(String adminToken, LocalDateTime when, int rows, int seatsPerRow) throws Exception {
        String body = """
                {"name":"Arijit Singh Live","venue":"Jawaharlal Nehru Stadium","eventDate":"%s",
                 "rows":%d,"seatsPerRow":%d,"price":499}"""
                .formatted(when.withNano(0).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME), rows, seatsPerRow);
        Res res = post("/api/events", adminToken, body);
        if (res.status() != 201) throw new AssertionError("create event failed: " + res);
        return ((Number) res.json().get("id")).longValue();
    }

    protected long seatId(long eventId, String label) {
        return jdbc.queryForObject("SELECT id FROM seats WHERE event_id = ? AND seat_label = ?", Long.class, eventId, label);
    }

    protected Res hold(String token, long seatId) throws Exception {
        return post("/api/bookings", token, "{\"seatId\":" + seatId + "}");
    }

    protected long id(Res res) {
        return ((Number) res.json().get("id")).longValue();
    }

    /** Conveyor jobs the app has queued, as "name:state" strings in creation order. */
    protected List<String> jobs() {
        return jdbc.queryForList("SELECT name || ':' || state FROM jobs ORDER BY id", String.class);
    }
}
