package com.parkit.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.Instant;

/**
 * Health-check result.
 *
 * @param status         overall status: UP or DOWN
 * @param database       database connectivity: UP or DOWN
 * @param schemaVersion  current Flyway schema version (null if unknown)
 * @param timestamp      server time of the check (UTC)
 */
public record HealthResponse(String status, String database, String schemaVersion, Instant timestamp) {

    public static final String UP = "UP";
    public static final String DOWN = "DOWN";

    @JsonIgnore
    public boolean isUp() {
        return UP.equals(status);
    }
}
