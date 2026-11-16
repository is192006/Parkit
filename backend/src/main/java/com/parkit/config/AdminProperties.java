package com.parkit.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * First-admin settings bound from {@code parkit.admin.*}; every value comes from
 * environment variables (see application.yml). The password is never logged.
 */
@ConfigurationProperties(prefix = "parkit.admin")
public record AdminProperties(String email, String password, String name, String phone) {

    public boolean isConfigured() {
        return notBlank(email) && notBlank(password) && notBlank(phone);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    @Override
    public String toString() {
        return "AdminProperties[email=" + email + ", password=****, name=" + name + ", phone=" + phone + "]";
    }
}
