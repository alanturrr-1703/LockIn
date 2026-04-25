package com.lockin.tabstream.dto;

import java.time.Instant;

/**
 * Generic API response wrapper used by all REST endpoints.
 * Immutable by design — use static factory methods to construct instances.
 */
public class ApiResponse<T> {

    private final boolean ok;
    private final T data;
    private final String error;
    private final String timestamp;

    private ApiResponse(boolean ok, T data, String error) {
        this.ok = ok;
        this.data = data;
        this.error = error;
        this.timestamp = Instant.now().toString();
    }

    // -------------------------------------------------------------------------
    // Factory methods
    // -------------------------------------------------------------------------

    /**
     * Build a successful response carrying the given data payload.
     */
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null);
    }

    /**
     * Build an error response with a human-readable message and no data payload.
     */
    public static <T> ApiResponse<T> error(String message) {
        return new ApiResponse<>(false, null, message);
    }

    // -------------------------------------------------------------------------
    // Getters (read-only — Jackson serialises via these)
    // -------------------------------------------------------------------------

    public boolean isOk() {
        return ok;
    }

    public T getData() {
        return data;
    }

    public String getError() {
        return error;
    }

    public String getTimestamp() {
        return timestamp;
    }
}
