package com.buzzer.payment;

import com.buzzer.common.ApiResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Public endpoint called by the payment gateway. Authenticity is established by the HMAC signature only. */
@RestController
public class PaymentWebhookController {
    private final PaymentService payments;

    public PaymentWebhookController(PaymentService payments) {
        this.payments = payments;
    }

    @PostMapping(value = "/api/v1/payments/webhooks/{provider}", consumes = "application/json")
    public ApiResponse<Map<String, Object>> webhook(@PathVariable String provider, @RequestBody String rawBody,
                                                    @RequestHeader(value = "X-Signature", required = false) String signature) {
        return ApiResponse.of(Map.of("result", payments.handleWebhook(provider, rawBody, signature)));
    }
}
