package com.parkit.controller.admin;

import com.parkit.dto.response.PageResponse;
import com.parkit.dto.response.UserResponse;
import com.parkit.dto.response.VehicleResponse;
import com.parkit.service.UserService;
import com.parkit.service.VehicleService;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Admin customer search and vehicles (SRS §2.2). ADMIN only, via the /api/admin/** URL rule. */
@RestController
@RequestMapping("/api/admin/customers")
public class AdminCustomerController {

    private final UserService userService;
    private final VehicleService vehicleService;

    public AdminCustomerController(UserService userService, VehicleService vehicleService) {
        this.userService = userService;
        this.vehicleService = vehicleService;
    }

    @GetMapping
    public PageResponse<UserResponse> search(@RequestParam(name = "q", required = false) String q,
                                             @PageableDefault(size = 20, sort = "name", direction = Sort.Direction.ASC)
                                             Pageable pageable) {
        return userService.searchCustomers(q, pageable);
    }

    @GetMapping("/{id}/vehicles")
    public List<VehicleResponse> vehicles(@PathVariable Long id) {
        return vehicleService.listForCustomer(id);
    }
}
