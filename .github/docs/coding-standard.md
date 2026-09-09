# Coding Standard

## Layer Architecture

Controller
Service
Repository
Entity

No business logic inside Controller.

---

## Naming

Controller:

OrderController

Service:

OrderService

Repository:

OrderRepository

DTO:

CreateOrderRequest

Response:

OrderResponse

---

## Package Structure

com.commerceflow.orderservice

├── controller
├── service
├── repository
├── entity
├── dto
├── mapper
├── config
├── exception
├── kafka
├── security