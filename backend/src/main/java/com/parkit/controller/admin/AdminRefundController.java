package com.parkit.controller.admin;

import com.parkit.dto.request.RefundRetryRequest;
import com.parkit.dto.response.RefundResponse;
import com.parkit.gateway.SimulatedOutcome;
import com.parkit.security.AuthenticatedUser;
import com.parkit.service.RefundService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Admin retry of failed refunds (FR-42, BR-15). ADMIN only. */
@RestController
@RequestMapping("/api/admin/refunds")
public class AdminRefundController {

    private final RefundService refundService;

    public AdminRefundController(RefundService refundService) {
        this.refundService = refundService;
    }

    /**
     * Retries a FAILED refund as a new refund record. 201 with the new refund;
     * 200 with the earlier retry if this refund was already retried; 409 if the
     * refund is not FAILED.
     */
    @PostMapping("/{id}/retry")
    public ResponseEntity<RefundResponse> retry(@AuthenticationPrincipal AuthenticatedUser principal,
                                                @PathVariable Long id,
                                                @RequestBody(required = false) RefundRetryRequest request) {
        SimulatedOutcome outcome = request == null ? null : request.simulatedRefundOutcome();
        RefundService.RetryResult result = refundService.retry(principal.getUserId(), id, outcome);
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(result.refund());
    }
}
