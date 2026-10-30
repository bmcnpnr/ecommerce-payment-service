package com.ecommerce.payment.config;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.JacksonMapperUtils;
import org.springframework.kafka.support.ProducerListener;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashMap;
import java.util.Map;

/**
 * Producer side only: this service consumes nothing. (The consumer and listener container factories that
 * used to live here served no {@code @KafkaListener} and were removed.)
 */
@Configuration
public class KafkaProducerConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    /**
     * Strings in, strings out: events are serialised to JSON when they are written to the outbox table
     * ({@link #eventJsonMapper()}), and {@link com.ecommerce.payment.outbox.OutboxRelay} only moves those bytes.
     */
    @Bean
    public ProducerFactory<String, String> producerFactory() {
        Map<String, Object> configs = new HashMap<>();
        configs.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        // A dead broker then costs one "could not be established" WARN per client every ~10 s, not one per second.
        configs.put(ProducerConfig.RECONNECT_BACKOFF_MS_CONFIG, 1000);
        configs.put(ProducerConfig.RECONNECT_BACKOFF_MAX_MS_CONFIG, 10000);
        // A dead broker must not hold a thread for the 60 s default: give up after 5 s (metadata) / 10 s (one
        // request) / 30 s (whole delivery) and let the caller back off.
        configs.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 5000);
        configs.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 10000);
        configs.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 30000);
        configs.put(ProducerConfig.ACKS_CONFIG, "all");
        configs.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        return new DefaultKafkaProducerFactory<>(configs, new StringSerializer(), new StringSerializer());
    }

    /**
     * Event JSON as it has always looked on the wire: spring-kafka's mapper defaults,
     * but with java.time as numeric arrays ([y, M, d, H, m, s, nanos]) instead of the
     * ISO strings Jackson 3 writes by default. Every consumer and the Pact contracts
     * pin the array form.
     */
    public static JsonMapper eventJsonMapper() {
        return JacksonMapperUtils.enhancedJsonMapper().rebuild()
                .enable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
    }

    @Bean
    public KafkaTemplate<String, String> kafkaTemplate() {
        KafkaTemplate<String, String> template = new KafkaTemplate<>(producerFactory());
        // The outbox relay reports a failed send itself, throttled and without a stack trace; the default
        // LoggingProducerListener would add an ERROR with one per failed send while the broker is down.
        template.setProducerListener(new ProducerListener<>() { });
        return template;
    }
}
