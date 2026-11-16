package com.parkit.api;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;

/** Vehicle registration, ownership, update and deactivation (FR-09 to FR-14, BR-21). */
class VehicleApiIntegrationTest extends AbstractApiIntegrationTest {

    private long createVehicle(MockHttpSession session, String reg, String type, boolean electric) throws Exception {
        var result = mvc.perform(post("/api/vehicles").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"registrationNumber\":\"%s\",\"vehicleType\":\"%s\",\"electric\":%s}"
                                .formatted(reg, type, electric)))
                .andExpect(status().isCreated())
                .andReturn();
        return body(result).get("id").asLong();
    }

    private void update(MockHttpSession session, long id, String type, int expectedStatus) throws Exception {
        mvc.perform(put("/api/vehicles/{id}", id).session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"vehicleType\":\"%s\",\"electric\":false,\"description\":\"updated\"}"
                                .formatted(type)))
                .andExpect(status().is(expectedStatus));
    }

    @Test
    void createNormalizesRegistrationAndListsOwnVehicles() throws Exception {
        MockHttpSession session = newCustomerSession();
        String reg = uniqueRegistration();
        String messy = reg.substring(0, 2).toLowerCase() + "-" + reg.substring(2, 5) + " " + reg.substring(5);

        mvc.perform(post("/api/vehicles").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"registrationNumber\":\"%s\",\"vehicleType\":\"CAR\",\"electric\":true,\"description\":\"EV\"}"
                                .formatted(messy)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.registrationNumber").value(reg))
                .andExpect(jsonPath("$.electric").value(true))
                .andExpect(jsonPath("$.active").value(true));

        createVehicle(session, uniqueRegistration(), "BIKE", false);
        mvc.perform(get("/api/vehicles").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void registrationNumberIsGloballyUniqueAfterNormalization() throws Exception {
        String reg = uniqueRegistration();
        createVehicle(newCustomerSession(), reg, "CAR", false);
        String sameDifferentFormat = reg.substring(0, 4) + " " + reg.substring(4).toLowerCase();
        mvc.perform(post("/api/vehicles").session(newCustomerSession()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"registrationNumber\":\"%s\",\"vehicleType\":\"CAR\"}".formatted(sameDifferentFormat)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("DUPLICATE_RESOURCE"));
    }

    @Test
    void electricBikeAndInvalidInputAreRejected() throws Exception {
        MockHttpSession session = newCustomerSession();
        mvc.perform(post("/api/vehicles").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"registrationNumber\":\"%s\",\"vehicleType\":\"BIKE\",\"electric\":true}"
                                .formatted(uniqueRegistration())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.electricConsistent").exists());
        mvc.perform(post("/api/vehicles").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"registrationNumber\":\"\",\"vehicleType\":null}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/vehicles").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"registrationNumber\":\"KA@01#99\",\"vehicleType\":\"CAR\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void customerCannotSeeOrChangeAnotherCustomersVehicle() throws Exception {
        MockHttpSession owner = newCustomerSession();
        MockHttpSession other = newCustomerSession();
        long id = createVehicle(owner, uniqueRegistration(), "CAR", false);

        mvc.perform(get("/api/vehicles/{id}", id).session(other))
                .andExpect(status().isNotFound());
        update(other, id, "BIKE", 404);
        mvc.perform(post("/api/vehicles/{id}/deactivate", id).session(other).with(csrf()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/vehicles").session(other))
                .andExpect(jsonPath("$.length()").value(0));

        mvc.perform(get("/api/vehicles/{id}", id).session(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vehicleType").value("CAR"));
    }

    @Test
    void anonymousCannotAccessVehicles() throws Exception {
        mvc.perform(get("/api/vehicles")).andExpect(status().isUnauthorized());
    }

    @Test
    void updateAndDeactivateWithoutBookings() throws Exception {
        MockHttpSession session = newCustomerSession();
        long id = createVehicle(session, uniqueRegistration(), "CAR", false);

        update(session, id, "BIKE", 200);
        mvc.perform(get("/api/vehicles/{id}", id).session(session))
                .andExpect(jsonPath("$.vehicleType").value("BIKE"))
                .andExpect(jsonPath("$.description").value("updated"));

        mvc.perform(post("/api/vehicles/{id}/deactivate", id).session(session).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
        mvc.perform(post("/api/vehicles/{id}/deactivate", id).session(session).with(csrf()))
                .andExpect(status().isOk());
        update(session, id, "CAR", 422);
    }

    @Test
    void categoryChangeAndDeactivationRefusedWithFutureActiveBooking() throws Exception {
        MockHttpSession session = newCustomerSession();
        long vehicleId = createVehicle(session, uniqueRegistration(), "CAR", false);
        insertConfirmedFutureBooking(vehicleId);

        update(session, vehicleId, "BIKE", 422);
        update(session, vehicleId, "CAR", 200); // same category: description change allowed
        mvc.perform(post("/api/vehicles/{id}/deactivate", vehicleId).session(session).with(csrf()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("BOOKING_RULE_VIOLATION"));
    }

    @Test
    void adminCanListACustomersVehicles() throws Exception {
        String email = uniqueEmail();
        register(email, PASSWORD);
        MockHttpSession customer = login(email, PASSWORD);
        String reg = uniqueRegistration();
        createVehicle(customer, reg, "CAR", false);
        Long customerId = jdbc.queryForObject(
                "SELECT user_id FROM users WHERE lower(email) = lower(?)", Long.class, email);

        mvc.perform(get("/api/admin/customers/{id}/vehicles", customerId).session(adminSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].registrationNumber").value(reg));
        mvc.perform(get("/api/admin/customers/{id}/vehicles", 999_999).session(adminSession()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/admin/customers/{id}/vehicles", customerId).session(customer))
                .andExpect(status().isForbidden());
    }

    /** Inserts a CONFIRMED booking starting tomorrow on a fresh slot (on the 15-minute grid). */
    private void insertConfirmedFutureBooking(long vehicleId) {
        long userId = jdbc.queryForObject("SELECT user_id FROM vehicles WHERE vehicle_id = ?", Long.class, vehicleId);
        Integer sort = jdbc.queryForObject("SELECT COALESCE(MAX(sort_order), 0) + 1 FROM parking_slots", Integer.class);
        long slotId = jdbc.queryForObject(
                "INSERT INTO parking_slots (slot_number, sort_order, vehicle_type) VALUES (?, ?, 'CAR') RETURNING slot_id",
                Long.class, "T-" + sort, sort);
        Instant start = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);
        jdbc.update("""
                INSERT INTO bookings (booking_reference, user_id, vehicle_id, slot_id, created_by_user_id,
                    scheduled_start, scheduled_end, hold_expires_at, confirmed_at,
                    hourly_rate_snapshot, daily_rate_snapshot, booking_amount, booking_status)
                VALUES (?, ?, ?, ?, ?, ?, ?, now(), now(), 40, 200, 80, 'CONFIRMED')
                """, "TEST-" + vehicleId, userId, vehicleId, slotId, userId,
                java.sql.Timestamp.from(start), java.sql.Timestamp.from(start.plus(2, ChronoUnit.HOURS)));
    }
}
