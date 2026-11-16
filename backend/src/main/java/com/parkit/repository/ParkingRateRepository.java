package com.parkit.repository;

import com.parkit.domain.entity.ParkingRate;
import com.parkit.domain.enums.VehicleType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Data access for {@link com.parkit.domain.entity.ParkingRate} (one row per category). */
public interface ParkingRateRepository extends JpaRepository<ParkingRate, Long> {

    Optional<ParkingRate> findByVehicleType(VehicleType vehicleType);
}
