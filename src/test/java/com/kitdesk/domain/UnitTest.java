package com.kitdesk.domain;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class UnitTest {

    static final LocalDate TODAY = LocalDate.of(2026, 11, 2);
    static final Instant NOW = Instant.parse("2026-11-02T15:00:00Z");

    final User alice = new User("alice@kitdesk.test", "hash", "Alice", Role.STUDENT, true);
    final Category cameras = new Category(CategoryCode.CAMERA, "Cameras", 1);
    final Unit unit = new Unit("CAM-R5-03", new EquipmentModel(cameras, "Canon", "EOS R5", null));

    Reservation existing(int fromDay, int toDay) {
        return Reservation.create(alice, unit, TODAY.plusDays(fromDay), TODAY.plusDays(toDay), TODAY, NOW);
    }

    @Test
    void freeWhenNothingIsBooked() {
        assertThatCode(() -> unit.assertAvailable(TODAY, TODAY.plusDays(3), List.of())).doesNotThrowAnyException();
    }

    @Test
    void refusedWhenAnActiveBookingSharesADay() {
        List<Reservation> booked = List.of(existing(2, 4));
        assertThatThrownBy(() -> unit.assertAvailable(TODAY.plusDays(4), TODAY.plusDays(6), booked))
                .isInstanceOf(DomainRuleViolation.class)
                .hasMessageContaining("CAM-R5-03")
                .extracting(e -> ((DomainRuleViolation) e).getCode())
                .isEqualTo(DomainRuleViolation.UNIT_UNAVAILABLE);
    }

    @Test
    void refusedWhenThePickedUpBookingSharesADay() {
        Reservation out = existing(0, 4);
        out.pickUp(TODAY, NOW, List.of());
        assertThatThrownBy(() -> unit.assertAvailable(TODAY.plusDays(3), TODAY.plusDays(5), List.of(out)))
                .isInstanceOf(DomainRuleViolation.class);
    }

    @Test
    void backToBackBookingsAreFine() {
        List<Reservation> booked = List.of(existing(2, 4));
        assertThatCode(() -> unit.assertAvailable(TODAY.plusDays(5), TODAY.plusDays(7), booked)).doesNotThrowAnyException();
        assertThatCode(() -> unit.assertAvailable(TODAY, TODAY.plusDays(1), booked)).doesNotThrowAnyException();
    }

    @Test
    void cancelledReturnedAndExpiredBookingsDoNotBlock() {
        Reservation cancelled = existing(0, 3);
        cancelled.cancel(NOW);
        Reservation returned = existing(0, 3);
        returned.pickUp(TODAY, NOW, List.of());
        returned.markReturned(NOW);
        Reservation expired = existing(0, 3);
        expired.expireIfUnclaimed(TODAY.plusDays(4));

        assertThatCode(() -> unit.assertAvailable(TODAY, TODAY.plusDays(3), List.of(cancelled, returned, expired)))
                .doesNotThrowAnyException();
    }

    @Test
    void outOfServiceUnitIsNeverAvailable() {
        ReflectionTestUtils.setField(unit, "active", false);
        assertThatThrownBy(() -> unit.assertAvailable(TODAY, TODAY, List.of()))
                .isInstanceOf(DomainRuleViolation.class)
                .hasMessageContaining("out of service");
    }
}
