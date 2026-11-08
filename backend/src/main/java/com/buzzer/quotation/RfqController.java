package com.buzzer.quotation;

import com.buzzer.common.ApiResponse;
import com.buzzer.common.BusinessException;
import com.buzzer.identity.CurrentUser;
import com.buzzer.request.RequestRepository;
import com.buzzer.request.RequestService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/** Customer quotation requests for products: create (items + specs), then compare the shops' quotes side by side. */
@RestController
@RequestMapping("/api/v1/customer")
@PreAuthorize("hasRole('CUSTOMER')")
public class RfqController {
    private final RfqService rfqs;
    private final RequestRepository requests;

    public RfqController(RfqService rfqs, RequestRepository requests) {
        this.rfqs = rfqs;
        this.requests = requests;
    }

    @PostMapping("/rfqs")
    public ApiResponse<RequestService.Created> create(@RequestBody RfqService.RfqInput in, @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return ApiResponse.of(rfqs.create(CurrentUser.id(), in, key));
    }

    @GetMapping("/requests/{id}/compare")
    public ApiResponse<Map<String, Object>> compare(@PathVariable UUID id) {
        if (!requests.get(id).customerUserId().equals(CurrentUser.id())) throw BusinessException.notFound("Request");
        return ApiResponse.of(rfqs.compare(id));
    }
}
