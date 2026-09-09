# CommerceFlow

Microservices-based E-Commerce Order Processing System.

## Stack

- Java 21
- Spring Boot
- Spring Security
- PostgreSQL
- Redis
- Kafka
- Docker
- Kubernetes

## Services

- Auth Service
- Order Service
- Inventory Service
- Payment Service
- Notification Service

## Architecture

Orchestration Saga Pattern using Kafka.

Order Service hosts the orchestrator. It sends each participant an explicit command and
decides the next step from the reply. No participant subscribes to another participant.

## Run Local

docker compose up -d

## Services

Gateway:
localhost:8080

Auth:
localhost:8081

Order:
localhost:8082

Inventory:
localhost:8083

Payment:
localhost:8084

Notification:
localhost:8085

## API Docs

/swagger-ui.html

## Monitoring

Prometheus
Grafana

## Deployment

GitHub Actions + Kubernetes