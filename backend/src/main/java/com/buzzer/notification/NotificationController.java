package com.buzzer.notification;

import com.buzzer.common.ApiResponse;
import com.buzzer.identity.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Notifications for any signed-in user (customer, partner, agent or admin). */
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {
    private final NotificationService notifications;

    public NotificationController(NotificationService notifications) {
        this.notifications = notifications;
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list(@RequestParam(defaultValue = "50") int limit) {
        UUID me = CurrentUser.id();
        return ApiResponse.of(notifications.list(me, limit), Map.of("unread", notifications.unreadCount(me)));
    }

    @PostMapping("/{id}/read")
    public ApiResponse<Map<String, Object>> read(@PathVariable UUID id) {
        notifications.markRead(CurrentUser.id(), id);
        return ApiResponse.of(Map.of("read", true));
    }
}
