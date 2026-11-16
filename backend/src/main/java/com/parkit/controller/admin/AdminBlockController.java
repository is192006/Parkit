package com.parkit.controller.admin;

import com.parkit.dto.response.SlotBlockResponse;
import com.parkit.security.AuthenticatedUser;
import com.parkit.service.SlotService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Cancelling (unblocking) a slot block (FR-18, BR-22). ADMIN only. */
@RestController
@RequestMapping("/api/admin/blocks")
public class AdminBlockController {

    private final SlotService slotService;

    public AdminBlockController(SlotService slotService) {
        this.slotService = slotService;
    }

    @PostMapping("/{id}/cancel")
    public SlotBlockResponse cancel(@AuthenticationPrincipal AuthenticatedUser principal, @PathVariable Long id) {
        return slotService.cancelBlock(principal.getUserId(), id);
    }
}
