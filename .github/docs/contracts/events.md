# Envelope

Every message on every topic carries these fields.

{
  "eventId": "uuid",
  "eventType": "RESERVE_INVENTORY",
  "timestamp": "2025-01-01T10:00:00Z",
  "correlationId": "string",
  "sagaId": "uuid",
  "causationId": "uuid"
}

eventId
    Unique. The idempotency key for every consumer.
eventType
    Logical name, carried in the __TypeId__ header. Never a Java class name.
sagaId
    Which saga this belongs to. Stamped by the orchestrator on every command, echoed
    unchanged on the reply. Null when the message is not part of the order saga, e.g. the
    welcome mail from Auth Service. The orchestrator drops replies with no sagaId.
causationId
    The eventId of the command being answered. Null on a command.

Commands also carry:

orderId, orderNumber
    The order the command is about. orderId always equals sagaId in the order saga.
attempt
    Which send this is, starting at 1. For logging only. A re-send must produce the same
    reply as the original.

# COMMANDS

## RESERVE_INVENTORY  ->  inventory.commands

{
  "orderId": "uuid",
  "orderNumber": "CF-20260101-000001",
  "attempt": 1,
  "userId": "uuid",
  "userEmail": "string",
  "totalAmount": 100,
  "currency": "EUR",
  "items": [
    { "productId": "uuid", "sku": "string", "productName": "string",
      "quantity": 1, "unitPrice": 100 }
  ]
}

Answered by INVENTORY_RESERVED or INVENTORY_FAILED.

## PROCESS_PAYMENT  ->  payment.commands

{
  "orderId": "uuid",
  "orderNumber": "CF-20260101-000001",
  "attempt": 1,
  "reservationId": "uuid",
  "userId": "uuid",
  "userEmail": "string",
  "amount": 100,
  "currency": "EUR"
}

Answered by PAYMENT_COMPLETED or PAYMENT_FAILED.
The amount travels on the command so Payment never calls back into Order Service.

## CONFIRM_INVENTORY  ->  inventory.commands

{
  "orderId": "uuid",
  "orderNumber": "CF-20260101-000001",
  "attempt": 1
}

Answered by INVENTORY_CONFIRMED. Sent only after the customer has been charged.

## RELEASE_INVENTORY  ->  inventory.commands

{
  "orderId": "uuid",
  "orderNumber": "CF-20260101-000001",
  "attempt": 1,
  "reason": "PAYMENT_FAILED"
}

Answered by INVENTORY_RELEASED. Sent only for a saga whose reserve step succeeded.

## NOTIFICATION_SEND  ->  notification.send

{
  "userId": "uuid",
  "recipient": "string",
  "channel": "EMAIL",
  "templateCode": "ORDER_CONFIRMED",
  "subject": "string",
  "params": { "orderNumber": "CF-20260101-000001" },
  "referenceId": "uuid"
}

Answered by NOTIFICATION_SENT.
Also used outside the saga, with no sagaId, e.g. USER_WELCOME from Auth Service.

# REPLIES

## INVENTORY_RESERVED  ->  inventory.reserved

{
  "orderId": "uuid",
  "orderNumber": "CF-20260101-000001",
  "reservationId": "uuid",
  "userId": "uuid",
  "userEmail": "string",
  "totalAmount": 100,
  "currency": "EUR",
  "items": [ ... ],
  "status": "RESERVED"
}

reservationId is the handle the compensation needs. The orchestrator stores it so a release
never has to ask Inventory what it is holding.

## INVENTORY_FAILED  ->  inventory.failed

{
  "orderId": "uuid",
  "orderNumber": "CF-20260101-000001",
  "userId": "uuid",
  "userEmail": "string",
  "reason": "OUT_OF_STOCK",
  "unavailableSkus": ["string"]
}

reason: OUT_OF_STOCK | PRODUCT_NOT_FOUND | EMPTY_ORDER | RESERVATION_ALREADY_RELEASED

## INVENTORY_CONFIRMED  ->  inventory.confirmed

{
  "orderId": "uuid",
  "reservationId": "uuid"
}

## INVENTORY_RELEASED  ->  inventory.released

{
  "orderId": "uuid",
  "reservationId": "uuid",
  "reason": "PAYMENT_FAILED"
}

reservationId is null when there was nothing to release. That is still a success: a
compensation with nothing to undo has succeeded, and the orchestrator needs to hear so.

## PAYMENT_COMPLETED  ->  payment.completed

{
  "orderId": "uuid",
  "orderNumber": "CF-20260101-000001",
  "paymentId": "uuid",
  "userId": "uuid",
  "userEmail": "string",
  "amount": 100,
  "currency": "EUR",
  "transactionId": "string",
  "status": "COMPLETED"
}

## PAYMENT_FAILED  ->  payment.failed

{
  "orderId": "uuid",
  "orderNumber": "CF-20260101-000001",
  "paymentId": "uuid",
  "userId": "uuid",
  "userEmail": "string",
  "amount": 100,
  "currency": "EUR",
  "reason": "INSUFFICIENT_FUNDS"
}

reason: INSUFFICIENT_FUNDS | CARD_DECLINED | GATEWAY_ERROR

A decline is a business outcome, not an error. It is recorded and answered, and the
transaction commits.

## NOTIFICATION_SENT  ->  notification.sent

{
  "notificationId": "uuid",
  "userId": "uuid",
  "referenceId": "uuid",
  "channel": "EMAIL",
  "recipient": "string",
  "templateCode": "ORDER_CONFIRMED",
  "status": "SENT",
  "failureReason": null
}

status: SENT | FAILED
Sent whether or not delivery succeeded. An undeliverable email must not leave a paid order
looking unfinished.

# DOMAIN EVENTS

Published by Order Service for external consumers. No saga participant reads them.

## ORDER_CREATED  ->  order.created

{
  "orderId": "uuid",
  "orderNumber": "CF-20260101-000001",
  "userId": "uuid",
  "userEmail": "string",
  "totalAmount": 100,
  "currency": "EUR",
  "items": [ ... ]
}

## ORDER_COMPLETED  ->  order.completed

{
  "orderId": "uuid",
  "orderNumber": "CF-20260101-000001",
  "userId": "uuid",
  "userEmail": "string",
  "paymentId": "uuid",
  "totalAmount": 100,
  "currency": "EUR"
}

## ORDER_CANCELLED  ->  order.cancelled

{
  "orderId": "uuid",
  "orderNumber": "CF-20260101-000001",
  "userId": "uuid",
  "userEmail": "string",
  "totalAmount": 100,
  "currency": "EUR",
  "failedStep": "PAYMENT",
  "reason": "INSUFFICIENT_FUNDS"
}

failedStep: INVENTORY | PAYMENT | NOTIFICATION

This is the customer-facing name of the step, not the orchestrator's internal step name.
Three orchestrator steps map onto INVENTORY, so renaming or splitting a step never breaks a
client.
