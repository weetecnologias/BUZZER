package com.buzzer.notification;

import com.buzzer.common.PhoneNumbers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * TEST MODE SMS adapter. Logs a redacted message instead of sending a real SMS.
 * Replace with a real provider adapter (e.g. MSG91 / Gupshup / Twilio) by setting
 * buzzer.notifications.sms.provider and supplying credentials. Active when provider = log (default).
 */
@Component
@ConditionalOnProperty(prefix = "buzzer.notifications.sms", name = "provider", havingValue = "log", matchIfMissing = true)
public class LoggingSmsSender implements ChannelSender {
    private static final Logger log = LoggerFactory.getLogger(LoggingSmsSender.class);

    @Override
    public String channel() {
        return "SMS";
    }

    @Override
    public boolean liveMode() {
        return false;
    }

    @Override
    public void send(String recipient, String eventType, Map<String, Object> payload) {
        String message = String.valueOf(payload.getOrDefault("message", ""));
        // Never log OTPs or other numeric secrets.
        log.info("[SMS TEST MODE] to={} event={} message={}", PhoneNumbers.mask(recipient), eventType, message.replaceAll("\\d{4,}", "****"));
    }
}
