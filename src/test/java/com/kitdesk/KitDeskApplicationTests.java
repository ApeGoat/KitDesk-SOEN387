package com.kitdesk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Boots the whole service against a real PostgreSQL 16 and checks the migrations.
 * Needs Docker running.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class KitDeskApplicationTests {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MockMvc mvc;

    @Test
    void unknownUrlGivesNotFoundInTheStandardErrorShape() throws Exception {
        mvc.perform(get("/no-such-thing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andExpect(jsonPath("$.trace").doesNotExist());
    }

    @Test
    void errorBodyEchoesTheCallersCorrelationId() throws Exception {
        String id = "c1f9a3d2-77e0-4b1c-9f45-0a6d2e8b1c33";
        mvc.perform(get("/no-such-thing").header("X-Correlation-Id", id))
                .andExpect(header().string("X-Correlation-Id", id))
                .andExpect(jsonPath("$.correlationId").value(id));
    }

    @Test
    void healthChecksAreServedAtTheContractPaths() throws Exception {
        mvc.perform(get("/health/live"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/health/ready"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.database").value("UP"));
    }

    @Test
    void contractIsPublished() throws Exception {
        mvc.perform(get("/openapi.yaml")).andExpect(status().isOk());
    }

    @Test
    void publicIdsHaveTheContractPrefixes() {
        assertThat(jdbc.queryForList("SELECT public_id FROM units", String.class))
                .allSatisfy(id -> assertThat(id).matches("unt_[0-9a-f]{16}"));
        assertThat(jdbc.queryForList("SELECT public_id FROM categories", String.class))
                .allSatisfy(id -> assertThat(id).startsWith("cat_"));
    }

    @Test
    void migrationsCreateSchemaAndSeedData() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM categories", Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject(
                "SELECT rental_limit FROM categories WHERE code = 'LENS'", Integer.class))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM users WHERE email LIKE 'student%'", Integer.class))
                .isEqualTo(50);
    }

    @Test
    void databaseRefusesOverlappingActiveReservationsForOneUnit() {
        Long unit = jdbc.queryForObject("SELECT id FROM units WHERE asset_tag = 'CAM-R5-03'", Long.class);
        Long alice = jdbc.queryForObject("SELECT id FROM users WHERE email = 'alice@kitdesk.test'", Long.class);
        Long bob = jdbc.queryForObject("SELECT id FROM users WHERE email = 'bob@kitdesk.test'", Long.class);
        String insert = "INSERT INTO reservations (student_id, unit_id, start_date, end_date)"
                + " VALUES (?, ?, ?::date, ?::date)";

        jdbc.update(insert, alice, unit, "2026-12-01", "2026-12-05");

        // Bob's dates overlap Alice's on Dec 5: the exclusion constraint must refuse it.
        assertThatThrownBy(() -> jdbc.update(insert, bob, unit, "2026-12-05", "2026-12-08"))
                .isInstanceOf(DataIntegrityViolationException.class);

        // Once Alice cancels, the unit is free for those dates again.
        jdbc.update("UPDATE reservations SET status = 'CANCELLED' WHERE student_id = ?", alice);
        jdbc.update(insert, bob, unit, "2026-12-05", "2026-12-08");
    }
}
