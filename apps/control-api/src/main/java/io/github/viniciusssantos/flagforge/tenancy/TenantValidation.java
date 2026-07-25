package io.github.viniciusssantos.flagforge.tenancy;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

final class TenantValidation {

    private static final Pattern KEY_PATTERN =
            Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");

    private TenantValidation() {
    }

    static UUID requireId(UUID value, String fieldName) {
        return Objects.requireNonNull(value, fieldName + " is required");
    }

    static String requireKey(String value, String fieldName) {
        String normalized = requireText(value, fieldName, 63).toLowerCase();
        if (!KEY_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    fieldName + " must use lowercase letters, digits, and internal hyphens");
        }
        return normalized;
    }

    static String requireName(String value, String fieldName) {
        return requireText(value, fieldName, 120);
    }

    static String requireActorId(String value) {
        return requireText(value, "actorId", 128);
    }

    static Instant requireTimestamp(Instant value, String fieldName) {
        return Objects.requireNonNull(value, fieldName + " is required");
    }

    static Long requireVersion(Long value) {
        if (value != null && value < 0) {
            throw new IllegalArgumentException("version cannot be negative");
        }
        return value;
    }

    private static String requireText(String value, String fieldName, int maximumLength) {
        Objects.requireNonNull(value, fieldName + " is required");
        String normalized = value.strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " cannot be blank");
        }
        if (normalized.length() > maximumLength) {
            throw new IllegalArgumentException(
                    fieldName + " cannot exceed " + maximumLength + " characters");
        }
        return normalized;
    }
}
