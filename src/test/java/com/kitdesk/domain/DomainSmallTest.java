package com.kitdesk.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DomainSmallTest {

    @Test
    void publicIdsHavePrefixAndDoNotRepeat() {
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            String id = PublicIds.newId("unt");
            assertThat(id).matches("unt_[0-9a-f]{16}");
            ids.add(id);
        }
        assertThat(ids).hasSize(10_000);
    }

    @Test
    void onlyReservedAndPickedUpAreActive() {
        assertThat(ReservationStatus.RESERVED.isActive()).isTrue();
        assertThat(ReservationStatus.PICKED_UP.isActive()).isTrue();
        assertThat(ReservationStatus.RETURNED.isActive()).isFalse();
        assertThat(ReservationStatus.CANCELLED.isActive()).isFalse();
        assertThat(ReservationStatus.EXPIRED.isActive()).isFalse();
    }

    @Test
    void idempotencyRecordMatchesOnlyTheSameRequest() {
        IdempotencyRecord rec = new IdempotencyRecord(1L, "k", "hash-a", 201, "{}", Instant.now());
        assertThat(rec.matches("hash-a")).isTrue();
        assertThat(rec.matches("hash-b")).isFalse();
    }

    @Test
    void modelDisplayNameJoinsBrandAndName() {
        EquipmentModel m = new EquipmentModel(new Category(CategoryCode.CAMERA, "Cameras", 1), "Canon", "EOS R5", null);
        assertThat(m.getDisplayName()).isEqualTo("Canon EOS R5");
    }
}
