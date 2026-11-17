package com.parkit.repository;

import com.parkit.domain.entity.Refund;
import com.parkit.domain.enums.RefundStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Data access for {@link com.parkit.domain.entity.Refund}. */
public interface RefundRepository extends JpaRepository<Refund, Long> {

    Optional<Refund> findByIdempotencyKey(String idempotencyKey);

    List<Refund> findByPaymentIdOrderByCreatedAtAsc(Long paymentId);

    /** True if a PENDING or SUCCESS ("live") refund exists; mirrors the ux_one_live_refund index. */
    boolean existsByPaymentIdAndStatusIn(Long paymentId, Collection<RefundStatus> statuses);

    /** All refunds of a booking's payments, oldest first. */
    @Query("select r from Refund r where r.payment.booking.id = :bookingId order by r.createdAt asc, r.id asc")
    List<Refund> findByBookingId(@Param("bookingId") Long bookingId);

    /** The owning booking of a refund, so the booking can be locked before the refund is read. */
    @Query("select r.payment.booking.id from Refund r where r.id = :refundId")
    Optional<Long> findBookingIdByRefundId(@Param("refundId") Long refundId);
}
