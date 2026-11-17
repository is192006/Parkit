package com.parkit.repository;

import com.parkit.domain.entity.Payment;
import com.parkit.domain.enums.PaymentStatus;
import com.parkit.domain.enums.PaymentType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Data access for {@link com.parkit.domain.entity.Payment}. */
public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByIdempotencyKey(String idempotencyKey);

    List<Payment> findByBookingIdOrderByCreatedAtAsc(Long bookingId);

    Optional<Payment> findByBookingIdAndPaymentTypeAndStatus(Long bookingId, PaymentType type, PaymentStatus status);
}
