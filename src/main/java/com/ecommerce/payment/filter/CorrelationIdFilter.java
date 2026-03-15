package com.ecommerce.payment.filter;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.UUID;

@Component
public class CorrelationIdFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String cid = req.getHeader("X-Correlation-Id");
        if (cid == null || cid.isBlank()) cid = UUID.randomUUID().toString();
        MDC.put("correlationId", cid);
        res.addHeader("X-Correlation-Id", cid);
        try { chain.doFilter(req, res); } finally { MDC.remove("correlationId"); }
    }
}
