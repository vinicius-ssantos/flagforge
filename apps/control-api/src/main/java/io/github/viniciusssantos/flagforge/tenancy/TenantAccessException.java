package io.github.viniciusssantos.flagforge.tenancy;

public final class TenantAccessException extends RuntimeException {

    public enum Reason {
        AUTHENTICATION_REQUIRED,
        RESOURCE_NOT_FOUND
    }

    private final Reason reason;

    private TenantAccessException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public static TenantAccessException authenticationRequired() {
        return new TenantAccessException(
                Reason.AUTHENTICATION_REQUIRED,
                "Authenticated tenant context is required");
    }

    public static TenantAccessException resourceNotFound() {
        return new TenantAccessException(
                Reason.RESOURCE_NOT_FOUND,
                "Resource not found");
    }

    public Reason reason() {
        return reason;
    }
}
