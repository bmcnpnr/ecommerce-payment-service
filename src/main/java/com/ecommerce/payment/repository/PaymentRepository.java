package com.ecommerce.payment.repository;

import com.ecommerce.payment.model.Payment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, Long> {
    Optional<Payment> findByOrderId(Long orderId);
    List<Payment> findByCustomerId(String customerId);
    Optional<Payment> findByTransactionId(String transactionId);
}
