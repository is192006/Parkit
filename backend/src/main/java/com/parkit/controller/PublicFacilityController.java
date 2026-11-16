package com.parkit.controller;

import com.parkit.dto.response.PublicFacilityResponse;
import com.parkit.service.FacilityService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Facility name, hours and current rates, readable without login (FR-21). */
@RestController
@RequestMapping("/api/facility")
public class PublicFacilityController {

    private final FacilityService facilityService;

    public PublicFacilityController(FacilityService facilityService) {
        this.facilityService = facilityService;
    }

    @GetMapping
    public PublicFacilityResponse get() {
        return facilityService.getPublicInfo();
    }
}
