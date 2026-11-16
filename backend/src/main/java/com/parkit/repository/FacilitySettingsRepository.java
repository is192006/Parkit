package com.parkit.repository;

import com.parkit.domain.entity.FacilitySettings;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Data access for the single {@link com.parkit.domain.entity.FacilitySettings} row (id 1). */
public interface FacilitySettingsRepository extends JpaRepository<FacilitySettings, Short> {

    default Optional<FacilitySettings> findSettings() {
        return findById(FacilitySettings.SINGLETON_ID);
    }
}
