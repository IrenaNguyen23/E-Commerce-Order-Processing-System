# Pattern

Orchestration (ADR-004).

Order Service hosts the orchestrator. It sends a command to one named participant, waits for the
reply, and decides the next step from that reply. No participant subscribes to another
participant's topic.

Commands are imperative (RESERVE_INVENTORY). Replies are past tense (INVENTORY_RESERVED).

# Happy Path

ORCHESTRATOR
    |
    | 1. RESERVE_INVENTORY  -->  Inventory
    | <-- INVENTORY_RESERVED
    |     order = INVENTORY_RESERVED
    |
    | 2. PROCESS_PAYMENT    -->  Payment
    | <-- PAYMENT_COMPLETED
    |     order = PAID
    |
    | 3. CONFIRM_INVENTORY  -->  Inventory
    | <-- INVENTORY_CONFIRMED
    |     order = COMPLETED
    |
    | 4. NOTIFICATION_SEND  -->  Notification
    | <-- NOTIFICATION_SENT
    |     saga = COMPLETED

# Failure Path: stock

ORCHESTRATOR
    |
    | 1. RESERVE_INVENTORY  -->  Inventory
    | <-- INVENTORY_FAILED
    |     order = CANCELLED
    |
    |     nothing to compensate: the reservation never happened
    |
    | 4. NOTIFICATION_SEND  -->  Notification
    | <-- NOTIFICATION_SENT
    |     saga = COMPENSATED

# Failure Path: payment

ORCHESTRATOR
    |
    | 1. RESERVE_INVENTORY  -->  Inventory
    | <-- INVENTORY_RESERVED
    |
    | 2. PROCESS_PAYMENT    -->  Payment
    | <-- PAYMENT_FAILED
    |     order = CANCELLED
    |
    | 3. RELEASE_INVENTORY  -->  Inventory
    | <-- INVENTORY_RELEASED
    |
    | 4. NOTIFICATION_SEND  -->  Notification
    | <-- NOTIFICATION_SENT
    |     saga = COMPENSATED

# Kafka Topics

Commands (orchestrator to one participant)

inventory.commands
    RESERVE_INVENTORY
    RELEASE_INVENTORY
    CONFIRM_INVENTORY
payment.commands
    PROCESS_PAYMENT
notification.send
    NOTIFICATION_SEND

Replies (participant to orchestrator)

inventory.reserved
inventory.failed
inventory.released
inventory.confirmed
payment.completed
payment.failed
notification.sent

Domain events (published; no saga participant consumes them)

order.created
order.completed
order.cancelled

Dead letter

<topic>.DLT

# Rules

1. Every message is keyed by orderId. One order, one partition, in order.

2. The three inventory commands share one topic on purpose, so a release cannot overtake the
   reserve it compensates.

3. Every command produces exactly one reply, including a command for work already done. A
   re-sent RESERVE_INVENTORY for an already reserved order replies INVENTORY_RESERVED again with
   the original reservationId. Going quiet instead deadlocks the saga.

4. The orchestrator acts on a reply only when it names the step the saga is waiting for.
   Everything else is late or superseded and is dropped.

5. Every step has a deadline. An unanswered command is re-sent; after the retry budget the saga
   is parked as STALLED for an operator. It is not compensated automatically.

6. Every producer writes through the outbox. Every consumer claims the message in the
   idempotency ledger first.
