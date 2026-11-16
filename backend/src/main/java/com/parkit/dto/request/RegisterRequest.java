package com.parkit.dto.request;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Customer registration (FR-01). Registration always creates a CUSTOMER (FR-04). */
public record RegisterRequest(
        @NotBlank @Size(max = 100) String name,
        @NotBlank @Email @Size(max = 150) String email,
        String password,
        @NotBlank @Pattern(regexp = ValidationPatterns.PHONE, message = ValidationPatterns.PHONE_MESSAGE) String phone) {

    @AssertTrue(message = ValidationPatterns.PASSWORD_MESSAGE)
    public boolean isPasswordValid() {
        return ValidationPatterns.isValidPassword(password);
    }
}
