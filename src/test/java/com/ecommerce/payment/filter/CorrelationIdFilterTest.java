package com.ecommerce.payment.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();
    private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/payments/1");
    private final MockHttpServletResponse response = new MockHttpServletResponse();
    private final AtomicReference<String> mdcSeenByChain = new AtomicReference<>();
    private final FilterChain recordingChain = (req, res) -> mdcSeenByChain.set(MDC.get("correlationId"));

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void incomingHeader_isPropagatedToMdcAndResponse() throws Exception {
        request.addHeader("X-Correlation-Id", "abc-123");

        filter.doFilter(request, response, recordingChain);

        assertThat(mdcSeenByChain.get()).isEqualTo("abc-123");
        assertThat(response.getHeader("X-Correlation-Id")).isEqualTo("abc-123");
        assertThat(MDC.get("correlationId")).as("MDC cleaned up after the request").isNull();
    }

    @Test
    void missingHeader_generatesUuid() throws Exception {
        filter.doFilter(request, response, recordingChain);

        String generated = response.getHeader("X-Correlation-Id");
        assertThat(generated).isNotBlank();
        assertThat(UUID.fromString(generated).toString()).isEqualTo(generated);
        assertThat(mdcSeenByChain.get()).isEqualTo(generated);
        assertThat(MDC.get("correlationId")).isNull();
    }

    @Test
    void blankHeader_generatesUuid() throws Exception {
        request.addHeader("X-Correlation-Id", "   ");

        filter.doFilter(request, response, recordingChain);

        String generated = response.getHeader("X-Correlation-Id");
        assertThat(generated).isNotBlank().isNotEqualTo("   ");
        assertThat(UUID.fromString(generated)).isNotNull();
        assertThat(mdcSeenByChain.get()).isEqualTo(generated);
    }

    @Test
    void eachRequestWithoutHeader_getsDistinctId() throws Exception {
        MockHttpServletResponse other = new MockHttpServletResponse();
        filter.doFilter(request, response, recordingChain);
        filter.doFilter(new MockHttpServletRequest("GET", "/x"), other, recordingChain);

        assertThat(other.getHeader("X-Correlation-Id")).isNotEqualTo(response.getHeader("X-Correlation-Id"));
    }

    @Test
    void mdcIsClearedEvenWhenChainThrows() {
        request.addHeader("X-Correlation-Id", "boom-1");
        FilterChain failing = (req, res) -> { throw new ServletException("boom"); };

        assertThatThrownBy(() -> filter.doFilter(request, response, failing)).isInstanceOf(ServletException.class);

        assertThat(MDC.get("correlationId")).isNull();
        assertThat(response.getHeader("X-Correlation-Id")).isEqualTo("boom-1");
    }
}
