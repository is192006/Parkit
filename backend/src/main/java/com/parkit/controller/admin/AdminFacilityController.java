package com.parkit.controller.admin;

import com.parkit.dto.request.FacilitySettingsUpdateRequest;
import com.parkit.dto.response.FacilitySettingsResponse;
import com.parkit.security.AuthenticatedUser;
import com.parkit.service.FacilityService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Facility name and operating hours (FR-20). ADMIN only. */
@RestController
@RequestMapping("/api/admin/facility-settings")
public class AdminFacilityController {

    private final FacilityService facilityService;

    public AdminFacilityController(FacilityService facilityService) {
        this.facilityService = facilityService;
    }

    @GetMapping
    public FacilitySettingsResponse get() {
        return facilityService.getSettings();
    }

    @PutMapping
    public FacilitySettingsResponse update(@AuthenticationPrincipal AuthenticatedUser principal,
                                           @Valid @RequestBody FacilitySettingsUpdateRequest request) {
        return facilityService.updateSettings(principal.getUserId(), request);
    }
}
