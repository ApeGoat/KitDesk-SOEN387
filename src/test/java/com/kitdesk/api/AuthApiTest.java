package com.kitdesk.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.kitdesk.support.ApiTestBase;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import org.junit.jupiter.api.Test;

/** Login, and the token check on every other endpoint. */
class AuthApiTest extends ApiTestBase {

    static final String TEST_SECRET = "test-secret-for-automated-tests-only-0123456789";

    @Test
    void loginReturnsABearerTokenWithTheRoleAndLifetime() {
        Resp r = login("alice@kitdesk.test", "password");
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.text("tokenType")).isEqualTo("Bearer");
        assertThat(r.body().path("expiresIn").asLong()).isEqualTo(1800);
        assertThat(r.text("role")).isEqualTo("STUDENT");
        assertThat(r.text("token")).isNotBlank();

        assertThat(login("staff@kitdesk.test", "password").text("role")).isEqualTo("STAFF");
    }

    @Test
    void emailIsMatchedIgnoringCase() {
        assertThat(login("ALICE@kitdesk.test", "password").status()).isEqualTo(200);
    }

    @Test
    void wrongPasswordAndUnknownEmailGetTheSameError() {
        Resp wrongPassword = login("alice@kitdesk.test", "nope");
        Resp unknownEmail = login("nobody@kitdesk.test", "password");
        assertThat(wrongPassword.status()).isEqualTo(401);
        assertThat(wrongPassword.code()).isEqualTo("INVALID_CREDENTIALS");
        assertThat(unknownEmail.status()).isEqualTo(401);
        assertThat(unknownEmail.code()).isEqualTo("INVALID_CREDENTIALS");
        assertThat(unknownEmail.text("message")).isEqualTo(wrongPassword.text("message"));
    }

    @Test
    void badLoginBodiesAre400() {
        assertThat(send("POST", "/auth/login", null, "{\"email\":\"alice@kitdesk.test\"}").code())
                .isEqualTo("INVALID_REQUEST");
        assertThat(send("POST", "/auth/login", null,
                "{\"email\":\"alice@kitdesk.test\",\"password\":\"password\",\"role\":\"STAFF\"}").status())
                .isEqualTo(400);
        assertThat(send("POST", "/auth/login", null, "not json").status()).isEqualTo(400);
    }

    @Test
    void everyOtherEndpointNeedsAToken() {
        for (String path : new String[] {"/categories", "/models", "/reservations", "/reservations/res_x"}) {
            Resp r = get(path, null);
            assertThat(r.status()).as(path).isEqualTo(401);
            assertThat(r.code()).isEqualTo("UNAUTHORIZED");
            assertThat(r.body().path("correlationId").asText()).isNotBlank();
        }
        assertThat(send("POST", "/reservations", null, "{}", "Idempotency-Key", newKey()).status()).isEqualTo(401);
    }

    @Test
    void healthAndDocsNeedNoToken() {
        assertThat(get("/health/live", null).status()).isEqualTo(200);
        assertThat(get("/health/ready", null).status()).isEqualTo(200);
        assertThat(send("GET", "/openapi.yaml", null, null).raw().statusCode()).isEqualTo(200);
    }

    @Test
    void aValidTokenIsAccepted() {
        assertThat(get("/categories", token("alice@kitdesk.test")).status()).isEqualTo(200);
    }

    @Test
    void tamperedTokenIs401() {
        // Change the role in the payload from STUDENT to STAFF without re-signing.
        String[] parts = token("alice@kitdesk.test").split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
                .replace("STUDENT", "STAFF");
        String forged = parts[0] + "." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + "." + parts[2];
        assertThat(get("/reservations", forged).code()).isEqualTo("UNAUTHORIZED");
    }

    @Test
    void tokenSignedWithAnotherSecretIs401() {
        String other = Jwts.builder().subject("1").claim("role", "STAFF")
                .expiration(Date.from(Instant.now().plusSeconds(600)))
                .signWith(Keys.hmacShaKeyFor("someone-elses-secret-key-0123456789abcdef".getBytes(StandardCharsets.UTF_8)))
                .compact();
        assertThat(get("/reservations", other).status()).isEqualTo(401);
    }

    @Test
    void unsignedTokenIs401() {
        String header = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"none\"}".getBytes());
        String body = Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"sub\":\"1\",\"role\":\"STAFF\",\"exp\":9999999999}".getBytes());
        assertThat(get("/reservations", header + "." + body + ".").status()).isEqualTo(401);
    }

    @Test
    void expiredTokenIs401() {
        String expired = Jwts.builder().subject("1").claim("role", "STAFF")
                .issuedAt(Date.from(Instant.now().minusSeconds(3600)))
                .expiration(Date.from(Instant.now().minusSeconds(1800)))
                .signWith(Keys.hmacShaKeyFor(TEST_SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();
        assertThat(get("/reservations", expired).status()).isEqualTo(401);
    }

    @Test
    void garbageAuthorizationHeadersAre401() {
        assertThat(send("GET", "/reservations", null, null, "Authorization", "Basic abc").status()).isEqualTo(401);
        assertThat(send("GET", "/reservations", null, null, "Authorization", "Bearer not.a.jwt").status()).isEqualTo(401);
    }

    @Test
    void unknownUrlWithATokenIs404() {
        Resp r = get("/no-such-thing", token("alice@kitdesk.test"));
        assertThat(r.status()).isEqualTo(404);
        assertThat(r.code()).isEqualTo("NOT_FOUND");
    }
}
