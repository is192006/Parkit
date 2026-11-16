package com.parkit.controller.admin;

import com.parkit.domain.enums.VehicleType;
import com.parkit.dto.request.RateUpdateRequest;
import com.parkit.dto.response.RateResponse;
import com.parkit.security.AuthenticatedUser;
import com.parkit.service.RateService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Hourly and daily rates per category (FR-32). ADMIN only. */
@RestController
@RequestMapping("/api/admin/rates")
public class AdminRateController {

    private final RateService rateService;

    public AdminRateController(RateService rateService) {
        this.rateService = rateService;
    }

    @GetMapping
    public List<RateResponse> list() {
        return rateService.listRates();
    }

    @PutMapping("/{vehicleType}")
    public RateResponse update(@AuthenticationPrincipal AuthenticatedUser principal,
                               @PathVariable VehicleType vehicleType,
                               @Valid @RequestBody RateUpdateRequest request) {
        return rateService.updateRate(principal.getUserId(), vehicleType, request);
    }
}
