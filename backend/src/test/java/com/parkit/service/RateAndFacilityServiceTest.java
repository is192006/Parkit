package com.parkit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.parkit.domain.entity.FacilitySettings;
import com.parkit.domain.entity.ParkingRate;
import com.parkit.domain.entity.User;
import com.parkit.domain.enums.VehicleType;
import com.parkit.dto.request.FacilitySettingsUpdateRequest;
import com.parkit.dto.request.RateUpdateRequest;
import com.parkit.dto.response.RateResponse;
import com.parkit.exception.ResourceNotFoundException;
import com.parkit.repository.FacilitySettingsRepository;
import com.parkit.repository.ParkingRateRepository;
import com.parkit.repository.UserRepository;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

/** Unit tests of rate (FR-32) and facility settings (FR-20) updates and their audit details (BR-27). */
class RateAndFacilityServiceTest {

    private static final long ADMIN = 1L;

    private final ParkingRateRepository rates = mock(ParkingRateRepository.class);
    private final FacilitySettingsRepository settings = mock(FacilitySettingsRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final AuditService audit = mock(AuditService.class);
    private final RateService rateService = new RateService(rates, users, audit);
    private final FacilityService facilityService = new FacilityService(settings, rates, users, audit);

    // ---------------------------------------------------------------- rates

    @Test
    @SuppressWarnings("unchecked")
    void rateUpdateIsScaledAndAuditedWithOldAndNewValues() {
        ParkingRate rate = new ParkingRate(VehicleType.CAR, new BigDecimal("40.00"), new BigDecimal("200.00"), null);
        ReflectionTestUtils.setField(rate, "id", 3L);
        when(rates.findByVehicleType(VehicleType.CAR)).thenReturn(Optional.of(rate));
        when(rates.saveAndFlush(rate)).thenReturn(rate);
        when(users.getReferenceById(ADMIN)).thenReturn(mock(User.class));

        RateResponse response = rateService.updateRate(ADMIN, VehicleType.CAR,
                new RateUpdateRequest(new BigDecimal("45"), new BigDecimal("250.5")));

        assertThat(response.hourlyRate()).isEqualTo(new BigDecimal("45.00"));
        assertThat(response.dailyRate()).isEqualTo(new BigDecimal("250.50"));
        ArgumentCaptor<Map<String, Object>> details = ArgumentCaptor.forClass(Map.class);
        verify(audit).recordUserAction(eq(ADMIN), eq(AuditService.RATE_UPDATED), eq(AuditService.ENTITY_RATE),
                eq(3L), details.capture());
        assertThat(details.getValue())
                .containsEntry("oldHourlyRate", "40.00").containsEntry("newHourlyRate", "45.00")
                .containsEntry("oldDailyRate", "200.00").containsEntry("newDailyRate", "250.50");
    }

    @Test
    void unchangedRateIsNotSavedOrAudited() {
        ParkingRate rate = new ParkingRate(VehicleType.BIKE, new BigDecimal("20.00"), new BigDecimal("100.00"), null);
        when(rates.findByVehicleType(VehicleType.BIKE)).thenReturn(Optional.of(rate));

        rateService.updateRate(ADMIN, VehicleType.BIKE, new RateUpdateRequest(new BigDecimal("20"), new BigDecimal("100")));

        verify(rates, never()).saveAndFlush(any());
        verifyNoInteractions(audit);
    }

    @Test
    void missingRateRowIsNotFound() {
        when(rates.findByVehicleType(VehicleType.BIKE)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> rateService.updateRate(ADMIN, VehicleType.BIKE,
                new RateUpdateRequest(BigDecimal.TEN, BigDecimal.TEN)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ===================================================== facility settings

    @Test
    @SuppressWarnings("unchecked")
    void facilityUpdateAuditsOnlyChangedFields() {
        FacilitySettings current = seededSettings();
        when(settings.findSettings()).thenReturn(Optional.of(current));
        when(settings.saveAndFlush(current)).thenReturn(current);
        when(users.getReferenceById(ADMIN)).thenReturn(mock(User.class));

        facilityService.updateSettings(ADMIN, new FacilitySettingsUpdateRequest(
                " ParkIt ", LocalTime.of(18, 0), LocalTime.of(2, 0), false));

        assertThat(current.getOpeningTime()).isEqualTo(LocalTime.of(18, 0));
        assertThat(current.isOpen24Hours()).isFalse();
        ArgumentCaptor<Map<String, Object>> details = ArgumentCaptor.forClass(Map.class);
        verify(audit).recordUserAction(eq(ADMIN), eq(AuditService.FACILITY_SETTINGS_UPDATED),
                eq(AuditService.ENTITY_FACILITY), eq(1L), details.capture());
        // The name was unchanged after trimming, so it is not part of the diff.
        assertThat(details.getValue()).containsOnlyKeys("openingTime", "closingTime", "open24Hours");
    }

    @Test
    void unchangedFacilitySettingsAreNotAudited() {
        FacilitySettings current = seededSettings();
        when(settings.findSettings()).thenReturn(Optional.of(current));

        facilityService.updateSettings(ADMIN, new FacilitySettingsUpdateRequest(
                "ParkIt", LocalTime.MIDNIGHT, LocalTime.MIDNIGHT, true));

        verify(settings, never()).saveAndFlush(any());
        verifyNoInteractions(audit);
    }

    @Test
    void missingSettingsRowIsAServerFault() {
        when(settings.findSettings()).thenReturn(Optional.empty());
        assertThatThrownBy(facilityService::getSettings).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void diffRecordsOnlyChangedValues() {
        Map<String, Object> changes = new LinkedHashMap<>();
        AuditService.diff(changes, "same", 1, 1);
        AuditService.diff(changes, "changed", "a", "b");
        assertThat(changes).containsOnlyKeys("changed");
        assertThat(changes.get("changed")).isEqualTo(Map.of("old", "a", "new", "b"));
    }

    /** Mirrors the V3 seed: 24/7, named ParkIt. */
    private static FacilitySettings seededSettings() {
        FacilitySettings s = org.springframework.beans.BeanUtils.instantiateClass(FacilitySettings.class);
        ReflectionTestUtils.setField(s, "id", FacilitySettings.SINGLETON_ID);
        ReflectionTestUtils.setField(s, "facilityName", "ParkIt");
        ReflectionTestUtils.setField(s, "openingTime", LocalTime.MIDNIGHT);
        ReflectionTestUtils.setField(s, "closingTime", LocalTime.MIDNIGHT);
        ReflectionTestUtils.setField(s, "open24Hours", true);
        ReflectionTestUtils.setField(s, "timezone", "Asia/Kolkata");
        return s;
    }
}
