package com.ecommerce.payment.repository;

import com.ecommerce.payment.model.Payment;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, Long> {
    Optional<Payment> findByOrderId(Long orderId);
    /** Always paged: a customer's payment history is unbounded. */
    List<Payment> findByCustomerId(String customerId, Pageable pageable);
    Optional<Payment> findByTransactionId(String transactionId);

    @Query("select p.customerId from Payment p where p.id = :id")
    Optional<String> findCustomerIdById(@Param("id") Long id);

    @Query("select p.customerId from Payment p where p.orderId = :orderId")
    Optional<String> findCustomerIdByOrderId(@Param("orderId") Long orderId);
}
