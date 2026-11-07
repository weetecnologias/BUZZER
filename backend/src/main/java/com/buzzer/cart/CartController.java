package com.buzzer.cart;

import com.buzzer.common.ApiResponse;
import com.buzzer.identity.CurrentUser;
import com.buzzer.request.RequestService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Customer cart, checkout, wishlist and "write to my agent". */
@RestController
public class CartController {
    public record AddInput(@NotNull UUID serviceItemId, @Min(1) @Max(50) Integer quantity) {}

    public record QuantityInput(@Min(0) @Max(50) int quantity) {}

    public record CheckoutBody(String mode, UUID addressId, OffsetDateTime preferredStart, @Size(max = 2000) String note) {}

    public record AgentRequestInput(@Size(max = 2000) String message, UUID categoryId, UUID addressId, OffsetDateTime preferredStart) {}

    private final CartService cart;
    private final JdbcClient jdbc;

    public CartController(CartService cart, JdbcClient jdbc) {
        this.cart = cart;
        this.jdbc = jdbc;
    }

    @GetMapping("/api/v1/customer/cart")
    public ApiResponse<Map<String, Object>> view() {
        return ApiResponse.of(cart.view(CurrentUser.id()));
    }

    @PostMapping("/api/v1/customer/cart/items")
    public ApiResponse<Map<String, Object>> add(@Valid @RequestBody AddInput in) {
        return ApiResponse.of(cart.add(CurrentUser.id(), in.serviceItemId(), in.quantity() == null ? 1 : in.quantity()));
    }

    @PutMapping("/api/v1/customer/cart/items/{serviceItemId}")
    public ApiResponse<Map<String, Object>> setQuantity(@PathVariable UUID serviceItemId, @Valid @RequestBody QuantityInput in) {
        return ApiResponse.of(cart.setQuantity(CurrentUser.id(), serviceItemId, in.quantity()));
    }

    @DeleteMapping("/api/v1/customer/cart/items/{serviceItemId}")
    public ApiResponse<Map<String, Object>> remove(@PathVariable UUID serviceItemId) {
        return ApiResponse.of(cart.setQuantity(CurrentUser.id(), serviceItemId, 0));
    }

    @DeleteMapping("/api/v1/customer/cart")
    public ApiResponse<Map<String, Object>> clear() {
        cart.clear(CurrentUser.id());
        return ApiResponse.of(cart.view(CurrentUser.id()));
    }

    @PostMapping("/api/v1/customer/cart/checkout")
    public ApiResponse<List<RequestService.Created>> checkout(@Valid @RequestBody CheckoutBody in,
                                                             @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return ApiResponse.of(cart.checkout(CurrentUser.id(), new CartService.CheckoutInput(in.mode(), in.addressId(), in.preferredStart(), in.note(), key)));
    }

    @PostMapping("/api/v1/customer/agent-request")
    public ApiResponse<RequestService.Created> writeToAgent(@Valid @RequestBody AgentRequestInput in,
                                                           @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return ApiResponse.of(cart.writeToAgent(CurrentUser.id(), in.message(), in.categoryId(), in.addressId(), in.preferredStart(), key));
    }

    @GetMapping("/api/v1/customer/wishlist")
    public ApiResponse<List<Map<String, Object>>> wishlist() {
        return ApiResponse.of(cart.wishlist(CurrentUser.id()));
    }

    @GetMapping("/api/v1/customer/wishlist/ids")
    public ApiResponse<List<UUID>> wishlistIds() {
        return ApiResponse.of(jdbc.sql("SELECT service_item_id FROM wishlist_items WHERE customer_user_id = :c")
                .param("c", CurrentUser.id()).query(UUID.class).list());
    }

    @PutMapping("/api/v1/customer/wishlist/{serviceItemId}")
    public ApiResponse<Map<String, Object>> addWish(@PathVariable UUID serviceItemId) {
        cart.wish(CurrentUser.id(), serviceItemId, true);
        return ApiResponse.of(Map.of("wishlisted", true));
    }

    @DeleteMapping("/api/v1/customer/wishlist/{serviceItemId}")
    public ApiResponse<Map<String, Object>> removeWish(@PathVariable UUID serviceItemId) {
        cart.wish(CurrentUser.id(), serviceItemId, false);
        return ApiResponse.of(Map.of("wishlisted", false));
    }
}
