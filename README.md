# ecommerce-payment-service
I will develop an e commerce website backend (project is suggested by ChatGPT) to develop my azure, microservices and kubernetes experience
docker build -t payment-service:0.0.1-SNAPSHOT -f docker/Dockerfile .
docker tag payment-service:0.0.1-SNAPSHOT bmcnpnr/ecommerce-payment-service:latest
docker push bmcnpnr/ecommerce-payment-service:latest

## Contract tests (Pact)

- `src/test/java/.../contract/PaymentEventsProviderPactTest` — **provider** of `payment.completed` / `payment.failed` / `payment.refunded` for order-service, shipping-service and notification-service. Replays the pacts in `src/test/resources/pacts/` against the real `PaymentService` + `PaymentEventProducer`, serializing with the configured Kafka `JsonSerializer` (the random 80/20 gateway outcome is simply re-run until the wanted branch fires).

Run them alone with `mvn test -Dtest='*PactTest'`; they are ordinary Surefire tests, so `mvn verify` and CI run them too. Regenerate and redistribute pacts across repositories with `ecommerce-platform/sync-pacts.sh` (see its README, "Contract tests").
