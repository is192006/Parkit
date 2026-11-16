package com.parkit.dto.request;

import jakarta.validation.constraints.NotBlank;

/** JSON login (ARCHITECTURE.md §2). */
public record LoginRequest(@NotBlank String email, @NotBlank String password) {
}
