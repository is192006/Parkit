package com.parkit.service;

import com.parkit.domain.entity.FacilitySettings;
import com.parkit.domain.entity.ParkingRate;
import com.parkit.domain.policy.OperatingHoursPolicy;
import com.parkit.dto.request.FacilitySettingsUpdateRequest;
import com.parkit.dto.response.FacilitySettingsResponse;
import com.parkit.dto.response.PublicFacilityResponse;
import com.parkit.repository.FacilitySettingsRepository;
import com.parkit.repository.ParkingRateRepository;
import com.parkit.repository.UserRepository;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Facility settings (FR-20, BR-01) and the public facility view (FR-21).
 * The single settings row is seeded by migration V3 and only ever updated.
 */
@Service
public class FacilityService {

    private final FacilitySettingsRepository settingsRepository;
    private final ParkingRateRepository rateRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;

    public FacilityService(FacilitySettingsRepository settingsRepository, ParkingRateRepository rateRepository,
                           UserRepository userRepository, AuditService auditService) {
        this.settingsRepository = settingsRepository;
        this.rateRepository = rateRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public FacilitySettingsResponse getSettings() {
        return FacilitySettingsResponse.from(load());
    }

    /**
     * Updates name and hours. Existing bookings are not re-validated: BR-06.5 is
     * checked when a booking is created, like every other booking rule. The
     * change is audited with old and new values (BR-27).
     */
    @Transactional
    public FacilitySettingsResponse updateSettings(Long adminId, FacilitySettingsUpdateRequest request) {
        FacilitySettings settings = load();
        String newName = request.facilityName().trim();

        Map<String, Object> changes = new LinkedHashMap<>();
        AuditService.diff(changes, "facilityName", settings.getFacilityName(), newName);
        AuditService.diff(changes, "openingTime", settings.getOpeningTime(), request.openingTime());
        AuditService.diff(changes, "closingTime", settings.getClosingTime(), request.closingTime());
        AuditService.diff(changes, "open24Hours", settings.isOpen24Hours(), request.open24Hours());
        if (changes.isEmpty()) {
            return FacilitySettingsResponse.from(settings);
        }

        settings.updateSettings(newName, request.openingTime(), request.closingTime(), request.open24Hours(),
                userRepository.getReferenceById(adminId));
        FacilitySettings saved = settingsRepository.saveAndFlush(settings);
        auditService.recordUserAction(adminId, AuditService.FACILITY_SETTINGS_UPDATED,
                AuditService.ENTITY_FACILITY, saved.getId().longValue(), changes);
        return FacilitySettingsResponse.from(saved);
    }

    /** Name, hours and current rates, without login (FR-21). */
    @Transactional(readOnly = true)
    public PublicFacilityResponse getPublicInfo() {
        FacilitySettings settings = load();
        List<PublicFacilityResponse.Rate> rates = rateRepository.findAll().stream()
                .sorted(Comparator.comparing(ParkingRate::getVehicleType))
                .map(r -> new PublicFacilityResponse.Rate(r.getVehicleType(), r.getHourlyRate(), r.getDailyRate()))
                .toList();
        return new PublicFacilityResponse(settings.getFacilityName(), settings.getOpeningTime(),
                settings.getClosingTime(), settings.isOpen24Hours(), settings.getTimezone(), rates);
    }

    /** Current operating hours as a policy object (BR-06.5). */
    @Transactional(readOnly = true)
    public OperatingHoursPolicy getOperatingHours() {
        FacilitySettings s = load();
        return new OperatingHoursPolicy(s.getOpeningTime(), s.getClosingTime(), s.isOpen24Hours());
    }

    /** Facility time zone, used to convert facility-local input and output (BR-28). */
    @Transactional(readOnly = true)
    public ZoneId getZoneId() {
        return load().getZoneId();
    }

    private FacilitySettings load() {
        // The row is created by migration V3; its absence is a deployment fault (-> 500), not a client error.
        return settingsRepository.findSettings()
                .orElseThrow(() -> new IllegalStateException("Facility settings row is missing"));
    }
}
