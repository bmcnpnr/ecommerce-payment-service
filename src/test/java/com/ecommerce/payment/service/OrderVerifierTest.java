package com.ecommerce.payment.service;

import com.ecommerce.payment.client.OrderServiceClient;
import com.ecommerce.payment.client.OrderSummary;
import com.ecommerce.payment.dto.InitiatePaymentRequest;
import com.ecommerce.payment.exception.DependencyUnavailableException;
import com.ecommerce.payment.exception.ForbiddenException;
import com.ecommerce.payment.exception.InvalidPaymentStateException;
import com.ecommerce.payment.exception.OrderNotFoundException;
import com.ecommerce.payment.exception.OrderNotPayableException;
import feign.FeignException;
import feign.Request;
import feign.RetryableException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

@Tag("security")
@ExtendWith(MockitoExtension.class)
class OrderVerifierTest {

    @Mock private OrderServiceClient client;
    @InjectMocks private OrderVerifier verifier;

    private static InitiatePaymentRequest request(String customer, String amount) {
        return InitiatePaymentRequest.builder().orderId(7L).customerId(customer).amount(new BigDecimal(amount))
                .method("CREDIT_CARD").build();
    }

    private static OrderSummary order(String customer, String total, String status) {
        return new OrderSummary(7L, customer, total == null ? null : new BigDecimal(total), status);
    }

    private static Request feignRequest() {
        return Request.create(Request.HttpMethod.GET, "http://order-service/internal/v1/orders/7", Map.of(), null, StandardCharsets.UTF_8, null);
    }

    @Test
    void confirmedOrder_ownedByThePayer_withTheExactTotal_passes_whateverTheScale() {
        given(client.getOrder(7L)).willReturn(order("alice", "99.9800", "CONFIRMED"));

        assertThatCode(() -> verifier.verifyPayable(request("alice", "99.98"))).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "PAID", "SHIPPED", "DELIVERED", "CANCELLED", "confirmed"})
    void orderThatIsNotConfirmed_is409(String status) {
        given(client.getOrder(7L)).willReturn(order("alice", "10.00", status));

        assertThatThrownBy(() -> verifier.verifyPayable(request("alice", "10.00"))).isInstanceOf(OrderNotPayableException.class);
    }

    @Test
    void nullStatus_isNotPayable() {
        given(client.getOrder(7L)).willReturn(order("alice", "10.00", null));

        assertThatThrownBy(() -> verifier.verifyPayable(request("alice", "10.00"))).isInstanceOf(OrderNotPayableException.class);
    }

    @Test
    void orderOfSomeoneElse_is403() {
        given(client.getOrder(7L)).willReturn(order("bob", "10.00", "CONFIRMED"));

        assertThatThrownBy(() -> verifier.verifyPayable(request("alice", "10.00"))).isInstanceOf(ForbiddenException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.01", "9.99", "10.01", "100.00"})
    void amountThatIsNotTheTotal_is400(String amount) {
        given(client.getOrder(7L)).willReturn(order("alice", "10.00", "CONFIRMED"));

        assertThatThrownBy(() -> verifier.verifyPayable(request("alice", amount))).isInstanceOf(InvalidPaymentStateException.class);
    }

    @Test
    void orderWithoutATotal_cannotBePaid() {
        given(client.getOrder(7L)).willReturn(order("alice", null, "CONFIRMED"));

        assertThatThrownBy(() -> verifier.verifyPayable(request("alice", "10.00"))).isInstanceOf(InvalidPaymentStateException.class);
    }

    @Test
    void unknownOrder_is404() {
        given(client.getOrder(7L)).willThrow(new FeignException.NotFound("not found", feignRequest(), null, null));

        assertThatThrownBy(() -> verifier.verifyPayable(request("alice", "10.00"))).isInstanceOf(OrderNotFoundException.class);
    }

    @Test
    void orderServiceDown_isRefusedNeverAcceptedBlind() {
        given(client.getOrder(7L)).willThrow(new RetryableException(-1, "Connection refused", Request.HttpMethod.GET, (Long) null, feignRequest()));

        assertThatThrownBy(() -> verifier.verifyPayable(request("alice", "10.00"))).isInstanceOf(DependencyUnavailableException.class);
    }

    @Test
    void serverError_noInstance_andEmptyAnswer_areAllUnavailable() {
        given(client.getOrder(7L))
                .willThrow(new FeignException.InternalServerError("boom", feignRequest(), null, null))
                .willThrow(new IllegalStateException("No instances available for order-service"))
                .willReturn(null);

        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> verifier.verifyPayable(request("alice", "10.00"))).isInstanceOf(DependencyUnavailableException.class);
        }
    }
}
