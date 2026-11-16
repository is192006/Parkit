package com.parkit.controller.admin;

import com.parkit.dto.request.SlotBlockCreateRequest;
import com.parkit.dto.request.SlotCreateRequest;
import com.parkit.dto.request.SlotUpdateRequest;
import com.parkit.dto.response.PageResponse;
import com.parkit.dto.response.SlotBlockResponse;
import com.parkit.dto.response.SlotResponse;
import com.parkit.security.AuthenticatedUser;
import com.parkit.service.SlotService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
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

/** Admin slot inventory and per-slot blocks (FR-15 to FR-18). ADMIN only, via the admin URL rule. */
@RestController
@RequestMapping("/api/admin/slots")
public class AdminSlotController {

    private final SlotService slotService;

    public AdminSlotController(SlotService slotService) {
        this.slotService = slotService;
    }

    @GetMapping
    public PageResponse<SlotResponse> list(
            @PageableDefault(size = 50, sort = "sortOrder", direction = Sort.Direction.ASC) Pageable pageable) {
        return slotService.listSlots(pageable);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SlotResponse create(@AuthenticationPrincipal AuthenticatedUser principal,
                               @Valid @RequestBody SlotCreateRequest request) {
        return slotService.createSlot(principal.getUserId(), request);
    }

    @PutMapping("/{id}")
    public SlotResponse update(@AuthenticationPrincipal AuthenticatedUser principal, @PathVariable Long id,
                               @Valid @RequestBody SlotUpdateRequest request) {
        return slotService.updateSlot(principal.getUserId(), id, request);
    }

    @GetMapping("/{id}/blocks")
    public List<SlotBlockResponse> blocks(@PathVariable Long id) {
        return slotService.listBlocks(id);
    }

    @PostMapping("/{id}/blocks")
    @ResponseStatus(HttpStatus.CREATED)
    public SlotBlockResponse createBlock(@AuthenticationPrincipal AuthenticatedUser principal, @PathVariable Long id,
                                         @Valid @RequestBody SlotBlockCreateRequest request) {
        return slotService.createBlock(principal.getUserId(), id, request);
    }
}
