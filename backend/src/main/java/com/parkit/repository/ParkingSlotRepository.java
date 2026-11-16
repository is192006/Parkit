package com.parkit.repository;

import com.parkit.domain.entity.ParkingSlot;
import com.parkit.domain.enums.VehicleType;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Data access for {@link com.parkit.domain.entity.ParkingSlot}. Allocation queries are added with allocation (later phase). */
public interface ParkingSlotRepository extends JpaRepository<ParkingSlot, Long> {

    List<ParkingSlot> findAllByOrderBySortOrderAsc();

    Optional<ParkingSlot> findBySlotNumber(String slotNumber);

    boolean existsBySlotNumber(String slotNumber);

    boolean existsBySortOrder(Integer sortOrder);

    boolean existsBySlotNumberAndIdNot(String slotNumber, Long id);

    boolean existsBySortOrderAndIdNot(Integer sortOrder, Long id);

    /**
     * Loads one slot with a row lock ({@code SELECT ... FOR UPDATE}). Used for
     * block creation/cancellation and slot status/category changes, which must
     * be serialized with allocation on the same slot (ARCHITECTURE.md §6).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from ParkingSlot s where s.id = :id")
    Optional<ParkingSlot> findByIdForUpdate(@Param("id") Long id);

    /**
     * Locks every ACTIVE slot of a category in ascending slot_id order
     * ({@code SELECT ... ORDER BY slot_id FOR UPDATE}). This serializes all
     * allocations within the category (ARCHITECTURE.md §6 step 2); the fixed
     * order means concurrent lockers cannot deadlock. Waits at most the
     * configured 5 s lock_timeout.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select s from ParkingSlot s
            where s.vehicleType = :category
              and s.operationalStatus = com.parkit.domain.enums.SlotStatus.ACTIVE
            order by s.id
            """)
    List<ParkingSlot> lockActiveByCategory(@Param("category") VehicleType category);
}
