package com.kitdesk.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.kitdesk.support.ApiTestBase;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CatalogueApiTest extends ApiTestBase {

    @Test
    void categoriesComeWithTheirLimits() {
        Resp r = get("/categories", token("alice@kitdesk.test"));
        assertThat(r.status()).isEqualTo(200);
        JsonNode list = r.body().path("categories");
        assertThat(list).hasSize(4);
        assertThat(list.get(0).path("id").asText()).startsWith("cat_");
        assertThat(field(list, "code")).containsExactly("CAMERA", "LENS", "LIGHTING", "SOUND");
        assertThat(field(list, "rentalLimit")).containsExactly("1", "2", "1", "2");
    }

    @Test
    void modelsCanBeFilteredByCategory() {
        String token = token("alice@kitdesk.test");
        assertThat(get("/models", token).body().path("models")).hasSize(7);

        JsonNode lenses = get("/models?category=LENS", token).body().path("models");
        assertThat(field(lenses, "name")).containsExactly("Canon RF 24-70mm f/2.8", "Canon RF 50mm f/1.8");
        assertThat(field(lenses, "unitCount")).containsExactly("2", "3");
        assertThat(field(lenses, "category")).containsOnly("LENS");
    }

    @Test
    void unknownCategoryIs400() {
        Resp r = get("/models?category=DRONES", token("alice@kitdesk.test"));
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.code()).isEqualTo("INVALID_REQUEST");
    }

    @Test
    void unitsShowOnlyTheDatesTheyAreOccupied() {
        insertReservation("bob@kitdesk.test", "CAM-R5-01", TODAY.plusDays(1), TODAY.plusDays(3), "RESERVED");
        insertReservation("bob@kitdesk.test", "CAM-R5-02", TODAY.plusDays(1), TODAY.plusDays(3), "CANCELLED");
        String token = token("alice@kitdesk.test");
        String r5 = get("/models?category=CAMERA", token).body().path("models").get(0).path("id").asText();

        JsonNode units = get("/models/" + r5 + "/units", token).body().path("units");
        assertThat(field(units, "assetTag")).containsExactly("CAM-R5-01", "CAM-R5-02", "CAM-R5-03");
        assertThat(units.get(0).path("modelId").asText()).isEqualTo(r5);
        assertThat(units.get(0).path("reservations")).hasSize(1);
        assertThat(units.get(0).path("reservations").get(0).path("startDate").asText())
                .isEqualTo(TODAY.plusDays(1).toString());
        assertThat(units.get(1).path("reservations")).isEmpty();   // cancelled doesn't occupy
        // Who booked it is not shown to other students.
        assertThat(units.get(0).path("reservations").get(0).has("studentId")).isFalse();
    }

    @Test
    void anOverdueUnitShowsItIsBusyUntilItComesBack() {
        insertReservation("bob@kitdesk.test", "CAM-R5-01", TODAY.minusDays(4), TODAY.minusDays(2), "PICKED_UP");
        insertReservation("bob@kitdesk.test", "CAM-R5-02", TODAY, TODAY.plusDays(2), "PICKED_UP");
        String token = token("alice@kitdesk.test");
        String r5 = get("/models?category=CAMERA", token).body().path("models").get(0).path("id").asText();

        JsonNode units = get("/models/" + r5 + "/units", token).body().path("units");
        JsonNode overdue = units.get(0).path("reservations").get(0);
        assertThat(overdue.path("endDate").asText()).isEqualTo(TODAY.minusDays(2).toString());
        assertThat(overdue.path("occupiedUntil").asText()).isEqualTo(TODAY.toString());
        assertThat(overdue.path("notReturned").asBoolean()).isTrue();
        JsonNode onTime = units.get(1).path("reservations").get(0);
        assertThat(onTime.path("occupiedUntil").asText()).isEqualTo(TODAY.plusDays(2).toString());
        assertThat(onTime.path("notReturned").asBoolean()).isFalse();
    }

    @Test
    void anOutOfServiceUnitIsListedButMarkedAsNotInService() {
        jdbc.update("UPDATE units SET active = FALSE WHERE asset_tag = 'CAM-R5-03'");
        String token = token("alice@kitdesk.test");
        String r5 = get("/models?category=CAMERA", token).body().path("models").get(0).path("id").asText();

        JsonNode units = get("/models/" + r5 + "/units", token).body().path("units");
        assertThat(field(units, "assetTag")).containsExactly("CAM-R5-01", "CAM-R5-02", "CAM-R5-03");
        assertThat(field(units, "inService")).containsExactly("true", "true", "false");
    }

    @Test
    void unknownModelIs404() {
        Resp r = get("/models/mdl_nope/units", token("alice@kitdesk.test"));
        assertThat(r.status()).isEqualTo(404);
        assertThat(r.code()).isEqualTo("NOT_FOUND");
    }

    static List<String> field(JsonNode list, String name) {
        List<String> out = new ArrayList<>();
        list.forEach(n -> out.add(n.path(name).asText()));
        return out;
    }
}
