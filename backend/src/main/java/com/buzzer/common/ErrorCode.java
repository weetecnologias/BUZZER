package com.buzzer.common;

import org.springframework.http.HttpStatus;

/** Stable, client-visible error codes. */
public enum ErrorCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST),
    NOT_FOUND(HttpStatus.NOT_FOUND),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED),
    FORBIDDEN(HttpStatus.FORBIDDEN),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED),
    OTP_INVALID(HttpStatus.BAD_REQUEST),
    OTP_EXPIRED(HttpStatus.BAD_REQUEST),
    OTP_TOO_MANY_ATTEMPTS(HttpStatus.TOO_MANY_REQUESTS),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS),
    ROLE_NOT_ALLOWED(HttpStatus.FORBIDDEN),
    SESSION_INVALID(HttpStatus.UNAUTHORIZED),
    INVALID_STATE_TRANSITION(HttpStatus.CONFLICT),
    REQUEST_ALREADY_ASSIGNED(HttpStatus.CONFLICT),
    QUOTATION_NOT_AVAILABLE(HttpStatus.CONFLICT),
    QUOTATION_EXPIRED(HttpStatus.CONFLICT),
    PARTNER_NOT_ELIGIBLE(HttpStatus.UNPROCESSABLE_ENTITY),
    PAY_ON_DELIVERY_NOT_ALLOWED(HttpStatus.UNPROCESSABLE_ENTITY),
    PAYMENT_SIGNATURE_INVALID(HttpStatus.BAD_REQUEST),
    PAYMENT_PROVIDER_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE),
    DUPLICATE(HttpStatus.CONFLICT),
    /** The product cart already holds items from another shop (one shop per cart). */
    CART_VENDOR_MISMATCH(HttpStatus.CONFLICT),
    /** A price changed since the customer added the item; the cart shows the new price. */
    PRICE_CHANGED(HttpStatus.CONFLICT),
    /** A product is no longer sold / out of stock / below the minimum order quantity. */
    ITEM_UNAVAILABLE(HttpStatus.CONFLICT),
    /** The chosen time / professional was just taken or is outside the bookable slots. */
    SLOT_UNAVAILABLE(HttpStatus.CONFLICT),
    CONFLICT(HttpStatus.CONFLICT),
    INTEGRATION_NOT_CONFIGURED(HttpStatus.SERVICE_UNAVAILABLE),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
