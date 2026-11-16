package com.parkit.controller;

import com.parkit.dto.request.VehicleCreateRequest;
import com.parkit.dto.request.VehicleUpdateRequest;
import com.parkit.dto.response.VehicleResponse;
import com.parkit.security.AuthenticatedUser;
import com.parkit.service.VehicleService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Customer vehicles (FR-09 to FR-12). Restricted to CUSTOMER; ownership checked in the service. */
@RestController
@RequestMapping("/api/vehicles")
public class VehicleController {

    private final VehicleService vehicleService;

    public VehicleController(VehicleService vehicleService) {
        this.vehicleService = vehicleService;
    }

    @GetMapping
    public List<VehicleResponse> list(@AuthenticationPrincipal AuthenticatedUser principal) {
        return vehicleService.listOwn(principal.getUserId());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public VehicleResponse create(@AuthenticationPrincipal AuthenticatedUser principal,
                                  @Valid @RequestBody VehicleCreateRequest request) {
        return vehicleService.create(principal.getUserId(), request);
    }

    @GetMapping("/{id}")
    public VehicleResponse get(@AuthenticationPrincipal AuthenticatedUser principal, @PathVariable Long id) {
        return vehicleService.getOwn(principal.getUserId(), id);
    }

    @PutMapping("/{id}")
    public VehicleResponse update(@AuthenticationPrincipal AuthenticatedUser principal, @PathVariable Long id,
                                  @Valid @RequestBody VehicleUpdateRequest request) {
        return vehicleService.update(principal.getUserId(), id, request);
    }

    @PostMapping("/{id}/deactivate")
    public VehicleResponse deactivate(@AuthenticationPrincipal AuthenticatedUser principal, @PathVariable Long id) {
        return vehicleService.deactivate(principal.getUserId(), id);
    }
}
