package com.ecommerce.payment.security;

import com.ecommerce.payment.exception.ForbiddenException;
import com.ecommerce.payment.exception.PaymentNotFoundException;
import com.ecommerce.payment.exception.UnauthorizedException;
import com.ecommerce.payment.repository.PaymentRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

@Tag("security")
@ExtendWith(MockitoExtension.class)
class PaymentAccessTest {

    @Mock private PaymentRepository paymentRepository;
    @InjectMocks private PaymentAccess access;

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  "})
    void noIdentity_is401_onEveryRule(String username) {
        assertThatThrownBy(() -> access.requireAdmin(username, "ADMIN")).isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(() -> access.requireCustomerOrAdmin(username, "ADMIN", "alice")).isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(() -> access.requirePaymentOwnerOrAdmin(username, "ADMIN", 1L)).isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(() -> access.requireOrderPaymentOwnerOrAdmin(username, "ADMIN", 1L)).isInstanceOf(UnauthorizedException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"admin", "Admin", " ADMIN", "ADMIN ", "ROLE_ADMIN", "CUSTOMER", ""})
    void onlyTheExactRoleAdminIsAdmin(String role) {
        assertThatThrownBy(() -> access.requireAdmin("root", role)).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> access.requireCustomerOrAdmin("root", role, "alice")).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void adminPassesEverything_withoutALookup() {
        assertThatCode(() -> access.requireAdmin("root", "ADMIN")).doesNotThrowAnyException();
        assertThatCode(() -> access.requireCustomerOrAdmin("root", "ADMIN", "alice")).doesNotThrowAnyException();
        assertThatCode(() -> access.requirePaymentOwnerOrAdmin("root", "ADMIN", 7L)).doesNotThrowAnyException();
        assertThatCode(() -> access.requireOrderPaymentOwnerOrAdmin("root", "ADMIN", 7L)).doesNotThrowAnyException();
    }

    @Test
    void ownerPasses_otherCustomerIs403() {
        given(paymentRepository.findCustomerIdById(7L)).willReturn(Optional.of("alice"));
        given(paymentRepository.findCustomerIdByOrderId(70L)).willReturn(Optional.of("alice"));

        assertThatCode(() -> access.requirePaymentOwnerOrAdmin("alice", "CUSTOMER", 7L)).doesNotThrowAnyException();
        assertThatCode(() -> access.requireOrderPaymentOwnerOrAdmin("alice", "CUSTOMER", 70L)).doesNotThrowAnyException();
        assertThatThrownBy(() -> access.requirePaymentOwnerOrAdmin("bob", "CUSTOMER", 7L)).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> access.requireOrderPaymentOwnerOrAdmin("bob", "CUSTOMER", 70L)).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void unknownPayment_is404_forACustomer() {
        given(paymentRepository.findCustomerIdById(8L)).willReturn(Optional.empty());
        given(paymentRepository.findCustomerIdByOrderId(80L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> access.requirePaymentOwnerOrAdmin("alice", "CUSTOMER", 8L)).isInstanceOf(PaymentNotFoundException.class);
        assertThatThrownBy(() -> access.requireOrderPaymentOwnerOrAdmin("alice", "CUSTOMER", 80L)).isInstanceOf(PaymentNotFoundException.class);
    }

    @Test
    void customerRule_isTheCustomerThemselvesOnly() {
        assertThatCode(() -> access.requireCustomerOrAdmin("alice", "CUSTOMER", "alice")).doesNotThrowAnyException();
        assertThatThrownBy(() -> access.requireCustomerOrAdmin("alice", "CUSTOMER", "bob")).isInstanceOf(ForbiddenException.class);
    }
}
