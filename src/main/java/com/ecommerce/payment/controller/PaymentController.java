package com.ecommerce.payment.controller;

import com.ecommerce.payment.dto.*;
import com.ecommerce.payment.security.PaymentAccess;
import com.ecommerce.payment.service.OrderVerifier;
import com.ecommerce.payment.service.PaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
@Tag(name = "Payments", description = "Payment processing")
public class PaymentController {

    private static final String USER = "X-Username";
    private static final String ROLE = "X-User-Role";

    private final PaymentService paymentService;
    private final PaymentAccess access;
    private final OrderVerifier orderVerifier;

    @PostMapping
    @Operation(summary = "Initiate a payment (authenticated; the payer must be the caller unless admin)")
    public ResponseEntity<PaymentDTO> initiatePayment(@Valid @RequestBody InitiatePaymentRequest request,
                                                      @RequestHeader(value = USER, required = false) String username,
                                                      @RequestHeader(value = ROLE, required = false) String role) {
        access.requireCustomerOrAdmin(username, role, request.getCustomerId());
        // Cheap checks before the remote one: an unknown method is the caller's mistake, no need to ask order-service.
        if (!java.util.Arrays.stream(com.ecommerce.payment.model.PaymentMethod.values()).anyMatch(m -> m.name().equals(request.getMethod()))) {
            throw new com.ecommerce.payment.exception.InvalidPaymentMethodException("Unsupported payment method");
        }
        orderVerifier.verifyPayable(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(paymentService.initiatePayment(request));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a payment (owner or admin)")
    public ResponseEntity<PaymentDTO> getPaymentById(@PathVariable Long id,
                                                     @RequestHeader(value = USER, required = false) String username,
                                                     @RequestHeader(value = ROLE, required = false) String role) {
        access.requirePaymentOwnerOrAdmin(username, role, id);
        return ResponseEntity.ok(paymentService.getPaymentById(id));
    }

    @GetMapping("/order/{orderId}")
    @Operation(summary = "Get the payment of an order (owner or admin)")
    public ResponseEntity<PaymentDTO> getByOrderId(@PathVariable Long orderId,
                                                   @RequestHeader(value = USER, required = false) String username,
                                                   @RequestHeader(value = ROLE, required = false) String role) {
        access.requireOrderPaymentOwnerOrAdmin(username, role, orderId);
        return ResponseEntity.ok(paymentService.getPaymentByOrderId(orderId));
    }

    @GetMapping("/customer/{customerId}")
    @Operation(summary = "List the payments of a customer (that customer or admin)")
    public ResponseEntity<List<PaymentDTO>> getByCustomer(@PathVariable String customerId,
                                                          @RequestHeader(value = USER, required = false) String username,
                                                          @RequestHeader(value = ROLE, required = false) String role) {
        access.requireCustomerOrAdmin(username, role, customerId);
        return ResponseEntity.ok(paymentService.getPaymentsByCustomer(customerId));
    }

    @PostMapping("/{id}/process")
    @Operation(summary = "Process payment (simulate gateway; owner or admin)")
    public ResponseEntity<PaymentDTO> processPayment(@PathVariable Long id,
                                                     @RequestHeader(value = USER, required = false) String username,
                                                     @RequestHeader(value = ROLE, required = false) String role) {
        access.requirePaymentOwnerOrAdmin(username, role, id);
        return ResponseEntity.ok(paymentService.processPayment(id));
    }

    @PostMapping("/{id}/refund")
    @Operation(summary = "Refund a completed payment (admin only)")
    public ResponseEntity<PaymentDTO> refundPayment(@PathVariable Long id,
                                                    @RequestHeader(value = USER, required = false) String username,
                                                    @RequestHeader(value = ROLE, required = false) String role) {
        access.requireAdmin(username, role);
        return ResponseEntity.ok(paymentService.refundPayment(id));
    }
}
