package com.ecommerce.payment.security;

import com.ecommerce.payment.exception.ForbiddenException;
import com.ecommerce.payment.exception.PaymentNotFoundException;
import com.ecommerce.payment.exception.UnauthorizedException;
import com.ecommerce.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Authorization rules from the platform security spec. The gateway verifies the JWT and sets
 * X-Username and X-User-Role; this service trusts them. The role is compared exactly (ADMIN).
 * The owner of a payment is its customerId, compared with X-Username.
 */
@Component
@RequiredArgsConstructor
public class PaymentAccess {

    public static final String ADMIN = "ADMIN";

    private final PaymentRepository paymentRepository;

    public static boolean isAdmin(String role) {
        return ADMIN.equals(role);
    }

    private static void requireIdentity(String username) {
        if (username == null || username.isBlank()) {
            throw new UnauthorizedException();
        }
    }

    public void requireAdmin(String username, String role) {
        requireIdentity(username);
        if (!isAdmin(role)) {
            throw new ForbiddenException();
        }
    }

    /** The named customer themselves, or an admin (initiate, list by customer). */
    public void requireCustomerOrAdmin(String username, String role, String customerId) {
        requireIdentity(username);
        if (!isAdmin(role) && !username.equals(customerId)) {
            throw new ForbiddenException();
        }
    }

    /** One payment by id: its owner or an admin. A missing payment is reported as such after the identity check. */
    public void requirePaymentOwnerOrAdmin(String username, String role, Long paymentId) {
        requireIdentity(username);
        if (isAdmin(role)) {
            return;
        }
        String owner = paymentRepository.findCustomerIdById(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException("Payment not found: " + paymentId));
        if (!owner.equals(username)) {
            throw new ForbiddenException();
        }
    }

    /** The payment of an order: its owner or an admin. */
    public void requireOrderPaymentOwnerOrAdmin(String username, String role, Long orderId) {
        requireIdentity(username);
        if (isAdmin(role)) {
            return;
        }
        String owner = paymentRepository.findCustomerIdByOrderId(orderId)
                .orElseThrow(() -> new PaymentNotFoundException("Payment not found for order: " + orderId));
        if (!owner.equals(username)) {
            throw new ForbiddenException();
        }
    }
}
