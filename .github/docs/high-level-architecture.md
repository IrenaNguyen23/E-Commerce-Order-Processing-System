                        +----------------------+
                        | Spring Cloud Gateway |
                        +----------+-----------+
                                   |
                ----------------------------------------
                |            |            |            |
                v            v            v            v

          Auth Service  Order Service Inventory Service Payment Service
                                |
                        +-------+--------+
                        |  ORCHESTRATOR  |
                        +-------+--------+
                                |
                                v
                            Kafka Cluster
                                |
        commands out                          replies in
        inventory.commands                    inventory.reserved / failed
        payment.commands                      inventory.released / confirmed
        notification.send                     payment.completed / failed
                                              notification.sent
                |                                     |
                v                                     v

       Notification Service                  Audit Service

The orchestrator is the only consumer of the reply topics. Each participant consumes exactly
one command topic and nothing else.
