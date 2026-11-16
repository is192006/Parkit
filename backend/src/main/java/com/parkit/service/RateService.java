package com.parkit.service;

import com.parkit.domain.entity.ParkingRate;
import com.parkit.domain.enums.VehicleType;
import com.parkit.dto.request.RateUpdateRequest;
import com.parkit.dto.response.RateResponse;
import com.parkit.exception.ResourceNotFoundException;
import com.parkit.repository.ParkingRateRepository;
import com.parkit.repository.UserRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Hourly and daily rates per category (FR-32, BR-20). One current row per
 * category; history is kept in booking snapshots and the audit log, so a rate
 * change never affects existing bookings.
 */
@Service
public class RateService {

    private final ParkingRateRepository rateRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;

    public RateService(ParkingRateRepository rateRepository, UserRepository userRepository,
                       AuditService auditService) {
        this.rateRepository = rateRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<RateResponse> listRates() {
        return rateRepository.findAll().stream()
                .sorted(Comparator.comparing(ParkingRate::getVehicleType))
                .map(RateResponse::from)
                .toList();
    }

    /** Replaces both rates of a category and audits old and new values (BR-27). */
    @Transactional
    public RateResponse updateRate(Long adminId, VehicleType vehicleType, RateUpdateRequest request) {
        ParkingRate rate = rateRepository.findByVehicleType(vehicleType)
                .orElseThrow(() -> new ResourceNotFoundException("No rate is configured for " + vehicleType + "."));
        // Money is scale 2 everywhere (ARCHITECTURE.md §2); validation already rejects > 2 decimals.
        BigDecimal hourly = request.hourlyRate().setScale(2, RoundingMode.HALF_UP);
        BigDecimal daily = request.dailyRate().setScale(2, RoundingMode.HALF_UP);
        if (rate.getHourlyRate().compareTo(hourly) == 0 && rate.getDailyRate().compareTo(daily) == 0) {
            return RateResponse.from(rate);
        }

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("vehicleType", vehicleType.name());
        details.put("oldHourlyRate", rate.getHourlyRate().toPlainString());
        details.put("newHourlyRate", hourly.toPlainString());
        details.put("oldDailyRate", rate.getDailyRate().toPlainString());
        details.put("newDailyRate", daily.toPlainString());

        rate.updateRates(hourly, daily, userRepository.getReferenceById(adminId));
        ParkingRate saved = rateRepository.saveAndFlush(rate);
        auditService.recordUserAction(adminId, AuditService.RATE_UPDATED, AuditService.ENTITY_RATE,
                saved.getId(), details);
        return RateResponse.from(saved);
    }
}
