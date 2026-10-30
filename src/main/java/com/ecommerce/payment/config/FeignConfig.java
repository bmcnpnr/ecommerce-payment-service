package com.ecommerce.payment.config;

import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.http.converter.autoconfigure.ClientHttpMessageConvertersCustomizer;
import org.springframework.cloud.openfeign.CircuitBreakerNameResolver;
import org.springframework.cloud.openfeign.support.FeignHttpMessageConverters;
import org.springframework.cloud.openfeign.support.HttpMessageConverterCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.HttpMessageConverter;

@Configuration(proxyBeanMethods = false)
public class FeignConfig {

    /**
     * One circuit breaker per Feign client, named after it, instead of the default one per method: its
     * settings live under {@code resilience4j.circuitbreaker.instances.order-service}.
     */
    @Bean
    public CircuitBreakerNameResolver circuitBreakerNameResolver() {
        return (feignClientName, target, method) -> feignClientName;
    }

    /**
     * Replaces Spring Cloud OpenFeign's {@code FeignHttpMessageConverters} bean (declared
     * {@code @ConditionalOnMissingBean}; the per-client child contexts see this parent bean and skip their own),
     * as catalog-, order- and review-service do.
     *
     * Why: the library initialises its converter list lazily and without synchronisation: it publishes an
     * empty list and only then fills it. When the first payments after a start arrive concurrently, a second
     * thread sees the empty list and SpringDecoder fails with {@code DecodeException: 'messageConverters' must
     * not be empty}, which {@code OrderVerifier} turns into a 503 for that payer. The subclass makes the first
     * initialisation mutually exclusive; once built, reads are lock-free.
     */
    @Bean
    public FeignHttpMessageConverters feignHttpMessageConverters(
            ObjectProvider<ClientHttpMessageConvertersCustomizer> customizers,
            ObjectProvider<HttpMessageConverterCustomizer> cloudCustomizers) {
        return new ThreadSafeFeignHttpMessageConverters(customizers, cloudCustomizers);
    }

    public static class ThreadSafeFeignHttpMessageConverters extends FeignHttpMessageConverters {

        private volatile boolean initialised;

        public ThreadSafeFeignHttpMessageConverters(
                ObjectProvider<ClientHttpMessageConvertersCustomizer> customizers,
                ObjectProvider<HttpMessageConverterCustomizer> cloudCustomizers) {
            super(customizers, cloudCustomizers);
        }

        @Override
        public List<HttpMessageConverter<?>> getConverters() {
            if (!initialised) {
                synchronized (this) {
                    if (!initialised) {
                        // The superclass builds the list on first access; holding the lock for that first access
                        // is the whole fix. The volatile write afterwards publishes the filled list.
                        super.getConverters();
                        initialised = true;
                    }
                }
            }
            return super.getConverters();
        }
    }
}
