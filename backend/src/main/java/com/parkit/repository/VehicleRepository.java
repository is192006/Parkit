package com.parkit.repository;

import com.parkit.domain.entity.Vehicle;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Data access for {@link com.parkit.domain.entity.Vehicle}. */
public interface VehicleRepository extends JpaRepository<Vehicle, Long> {

    List<Vehicle> findByOwnerIdOrderByCreatedAtAsc(Long ownerId);

    Optional<Vehicle> findByIdAndOwnerId(Long vehicleId, Long ownerId);

    boolean existsByRegistrationNumber(String normalizedRegistrationNumber);
}
