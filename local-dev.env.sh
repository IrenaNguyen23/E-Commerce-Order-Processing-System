#!/usr/bin/env bash
# =====================================================================================
# CommerceFlow - local dev environment
#
# Dùng để chạy 1 service Spring Boot NGOÀI Docker (mvn spring-boot:run / IDE), trong khi
# hạ tầng (postgres, redis, kafka, pgadmin) vẫn chạy trong docker compose như bình thường.
#
# Build module chung 
#   mvn clean install -pl common/commerceflow-common -am -DskipTests
# CÁCH DÙNG (mỗi service 1 terminal riêng):
#   source local-dev.env.sh
#   set_auth          # hoặc set_order / set_inventory / set_payment / set_notification
#   mvn spring-boot:run -pl services/auth-service -am
#
# LƯU Ý QUAN TRỌNG:
#   - Kafka có 2 listener khác nhau. Container dùng "kafka:9092", nhưng máy host (app chạy
#     ngoài Docker) PHẢI dùng "localhost:29092" (listener EXTERNAL) - dùng nhầm 9092 sẽ bị
#     UnknownHostException: kafka.
#   - Chỉ chạy 1 set_xxx cho mỗi terminal/process. Nếu đổi sang chạy service khác trong
#     CÙNG 1 terminal, gọi lại set_xxx tương ứng trước khi mvn spring-boot:run.
# =====================================================================================

# ---------------------------------------------------------------- chung cho mọi service
export SPRING_PROFILES_ACTIVE=docker

export POSTGRES_HOST=localhost
export POSTGRES_PORT=5433
export POSTGRES_USER=commerceflow
export POSTGRES_PASSWORD=commerceflow

export REDIS_HOST=localhost
export REDIS_PORT=6379

export KAFKA_BOOTSTRAP_SERVERS=localhost:29092

export JWT_SECRET=commerceflow-local-development-secret-change-me-before-production
export JWT_ISSUER=commerceflow

export GATEWAY_PUBLIC_URL=http://localhost:8080
export WEB_PUBLIC_URL=http://localhost:3001
export TRACING_SAMPLE_RATE=1.0

# ---------------------------------------------------------------- theo từng service

set_auth() {
  export POSTGRES_DB=commerceflow_auth
  export REQUIRE_VERIFIED_EMAIL=false
  export COMMERCEFLOW_BOOTSTRAP_ADMIN_ENABLED=true
  export COMMERCEFLOW_BOOTSTRAP_ADMIN_EMAIL=admin@commerceflow.io
  export COMMERCEFLOW_BOOTSTRAP_ADMIN_PASSWORD=ChangeMe-Admin-2026
  echo "-> env set cho auth-service (port 8081), POSTGRES_DB=$POSTGRES_DB"
}

set_inventory() {
  export POSTGRES_DB=commerceflow_inventory
  export COMMERCEFLOW_BOOTSTRAP_DEMO_CATALOGUE_ENABLED=true
  echo "-> env set cho inventory-service (port 8083), POSTGRES_DB=$POSTGRES_DB"
}

set_order() {
  export POSTGRES_DB=commerceflow_order
  export INVENTORY_SERVICE_URL=http://localhost:8083
  echo "-> env set cho order-service (port 8082), POSTGRES_DB=$POSTGRES_DB"
}

set_payment() {
  export POSTGRES_DB=commerceflow_payment
  export PAYMENT_GATEWAY=SIMULATED
  export PAYMENT_DECLINE_THRESHOLD=10000.00
  echo "-> env set cho payment-service (port 8084), POSTGRES_DB=$POSTGRES_DB"
}

set_notification() {
  export POSTGRES_DB=commerceflow_notification
  export NOTIFICATION_EMAIL_ENABLED=false
  export NOTIFICATION_EMAIL_FROM=no-reply@commerceflow.io
  echo "-> env set cho notification-service (port 8085), POSTGRES_DB=$POSTGRES_DB"
}

set_gateway() {
  export AUTH_SERVICE_URL=http://localhost:8081
  export ORDER_SERVICE_URL=http://localhost:8082
  export INVENTORY_SERVICE_URL=http://localhost:8083
  export PAYMENT_SERVICE_URL=http://localhost:8084
  export NOTIFICATION_SERVICE_URL=http://localhost:8085
  echo "-> env set cho api-gateway (port 8080)"
}

echo "Đã load biến chung. Gọi set_auth / set_inventory / set_order / set_payment / set_notification / set_gateway rồi chạy mvn spring-boot:run."