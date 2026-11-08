package com.buzzer.order;

import com.buzzer.common.ApiResponse;
import com.buzzer.identity.CurrentUser;
import com.buzzer.partner.PartnerService;
import com.buzzer.payment.PaymentService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Product cart and orders for customers, shops and admins. Every endpoint is scoped to the caller's own orders. */
@RestController
@RequestMapping("/api/v1")
public class OrderController {
    public record AddInput(UUID listingId, Integer quantity, Boolean replaceCart) {}

    public record QuantityInput(Integer quantity) {}

    public record ReasonInput(String reason) {}

    public record CodeInput(String code) {}

    private final ProductCartService carts;
    private final OrderService orders;
    private final PartnerService partners;

    public OrderController(ProductCartService carts, OrderService orders, PartnerService partners) {
        this.carts = carts;
        this.orders = orders;
        this.partners = partners;
    }

    // ----- Customer: product cart (one shop) ---------------------------------------------------------

    @GetMapping("/customer/product-cart")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ApiResponse<Map<String, Object>> cart(@RequestParam(required = false) UUID addressId) {
        return ApiResponse.of(carts.view(CurrentUser.id(), addressId));
    }

    @PostMapping("/customer/product-cart/items")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ApiResponse<Map<String, Object>> add(@RequestBody AddInput in) {
        return ApiResponse.of(carts.add(CurrentUser.id(), in.listingId(), in.quantity() == null ? 1 : in.quantity(), Boolean.TRUE.equals(in.replaceCart())));
    }

    @PutMapping("/customer/product-cart/items/{listingId}")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ApiResponse<Map<String, Object>> setQuantity(@PathVariable UUID listingId, @RequestBody QuantityInput in) {
        return ApiResponse.of(carts.setQuantity(CurrentUser.id(), listingId, in.quantity() == null ? 0 : in.quantity()));
    }

    @PostMapping("/customer/product-cart/accept-prices")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ApiResponse<Map<String, Object>> acceptPrices() {
        return ApiResponse.of(carts.acceptPrices(CurrentUser.id()));
    }

    @DeleteMapping("/customer/product-cart")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ApiResponse<Map<String, Object>> clear() {
        carts.clear(CurrentUser.id());
        return ApiResponse.of(Map.of("cleared", true));
    }

    // ----- Customer: orders ---------------------------------------------------------------------------

    /** Checkout of the product cart. Idempotent per Idempotency-Key. */
    @PostMapping("/customer/orders")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ApiResponse<Map<String, Object>> checkout(@RequestBody OrderService.CheckoutInput in, @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return ApiResponse.of(orders.checkout(CurrentUser.id(), in, key));
    }

    @GetMapping("/customer/orders")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ApiResponse<List<Map<String, Object>>> myOrders() {
        return ApiResponse.of(orders.customerOrders(CurrentUser.id()));
    }

    @GetMapping("/customer/orders/{id}")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ApiResponse<Map<String, Object>> myOrder(@PathVariable UUID id) {
        return ApiResponse.of(orders.customerOrder(id, CurrentUser.id()));
    }

    @PostMapping("/customer/orders/{id}/cancel")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ApiResponse<Map<String, Object>> cancel(@PathVariable UUID id, @RequestBody(required = false) ReasonInput in) {
        return ApiResponse.of(orders.customerCancel(id, CurrentUser.id(), in == null ? null : in.reason()));
    }

    @PostMapping("/customer/orders/{id}/pay")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ApiResponse<PaymentService.PaymentView> pay(@PathVariable UUID id, @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return ApiResponse.of(orders.pay(id, CurrentUser.id(), key));
    }

    // ----- Shop -------------------------------------------------------------------------------------------

    @GetMapping("/partner/orders")
    @PreAuthorize("hasRole('PARTNER')")
    public ApiResponse<List<Map<String, Object>>> shopOrders(@RequestParam(required = false) String group) {
        return ApiResponse.of(orders.partnerOrders(partners.partnerIdFor(CurrentUser.id()), group));
    }

    @GetMapping("/partner/orders/{id}")
    @PreAuthorize("hasRole('PARTNER')")
    public ApiResponse<Map<String, Object>> shopOrder(@PathVariable UUID id) {
        return ApiResponse.of(orders.partnerOrder(id, partners.partnerIdFor(CurrentUser.id())));
    }

    @PostMapping("/partner/orders/{id}/confirm")
    @PreAuthorize("hasRole('PARTNER')")
    public ApiResponse<Map<String, Object>> confirm(@PathVariable UUID id) {
        return ApiResponse.of(orders.confirm(id, partners.partnerIdFor(CurrentUser.id()), CurrentUser.id()));
    }

    @PostMapping("/partner/orders/{id}/reject")
    @PreAuthorize("hasRole('PARTNER')")
    public ApiResponse<Map<String, Object>> reject(@PathVariable UUID id, @RequestBody ReasonInput in) {
        return ApiResponse.of(orders.reject(id, partners.partnerIdFor(CurrentUser.id()), CurrentUser.id(), in == null ? null : in.reason()));
    }

    @PostMapping("/partner/orders/{id}/ready")
    @PreAuthorize("hasRole('PARTNER')")
    public ApiResponse<Map<String, Object>> ready(@PathVariable UUID id) {
        return ApiResponse.of(orders.ready(id, partners.partnerIdFor(CurrentUser.id()), CurrentUser.id()));
    }

    @PostMapping("/partner/orders/{id}/dispatch")
    @PreAuthorize("hasRole('PARTNER')")
    public ApiResponse<Map<String, Object>> dispatch(@PathVariable UUID id) {
        return ApiResponse.of(orders.dispatch(id, partners.partnerIdFor(CurrentUser.id()), CurrentUser.id()));
    }

    @PostMapping("/partner/orders/{id}/complete")
    @PreAuthorize("hasRole('PARTNER')")
    public ApiResponse<Map<String, Object>> complete(@PathVariable UUID id, @RequestBody CodeInput in) {
        return ApiResponse.of(orders.complete(id, partners.partnerIdFor(CurrentUser.id()), CurrentUser.id(), in == null ? null : in.code()));
    }

    // ----- Admin ----------------------------------------------------------------------------------------

    @GetMapping("/admin/orders")
    public ApiResponse<List<Map<String, Object>>> adminOrders(@RequestParam(required = false) String status, @RequestParam(required = false) String q,
                                                              @RequestParam(required = false) UUID partnerId) {
        return ApiResponse.of(orders.adminOrders(status, q, partnerId));
    }

    @GetMapping("/admin/orders/{id}")
    public ApiResponse<Map<String, Object>> adminOrder(@PathVariable UUID id) {
        return ApiResponse.of(orders.adminOrder(id));
    }

    @PostMapping("/admin/orders/{id}/cancel")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN','SUPPORT')")
    public ApiResponse<Map<String, Object>> adminCancel(@PathVariable UUID id, @RequestBody ReasonInput in) {
        return ApiResponse.of(orders.adminCancel(id, CurrentUser.id(), in == null ? null : in.reason()));
    }
}
