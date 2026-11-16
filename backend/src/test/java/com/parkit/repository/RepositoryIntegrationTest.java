package com.parkit.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.parkit.config.JpaConfig;
import com.parkit.domain.entity.AuditLog;
import com.parkit.domain.entity.Booking;
import com.parkit.domain.entity.FacilitySettings;
import com.parkit.domain.entity.ParkingRate;
import com.parkit.domain.entity.ParkingSlot;
import com.parkit.domain.entity.Payment;
import com.parkit.domain.entity.Refund;
import com.parkit.domain.entity.SlotBlock;
import com.parkit.domain.entity.User;
import com.parkit.domain.entity.Vehicle;
import com.parkit.domain.enums.ActorType;
import com.parkit.domain.enums.BookingStatus;
import com.parkit.domain.enums.PaymentMethod;
import com.parkit.domain.enums.PaymentStatus;
import com.parkit.domain.enums.PaymentType;
import com.parkit.domain.enums.SlotStatus;
import com.parkit.domain.enums.UserRole;
import com.parkit.domain.enums.VehicleType;
import jakarta.persistence.EntityManager;
import jakarta.validation.ConstraintViolationException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Entity-mapping and repository tests on a real PostgreSQL 16 container.
 * Flyway applies V1-V3; Hibernate runs with ddl-auto=validate, so the
 * context only starts if every entity matches the schema. Each test rolls back.
 */
@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaConfig.class, RepositoryIntegrationTest.FixedClockConfig.class})
class RepositoryIntegrationTest {

    static final Instant NOW = Instant.parse("2026-12-01T03:30:00Z");

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @Autowired private EntityManager em;
    @Autowired private UserRepository users;
    @Autowired private VehicleRepository vehicles;
    @Autowired private ParkingSlotRepository slots;
    @Autowired private BookingRepository bookings;
    @Autowired private PaymentRepository payments;
    @Autowired private RefundRepository refunds;
    @Autowired private ParkingRateRepository rates;
    @Autowired private SlotBlockRepository blocks;
    @Autowired private FacilitySettingsRepository facility;
    @Autowired private AuditLogRepository auditLogs;

    // ---------- seed data (V3) ----------

    @Test
    void readsSeededFacilitySettings() {
        FacilitySettings s = facility.findSettings().orElseThrow();
        assertThat(s.getFacilityName()).isEqualTo("ParkIt");
        assertThat(s.isOpen24Hours()).isTrue();
        assertThat(s.getOpeningTime()).isEqualTo(LocalTime.MIDNIGHT);
        assertThat(s.getClosingTime()).isEqualTo(LocalTime.MIDNIGHT);
        assertThat(s.getZoneId().getId()).isEqualTo("Asia/Kolkata");
        assertThat(s.getUpdatedBy()).isNull();
    }

    @Test
    void facilityHoursRoundTripWithoutTimeZoneShift() {
        FacilitySettings s = facility.findSettings().orElseThrow();
        s.updateSettings("ParkIt", LocalTime.of(18, 0), LocalTime.of(2, 0), false, null);
        facility.saveAndFlush(s);
        em.clear();

        String raw = (String) em.createNativeQuery(
                "SELECT opening_time::text || '-' || closing_time::text FROM facility_settings").getSingleResult();
        assertThat(raw).isEqualTo("18:00:00-02:00:00");
        FacilitySettings reloaded = facility.findSettings().orElseThrow();
        assertThat(reloaded.getOpeningTime()).isEqualTo(LocalTime.of(18, 0));
        assertThat(reloaded.getClosingTime()).isEqualTo(LocalTime.of(2, 0));
    }

