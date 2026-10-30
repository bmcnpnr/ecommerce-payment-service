package com.ecommerce.payment.integration;

import com.ecommerce.payment.model.Payment;
import com.ecommerce.payment.model.PaymentMethod;
import com.ecommerce.payment.model.PaymentStatus;
import com.ecommerce.payment.repository.PaymentRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link PaymentRepository} against real Postgres 16 with the schema built by the production Flyway
 * migrations and checked by Hibernate's {@code ddl-auto=validate} (the context fails to start on any
 * entity/migration drift). Each test runs in a rolled-back transaction.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class PaymentRepositoryIT extends AbstractIntegrationTest {

    @Autowired PaymentRepository repository;
    @Autowired EntityManager em;

    private static long uniqueOrderId() {
        return ThreadLocalRandom.current().nextLong(1_000_000_000L, Long.MAX_VALUE);
    }

    private static Payment payment(long orderId, String customerId, String amount) {
        return Payment.builder().orderId(orderId).customerId(customerId).amount(new BigDecimal(amount))
                .method(PaymentMethod.CREDIT_CARD).build();
    }

    private Payment reload(Payment p) {
        em.flush();
        em.clear();
        return repository.findById(p.getId()).orElseThrow();
    }

    @Test
    void schemaIsBuiltByFlywayOnPostgres() {
        Object version = em.createNativeQuery(
                "select version from flyway_schema_history where success order by installed_rank desc limit 1")
                .getSingleResult();
        assertThat(version).hasToString("4"); // V3 adds payments.version, V4 the outbox table
        Object db = em.createNativeQuery("select version()").getSingleResult();
        assertThat(db.toString()).startsWith("PostgreSQL 16");
    }

    @Test
    void saveAndReload_roundTripsAllColumns() {
        long orderId = uniqueOrderId();
        Payment p = payment(orderId, "cust-rt", "10.50");
        p.setCurrency("EUR");
        p.setCardLastFour("4242");
        p.setMethod(PaymentMethod.DIGITAL_WALLET);
        p.setStatus(PaymentStatus.FAILED);
        p.setFailureReason("x".repeat(5_000)); // TEXT column, no length limit
        p = repository.save(p);
        assertThat(p.getId()).as("BIGSERIAL identity assigned").isNotNull();

        Payment loaded = reload(p);

        assertThat(loaded.getOrderId()).isEqualTo(orderId);
        assertThat(loaded.getCustomerId()).isEqualTo("cust-rt");
        assertThat(loaded.getCurrency()).isEqualTo("EUR");
        assertThat(loaded.getCardLastFour()).isEqualTo("4242");
        assertThat(loaded.getMethod()).isEqualTo(PaymentMethod.DIGITAL_WALLET);
        assertThat(loaded.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(loaded.getFailureReason()).hasSize(5_000);
        assertThat(loaded.getCreatedAt()).isNotNull();
        // enums are stored by name, as the migration's VARCHAR columns expect
        Object status = em.createNativeQuery("select status from payments where id = " + p.getId()).getSingleResult();
        assertThat(status).isEqualTo("FAILED");
    }

    @Test
    void amount_roundTripsAtNumeric19Scale4() {
        Payment max = repository.save(payment(uniqueOrderId(), "cust-num", "123456789012345.6789"));
        Payment cent = repository.save(payment(uniqueOrderId(), "cust-num", "0.01"));
        Payment tooPrecise = repository.save(payment(uniqueOrderId(), "cust-num", "1.23456"));

        assertThat(reload(max).getAmount()).isEqualTo(new BigDecimal("123456789012345.6789"));
        assertThat(reload(cent).getAmount()).isEqualTo(new BigDecimal("0.0100"));
        // NUMERIC(19,4) rounds half-up beyond four decimals
        assertThat(reload(tooPrecise).getAmount()).isEqualTo(new BigDecimal("1.2346"));
    }

    @Test
    void amount_beyondNumeric19Scale4_isRejected() {
        Payment overflow = payment(uniqueOrderId(), "cust-num", "1234567890123456"); // 16 integer digits > 15

        // IDENTITY ids make the INSERT happen at save(), the flush is belt and braces
        assertThatThrownBy(() -> { repository.save(overflow); em.flush(); }).isInstanceOfAny(DataAccessException.class,
                jakarta.persistence.PersistenceException.class);
    }

    @Test
    void preUpdate_setsUpdatedAt() {
        Payment p = repository.saveAndFlush(payment(uniqueOrderId(), "cust-upd", "5"));
        assertThat(p.getUpdatedAt()).isNull();

        p.setStatus(PaymentStatus.PROCESSING);
        repository.saveAndFlush(p);

        assertThat(reload(p).getUpdatedAt()).isNotNull();
    }

    @Test
    void findByOrderId_findsTheOrdersPayment() {
        long orderId = uniqueOrderId();
        Payment p = repository.save(payment(orderId, "cust-o", "1"));
        repository.save(payment(uniqueOrderId(), "cust-o", "2"));
        em.flush();

        assertThat(repository.findByOrderId(orderId)).map(Payment::getId).contains(p.getId());
        assertThat(repository.findByOrderId(uniqueOrderId())).isEmpty();
    }

    /** V2 makes order_id unique, so a duplicate row is rejected by the database. */
    @Test
    void orderId_isUnique_duplicateInsertIsRejected() {
        long orderId = uniqueOrderId();
        repository.save(payment(orderId, "cust-dup", "1"));
        em.flush();

        assertThatThrownBy(() -> {
            repository.save(payment(orderId, "cust-dup", "1"));
            em.flush();
        }).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void findByCustomerId_returnsOnlyThatCustomersPayments() {
        String customer = "cust-" + UUID.randomUUID();
        repository.save(payment(uniqueOrderId(), customer, "1"));
        repository.save(payment(uniqueOrderId(), customer, "2"));
        repository.save(payment(uniqueOrderId(), "someone-else", "3"));
        em.flush();

        List<Payment> found = repository.findByCustomerId(customer, org.springframework.data.domain.PageRequest.of(0, 100));
        assertThat(found).hasSize(2).allMatch(p -> p.getCustomerId().equals(customer));
        assertThat(repository.findByCustomerId("cust-" + UUID.randomUUID(), org.springframework.data.domain.PageRequest.of(0, 100))).isEmpty();
    }

    @Test
    void findByTransactionId_findsCompletedPayment() {
        String txn = "TXN-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        Payment p = payment(uniqueOrderId(), "cust-t", "1");
        p.setStatus(PaymentStatus.COMPLETED);
        p.setTransactionId(txn);
        repository.saveAndFlush(p);

        assertThat(repository.findByTransactionId(txn)).map(Payment::getId).contains(p.getId());
        assertThat(repository.findByTransactionId("TXN-NOPE")).isEmpty();
    }

    @Test
    void transactionId_isUnique() {
        String txn = "TXN-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        Payment first = payment(uniqueOrderId(), "cust-u", "1");
        first.setTransactionId(txn);
        repository.saveAndFlush(first);

        Payment second = payment(uniqueOrderId(), "cust-u", "1");
        second.setTransactionId(txn);
        assertThatThrownBy(() -> repository.saveAndFlush(second))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void transactionId_allowsManyNulls() {
        repository.save(payment(uniqueOrderId(), "cust-n", "1"));
        repository.save(payment(uniqueOrderId(), "cust-n", "1"));
        em.flush(); // no unique violation: pending payments have no transaction id yet
    }

    @Test
    void notNullColumns_areEnforcedByTheSchema() {
        em.flush();
        assertThatThrownBy(() -> em.createNativeQuery(
                        "insert into payments (order_id, customer_id, amount, method) values (1, null, 1, 'CREDIT_CARD')")
                .executeUpdate())
                .isInstanceOfAny(DataAccessException.class, jakarta.persistence.PersistenceException.class);
    }

    @Test
    void columnDefaults_fromMigration() {
        long orderId = uniqueOrderId();
        em.createNativeQuery("insert into payments (order_id, customer_id, amount, method) values (?1, 'cust-d', 1, 'BANK_TRANSFER')")
                .setParameter(1, orderId).executeUpdate();
        em.clear();

        Payment p = repository.findByOrderId(orderId).orElseThrow();
        assertThat(p.getCurrency()).isEqualTo("USD");
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(p.getCreatedAt()).isNotNull();
    }
}
