package com.parkit.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import org.hibernate.annotations.JdbcType;
import org.hibernate.type.descriptor.jdbc.LocalTimeJdbcType;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * The single facility settings row (table {@code facility_settings}, id = 1, BR-01).
 * Seeded by V3; only updated afterwards, never created by the application.
 */
@Entity
@Table(name = "facility_settings")
@EntityListeners(AuditingEntityListener.class)
public class FacilitySettings {

    /** The only permitted id (CHECK setting_id = 1). */
    public static final short SINGLETON_ID = 1;

    @Id
    @Column(name = "setting_id")
    private Short id;

    @NotBlank
    @Size(max = 150)
    @Column(name = "facility_name", nullable = false, length = 150)
    private String facilityName;

    /**
     * Facility-local wall-clock times. Bound as plain LocalTime (no time-zone
     * conversion), unlike instants, which use hibernate.jdbc.time_zone=UTC.
     * If closing is earlier than opening, the open period crosses midnight (BR-06.5).
     */
    @NotNull
    @JdbcType(LocalTimeJdbcType.class)
    @Column(name = "opening_time", nullable = false)
    private LocalTime openingTime;

    @NotNull
    @JdbcType(LocalTimeJdbcType.class)
    @Column(name = "closing_time", nullable = false)
    private LocalTime closingTime;

    @Column(name = "open_24_hours", nullable = false)
    private boolean open24Hours;

    /** Fixed to Asia/Kolkata in Version 1 (CHECK constraint). */
    @NotBlank
    @Size(max = 50)
    @Column(name = "timezone", nullable = false, length = 50, updatable = false)
    private String timezone;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by_user_id")
    private User updatedBy;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected FacilitySettings() {
        // for JPA; the row is created by migration V3
    }

    /** Mirrors ck_hours: equal opening and closing times only when open 24 hours. */
    @AssertTrue(message = "opening and closing time may be equal only when open 24 hours")
    public boolean isHoursConsistent() {
        return open24Hours || openingTime == null || !openingTime.equals(closingTime);
    }

    public void updateSettings(String facilityName, LocalTime openingTime, LocalTime closingTime,
                               boolean open24Hours, User updatedBy) {
        this.facilityName = facilityName;
        this.openingTime = openingTime;
        this.closingTime = closingTime;
        this.open24Hours = open24Hours;
        this.updatedBy = updatedBy;
    }

    public ZoneId getZoneId() {
        return ZoneId.of(timezone);
    }

    public Short getId() {
        return id;
    }

    public String getFacilityName() {
        return facilityName;
    }

    public LocalTime getOpeningTime() {
        return openingTime;
    }

    public LocalTime getClosingTime() {
        return closingTime;
    }

    public boolean isOpen24Hours() {
        return open24Hours;
    }

    public String getTimezone() {
        return timezone;
    }

    public User getUpdatedBy() {
        return updatedBy;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
