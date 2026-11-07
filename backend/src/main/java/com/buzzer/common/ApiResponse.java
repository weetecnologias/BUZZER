package com.buzzer.common;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Consistent envelope for every successful API response. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(T data, Object meta) {
    public static <T> ApiResponse<T> of(T data) {
        return new ApiResponse<>(data, null);
    }

    public static <T> ApiResponse<T> of(T data, Object meta) {
        return new ApiResponse<>(data, meta);
    }
}
