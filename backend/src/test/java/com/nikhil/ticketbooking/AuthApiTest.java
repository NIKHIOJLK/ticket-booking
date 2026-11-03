package com.nikhil.ticketbooking;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class AuthApiTest extends IntegrationTest {

    @Test
    void registerNeverReturnsThePasswordHash() throws Exception {
        Res res = post("/api/auth/register", null, "{\"email\":\"Priya@Example.com\",\"password\":\"password123\"}");
        assertThat(res.status()).isEqualTo(201);
        assertThat(res.body()).doesNotContain("password").doesNotContain("$2a$");
        assertThat(res.json()).containsEntry("email", "priya@example.com").containsEntry("role", "USER");
    }

    @Test
    void duplicateEmailIsRejectedWhateverTheCase() throws Exception {
        signUp("rahul@example.com");
        Res again = post("/api/auth/register", null, "{\"email\":\"RAHUL@example.com\",\"password\":\"password123\"}");
        assertThat(again.status()).isEqualTo(409);
        assertThat(again.error()).contains("already exists");
    }

    @Test
    void invalidRegistrationExplainsWhichFieldIsWrong() throws Exception {
        Res res = post("/api/auth/register", null, "{\"email\":\"not-an-email\",\"password\":\"short\"}");
        assertThat(res.status()).isEqualTo(400);
        assertThat(res.body()).contains("email").contains("at least 8 characters");
    }

    @Test
    void wrongPasswordIs401NotA500() throws Exception {
        signUp("anita@example.com");
        Res res = post("/api/auth/login", null, "{\"email\":\"anita@example.com\",\"password\":\"wrong-password\"}");
        assertThat(res.status()).isEqualTo(401);
        assertThat(res.error()).isEqualTo("Invalid email or password");
    }

    @Test
    void protectedEndpointsSay401WithoutATokenAnd403ForNonAdmins() throws Exception {
        assertThat(get("/api/bookings/me", null).status()).isEqualTo(401);

        String user = signUp("user@example.com");
        String body = "{\"name\":\"X\",\"venue\":\"Y\",\"eventDate\":\"" + LocalDateTime.now().plusDays(3).withNano(0)
                + "\",\"rows\":1,\"seatsPerRow\":1}";
        assertThat(post("/api/events", user, body).status()).isEqualTo(403);
        assertThat(get("/api/checkin/TKT-ABCDEFGH", user).status()).isEqualTo(403);
    }

    @Test
    void adminEmailsGetTheAdminRole() throws Exception {
        String admin = signUp(ADMIN_EMAIL);
        Res me = get("/api/auth/me", admin);
        assertThat(me.json()).containsEntry("role", "ADMIN");
        assertThat(createEvent(admin, LocalDateTime.now().plusDays(5), 2, 3)).isPositive();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM seats", Long.class)).isEqualTo(6);
    }
}