    @Test
    void readsSeededRatesAsBigDecimal() {
        ParkingRate car = rates.findByVehicleType(VehicleType.CAR).orElseThrow();
        ParkingRate bike = rates.findByVehicleType(VehicleType.BIKE).orElseThrow();
        assertThat(car.getHourlyRate()).isEqualByComparingTo("40.00");
        assertThat(car.getDailyRate()).isEqualByComparingTo("200.00");
        assertThat(bike.getHourlyRate()).isEqualByComparingTo("20.00");
        assertThat(bike.getDailyRate()).isEqualByComparingTo("100.00");
    }

    @Test
    void rateUpdateRecordsAdminAndClockTime() {
        User admin = users.save(new User("Admin", "admin@example.com", "hash", "9000000000", UserRole.ADMIN));
        ParkingRate car = rates.findByVehicleType(VehicleType.CAR).orElseThrow();
        car.updateRates(new BigDecimal("45.00"), new BigDecimal("210.00"), admin);
        rates.saveAndFlush(car);
        em.clear();

        ParkingRate reloaded = rates.findByVehicleType(VehicleType.CAR).orElseThrow();
        assertThat(reloaded.getHourlyRate()).isEqualByComparingTo("45.00");
        assertThat(reloaded.getUpdatedBy().getId()).isEqualTo(admin.getId());
        assertThat(reloaded.getUpdatedAt()).isEqualTo(NOW);
    }

    // ---------- users and vehicles ----------

    @Test
    void savesUserWithClockTimestampsAndFindsByEmailIgnoringCase() {
        User u = users.saveAndFlush(customer("asha@example.com"));
        assertThat(u.getId()).isNotNull();
        assertThat(u.getCreatedAt()).isEqualTo(NOW);
        assertThat(u.getUpdatedAt()).isEqualTo(NOW);
        assertThat(u.getRole()).isEqualTo(UserRole.CUSTOMER);
        assertThat(users.findByEmailIgnoreCase("ASHA@Example.com")).contains(u);
        assertThat(users.existsByEmailIgnoreCase("asha@EXAMPLE.com")).isTrue();
    }

