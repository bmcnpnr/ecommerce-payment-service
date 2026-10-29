package com.ecommerce.payment.integration;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Shared Testcontainers for every *IT class: one Postgres and one Kafka per JVM (started once in
 * the static initializer, reused by all IT classes and Spring contexts, removed by Ryuk at JVM exit).
 * Images match production (docker-compose / k8s).
 *
 * <p>Postgres is wired through {@link ServiceConnection}. Kafka is not: this service uses plain
 * spring-kafka without Boot's Kafka auto-configuration, and {@code KafkaProducerConfig} reads
 * {@code spring.kafka.bootstrap-servers} itself, so the property is set directly.
 */
@ActiveProfiles("it")
public abstract class AbstractIntegrationTest {

    @ServiceConnection
    protected static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    protected static final ConfluentKafkaContainer KAFKA = new ConfluentKafkaContainer("confluentinc/cp-kafka:7.6.0");

    static {
        POSTGRES.start();
        KAFKA.start();
    }

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }
}
