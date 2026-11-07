package com.buzzer.notification;

import java.util.Map;

/** Adapter for one delivery channel (SMS, push, email, WhatsApp). Implementations must be idempotent-safe. */
public interface ChannelSender {
    String channel();

    /** True when real provider credentials are configured. False means the adapter runs in test/log mode. */
    boolean liveMode();

    void send(String recipient, String eventType, Map<String, Object> payload) throws Exception;
}
