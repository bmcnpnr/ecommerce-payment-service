# ecommerce-payment-service
I will develop an e commerce website backend (project is suggested by ChatGPT) to develop my azure, microservices and kubernetes experience
docker build -t payment-service:0.0.1-SNAPSHOT -f docker/Dockerfile .
docker tag payment-service:0.0.1-SNAPSHOT bmcnpnr/ecommerce-payment-service:latest
docker push bmcnpnr/ecommerce-payment-service:latest
