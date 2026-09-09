Customer Login
    |
    v
Create Order                      <-- synchronous. 201 CREATED returns here.
    |
    v
ORCHESTRATOR starts               <-- same transaction as the order itself
    |
    v
1. Reserve Inventory
    |
    +--------------------------+
    |                          |
  RESERVED                 OUT OF STOCK
    |                          |
    v                          |
2. Process Payment             |
    |                          |
    +-------------+            |
    |             |            |
 COMPLETED     DECLINED        |
    |             |            |
    v             v            |
3. Confirm     3. Release      |   (nothing was reserved,
   Inventory      Inventory    |    so nothing to release)
    |             |            |
    v             v            v
Complete Order   Cancel Order (already cancelled when the failure was known)
    |             |            |
    +-------------+------------+
    |
    v
4. Notification
    |
    v
Saga COMPLETED / COMPENSATED
