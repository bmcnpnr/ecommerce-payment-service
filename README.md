# ecommerce-payment-service

Payments against a **simulated** payment gateway. The client creates a payment
(`POST /api/v1/payments` with order, customer, amount and method), then
processes it. A random draw approves 80 % of payments. Each outcome is published
to Kafka (`payment.completed`, `payment.failed`), which is how order-, shipping-
and notification-service learn about it. Completed payments can be refunded in
full (`payment.refunded`).

| | |
|---|---|
| Port | 8085 |
| Gateway routes | `/api/v1/payments/**` (JWT required) |
| Data store | PostgreSQL `paymentdb` (`payments`, Flyway) |
| Publishes | `payment.completed`, `payment.failed`, `payment.refunded` (key: order ID) |
| Consumes / calls | nothing |
| Docs | [Deep dive](https://github.com/bmcnpnr/ecommerce-platform/blob/develop/docs/services/payment-service.md) · [API reference](https://github.com/bmcnpnr/ecommerce-platform/blob/develop/docs/api/payment-service.md) · [Event catalog](https://github.com/bmcnpnr/ecommerce-platform/blob/develop/docs/events.md) · Swagger UI at http://localhost:8080/swagger-ui.html (`payment-service`) |

With the repositories checked out side by side, the same docs are in
`../ecommerce-platform/docs/`.

> **Known gaps** (see the deep dive): the amount is whatever the client sends,
> and the order is never checked; any authenticated user can process or refund
> any payment; a decline still answers HTTP 200.

## Build and run

```bash
mvn -B verify                       # unit and contract tests (H2)
mvn -B clean package -DskipTests    # the jar the Docker image copies
cd ../ecommerce-platform && docker compose up -d --build payment-service
```

Kafka is configured in `config/KafkaProducerConfig.java`. Events are JSON from
spring-kafka's `JacksonJsonSerializer`, with `LocalDateTime` kept as integer
arrays (`WRITE_DATES_AS_TIMESTAMPS`) as the consumers and pacts expect.

## Contract tests (Pact)

`src/test/java/.../contract/PaymentEventsProviderPactTest` is the **provider** of
`payment.completed` / `payment.failed` / `payment.refunded` for order-service,
shipping-service and notification-service. It replays the pacts in
`src/test/resources/pacts/` against the real `PaymentService` and
`PaymentEventProducer`, serializing with the production `JacksonJsonSerializer`
instance from the `ProducerFactory`. The random 80/20 outcome is simply re-run
until the wanted branch fires.

Run it alone with `mvn test -Dtest='*PactTest'`. Redistribute pacts with
`ecommerce-platform/sync-pacts.sh`.
