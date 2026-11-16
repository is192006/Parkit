package com.parkit.dto.request;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;

/** Password change; the current password is required (FR-05). */
public record ChangePasswordRequest(@NotBlank String currentPassword, String newPassword) {

    @AssertTrue(message = ValidationPatterns.PASSWORD_MESSAGE)
    public boolean isNewPasswordValid() {
        return ValidationPatterns.isValidPassword(newPassword);
    }
}
