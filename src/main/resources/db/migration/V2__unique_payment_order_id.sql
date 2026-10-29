-- One payment row per order; repeat initiates reuse the row (see PaymentService.initiatePayment).
DROP INDEX IF EXISTS idx_payments_order_id;
ALTER TABLE payments ADD CONSTRAINT uq_payments_order_id UNIQUE (order_id);
