package com.parkit.repository;

import com.parkit.domain.entity.ParkingSlot;
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
}
