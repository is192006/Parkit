package com.parkit.dto.request;

import java.nio.charset.StandardCharsets;

/** Shared input rules for request DTOs. */
public final class ValidationPatterns {

    /** Digits with an optional leading +, spaces or hyphens; 7-20 characters (column is VARCHAR(20)). */
    public static final String PHONE = "^\\+?[0-9][0-9 \\-]{5,18}[0-9]$";
    public static final String PHONE_MESSAGE = "must be a valid phone number";

    public static final int PASSWORD_MIN_LENGTH = 8;
    /** BCrypt only uses the first 72 bytes; longer passwords are rejected rather than silently truncated. */
    public static final int PASSWORD_MAX_BYTES = 72;
    public static final String PASSWORD_MESSAGE = "password must be at least 8 characters and at most 72 bytes";

    private ValidationPatterns() {
    }

    public static boolean isValidPassword(String password) {
        return password != null
                && password.length() >= PASSWORD_MIN_LENGTH
                && !password.isBlank()
                && password.getBytes(StandardCharsets.UTF_8).length <= PASSWORD_MAX_BYTES;
    }
}