    @Test
    void rejectsEmailDifferingOnlyInCase() {
        users.saveAndFlush(customer("dup@example.com"));
        assertThatThrownBy(() -> users.saveAndFlush(customer("DUP@example.com")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void normalizesRegistrationAndFindsVehiclesByOwner() {
        User owner = users.save(customer("owner@example.com"));
        Vehicle v = vehicles.saveAndFlush(new Vehicle(owner, "ka-01 ab 1234", VehicleType.CAR, true, "EV"));
        assertThat(v.getRegistrationNumber()).isEqualTo("KA01AB1234");
        assertThat(vehicles.existsByRegistrationNumber("KA01AB1234")).isTrue();
        assertThat(vehicles.findByOwnerIdOrderByCreatedAtAsc(owner.getId())).containsExactly(v);
        assertThat(vehicles.findByIdAndOwnerId(v.getId(), owner.getId())).contains(v);
        assertThat(vehicles.findByIdAndOwnerId(v.getId(), owner.getId() + 999)).isEmpty();
    }

    @Test
    void beanValidationRejectsElectricBike() {
        User owner = users.save(customer("bike@example.com"));
        assertThatThrownBy(() -> vehicles.saveAndFlush(
                new Vehicle(owner, "KA01X1", VehicleType.BIKE, true, null)))
                .isInstanceOf(ConstraintViolationException.class)
                .hasMessageContaining("only a CAR can be electric");
    }

    // ---------- slots and blocks ----------

    @Test
    void savesSlotsAndListsBySortOrder() {
        slots.save(new ParkingSlot("C-02", 2, VehicleType.CAR));
        slots.save(new ParkingSlot("C-01", 1, VehicleType.CAR));
        slots.flush();
        assertThat(slots.findAllByOrderBySortOrderAsc()).extracting(ParkingSlot::getSlotNumber)
                .containsExactly("C-01", "C-02");
        assertThat(slots.findBySlotNumber("C-01").orElseThrow().getOperationalStatus())
                .isEqualTo(SlotStatus.ACTIVE);
        assertThat(slots.existsBySortOrder(2)).isTrue();
    }

    @Test
    void savesSlotBlock() {
        User admin = users.save(new User("Admin", "a2@example.com", "hash", "9000000001", UserRole.ADMIN));
        ParkingSlot slot = slots.save(new ParkingSlot("B-01", 10, VehicleType.BIKE));
        SlotBlock block = blocks.saveAndFlush(new SlotBlock(slot, NOW, NOW.plus(Duration.ofHours(2)),
                "Maintenance", admin));
        assertThat(block.isActive()).isTrue();
        assertThat(block.getCreatedAt()).isEqualTo(NOW);
        assertThat(blocks.findBySlotIdOrderByStartTimeAsc(slot.getId())).containsExactly(block);
    }

    // ---------- bookings ----------

    @Test
    void savesPendingBookingWithAllRelationships() {
        Fixture f = fixture("bk@example.com", "KA02CD5678", "C-10", 20);
        Booking b = bookings.saveAndFlush(f.booking("REF-0001"));
        em.clear();

        Booking reloaded = bookings.findByBookingReference("REF-0001").orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(BookingStatus.PENDING_PAYMENT);
        assertThat(reloaded.getCustomer().getId()).isEqualTo(f.customer.getId());
        assertThat(reloaded.getVehicle().getId()).isEqualTo(f.vehicle.getId());
        assertThat(reloaded.getSlot().getId()).isEqualTo(f.slot.getId());
        assertThat(reloaded.getCreatedBy().getId()).isEqualTo(f.customer.getId());
        assertThat(reloaded.getBookingAmount()).isEqualByComparingTo("80.00");
        assertThat(reloaded.getOverstayAmount()).isEqualByComparingTo("0.00");
        assertThat(reloaded.getHoldExpiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(5)));
        assertThat(reloaded.getConfirmedAt()).isNull();
        assertThat(reloaded.getVersion()).isZero();
        assertThat(reloaded.getId()).isEqualTo(b.getId());
    }

    @Test
    void compositeForeignKeyRejectsAnotherCustomersVehicle() {
        Fixture f = fixture("own@example.com", "KA03EF0001", "C-11", 21);
        User other = users.save(customer("other@example.com"));
        Booking b = new Booking("REF-0002", other, f.vehicle, f.slot, other,
                f.start, f.start.plus(Duration.ofHours(2)), NOW.plus(Duration.ofMinutes(5)),
                new BigDecimal("40.00"), new BigDecimal("200.00"), new BigDecimal("80.00"));
        assertThatThrownBy(() -> bookings.saveAndFlush(b))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_booking_vehicle_owner");
    }

    @Test
    void oneActiveHoldPerCustomer() {
        Fixture f = fixture("hold@example.com", "KA04GH0001", "C-12", 22);
        Vehicle second = vehicles.save(new Vehicle(f.customer, "KA04GH0002", VehicleType.CAR, false, null));
        ParkingSlot slot2 = slots.save(new ParkingSlot("C-13", 23, VehicleType.CAR));
        bookings.saveAndFlush(f.booking("REF-0003"));
        Booking another = new Booking("REF-0004", f.customer, second, slot2, f.customer,
                f.start.plus(Duration.ofDays(1)), f.start.plus(Duration.ofDays(1)).plus(Duration.ofHours(1)),
                NOW.plus(Duration.ofMinutes(5)),
                new BigDecimal("40.00"), new BigDecimal("200.00"), new BigDecimal("40.00"));
        assertThatThrownBy(() -> bookings.saveAndFlush(another))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ux_one_hold_per_customer");
    }

    // ---------- payments and refunds ----------

    @Test
    void savesPaymentAndRefundAndEnforcesSinglePending() {
        Fixture f = fixture("pay@example.com", "KA05IJ0001", "C-14", 24);
        Booking b = bookings.save(f.booking("REF-0005"));
        Payment p = payments.saveAndFlush(new Payment(b, PaymentType.BOOKING, PaymentMethod.ONLINE_SIMULATED,
                new BigDecimal("80.00"), "idem-1", null));
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(p.getCreatedAt()).isEqualTo(NOW);
        assertThat(p.getCompletedAt()).isNull();
        assertThat(payments.findByIdempotencyKey("idem-1")).contains(p);
        assertThat(payments.findByBookingIdOrderByCreatedAtAsc(b.getId())).containsExactly(p);

        Refund r = refunds.saveAndFlush(new Refund(p, p.getAmount(), "test", "ridem-1", null));
        assertThat(refunds.findByPaymentIdOrderByCreatedAtAsc(p.getId())).containsExactly(r);

        assertThatThrownBy(() -> payments.saveAndFlush(new Payment(b, PaymentType.BOOKING,
                PaymentMethod.ONLINE_SIMULATED, new BigDecimal("80.00"), "idem-2", null)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ux_one_pending_per_type");
    }

    @Test
    void preventsDuplicateLiveRefund() {
        Fixture f = fixture("ref@example.com", "KA06KL0001", "C-15", 25);
        Booking b = bookings.save(f.booking("REF-0006"));
        Payment p = payments.save(new Payment(b, PaymentType.BOOKING, PaymentMethod.ONLINE_SIMULATED,
                new BigDecimal("80.00"), "idem-3", null));
        refunds.saveAndFlush(new Refund(p, p.getAmount(), "first", "ridem-2", null));
        assertThatThrownBy(() -> refunds.saveAndFlush(new Refund(p, p.getAmount(), "second", "ridem-3", null)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ux_one_live_refund");
    }

    // ---------- audit log ----------

    @Test
    void auditLogJsonbDetailsRoundTrip() {
        User admin = users.save(new User("Admin", "a3@example.com", "hash", "9000000002", UserRole.ADMIN));
        auditLogs.saveAndFlush(AuditLog.byUser(admin, "SLOT_REASSIGNED", "BOOKING", 42L,
                Map.of("oldSlot", "C-01", "newSlot", "C-02")));
        auditLogs.saveAndFlush(AuditLog.bySystem("HOLD_EXPIRED", "BOOKING", 42L, null));
        em.clear();

        var logs = auditLogs.findByEntityTypeAndEntityIdOrderByCreatedAtAsc("BOOKING", 42L);
        assertThat(logs).hasSize(2);
        AuditLog userLog = logs.stream().filter(l -> l.getActorType() == ActorType.USER).findFirst().orElseThrow();
        assertThat(userLog.getDetails()).containsEntry("oldSlot", "C-01").containsEntry("newSlot", "C-02");
        assertThat(userLog.getCreatedAt()).isEqualTo(NOW);
        assertThat(logs).anySatisfy(l -> assertThat(l.getActor()).isNull());
    }

    // ---------- helpers ----------

    private static User customer(String email) {
        return new User("Customer", email, "$2a$10$hash", "9876543210", UserRole.CUSTOMER);
    }

    private Fixture fixture(String email, String reg, String slotNumber, int sortOrder) {
        User c = users.save(customer(email));
        Vehicle v = vehicles.save(new Vehicle(c, reg, VehicleType.CAR, false, null));
        ParkingSlot s = slots.save(new ParkingSlot(slotNumber, sortOrder, VehicleType.CAR));
        return new Fixture(c, v, s, NOW.plus(Duration.ofHours(1)));
    }

    /** Test data for a booking; times are on the 15-minute grid (ck_grid). */
    private record Fixture(User customer, Vehicle vehicle, ParkingSlot slot, Instant start) {
        Booking booking(String ref) {
            return new Booking(ref, customer, vehicle, slot, customer,
                    start, start.plus(Duration.ofHours(2)), NOW.plus(Duration.ofMinutes(5)),
                    new BigDecimal("40.00"), new BigDecimal("200.00"), new BigDecimal("80.00"));
        }
    }
}
