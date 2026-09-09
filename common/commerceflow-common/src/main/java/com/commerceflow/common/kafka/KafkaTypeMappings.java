package com.commerceflow.common.kafka;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import com.commerceflow.common.constant.EventTypes;
import com.commerceflow.common.constant.KafkaTopics;
import com.commerceflow.common.event.ConfirmInventoryCommand;
import com.commerceflow.common.event.InventoryRestockedEvent;
import com.commerceflow.common.event.PaymentRefundedEvent;
import com.commerceflow.common.event.RefundPaymentCommand;
import com.commerceflow.common.event.RefundReturnCommand;
import com.commerceflow.common.event.RestockInventoryCommand;
import com.commerceflow.common.event.RestockReturnedGoodsCommand;
import com.commerceflow.common.event.DomainEvent;
import com.commerceflow.common.event.InventoryConfirmedEvent;
import com.commerceflow.common.event.InventoryFailedEvent;
import com.commerceflow.common.event.InventoryReleasedEvent;
import com.commerceflow.common.event.InventoryReservedEvent;
import com.commerceflow.common.event.NotificationSendEvent;
import com.commerceflow.common.event.NotificationSentEvent;
import com.commerceflow.common.event.OrderCancelledEvent;
import com.commerceflow.common.event.OrderCompletedEvent;
import com.commerceflow.common.event.ReturnRefundedEvent;
import com.commerceflow.common.event.UserErasedEvent;
import com.commerceflow.common.event.OrderCreatedEvent;
import com.commerceflow.common.event.PaymentCompletedEvent;
import com.commerceflow.common.event.PaymentFailedEvent;
import com.commerceflow.common.event.ProcessPaymentCommand;
import com.commerceflow.common.event.ReleaseInventoryCommand;
import com.commerceflow.common.event.ReserveInventoryCommand;

/**
 * Single source of truth binding a logical message name to its Java type and its Kafka topic.
 *
 * <p>The alias — not the fully qualified class name — is what travels in the {@code __TypeId__}
 * header, which keeps the wire contract stable across refactorings and consumable by
 * non-JVM clients.
 *
 * <p>Four commands share {@code inventory.commands}. That is deliberate: they address the same
 * participant and the same aggregate, so keeping them on one partitioned topic guarantees a
 * release can never overtake the reserve it is compensating.
 */
public final class KafkaTypeMappings {

    private static final Map<String, Class<? extends DomainEvent>> CLASS_BY_ALIAS;
    private static final Map<Class<? extends DomainEvent>, String> ALIAS_BY_CLASS;
    private static final Map<String, String> TOPIC_BY_ALIAS;

    static {
        Map<String, Class<? extends DomainEvent>> classes = new LinkedHashMap<>();
        classes.put(EventTypes.RESERVE_INVENTORY, ReserveInventoryCommand.class);
        classes.put(EventTypes.RELEASE_INVENTORY, ReleaseInventoryCommand.class);
        classes.put(EventTypes.CONFIRM_INVENTORY, ConfirmInventoryCommand.class);
        classes.put(EventTypes.PROCESS_PAYMENT, ProcessPaymentCommand.class);
        classes.put(EventTypes.REFUND_PAYMENT, RefundPaymentCommand.class);
        classes.put(EventTypes.RESTOCK_INVENTORY, RestockInventoryCommand.class);
        classes.put(EventTypes.NOTIFICATION_SEND, NotificationSendEvent.class);

        classes.put(EventTypes.INVENTORY_RESERVED, InventoryReservedEvent.class);
        classes.put(EventTypes.INVENTORY_FAILED, InventoryFailedEvent.class);
        classes.put(EventTypes.INVENTORY_RELEASED, InventoryReleasedEvent.class);
        classes.put(EventTypes.INVENTORY_CONFIRMED, InventoryConfirmedEvent.class);
        classes.put(EventTypes.INVENTORY_RESTOCKED, InventoryRestockedEvent.class);
        classes.put(EventTypes.PAYMENT_COMPLETED, PaymentCompletedEvent.class);
        classes.put(EventTypes.PAYMENT_FAILED, PaymentFailedEvent.class);
        classes.put(EventTypes.PAYMENT_REFUNDED, PaymentRefundedEvent.class);
        classes.put(EventTypes.NOTIFICATION_SENT, NotificationSentEvent.class);

        classes.put(EventTypes.ORDER_CREATED, OrderCreatedEvent.class);
        classes.put(EventTypes.ORDER_COMPLETED, OrderCompletedEvent.class);
        classes.put(EventTypes.USER_ERASED, UserErasedEvent.class);
        classes.put(EventTypes.REFUND_RETURN, RefundReturnCommand.class);
        classes.put(EventTypes.RETURN_REFUNDED, ReturnRefundedEvent.class);
        classes.put(EventTypes.RESTOCK_RETURNED_GOODS, RestockReturnedGoodsCommand.class);
        classes.put(EventTypes.ORDER_CANCELLED, OrderCancelledEvent.class);
        CLASS_BY_ALIAS = Map.copyOf(classes);

        Map<Class<? extends DomainEvent>, String> aliases = new LinkedHashMap<>();
        classes.forEach((alias, type) -> aliases.put(type, alias));
        ALIAS_BY_CLASS = Map.copyOf(aliases);

        Map<String, String> topics = new LinkedHashMap<>();
        topics.put(EventTypes.RESERVE_INVENTORY, KafkaTopics.INVENTORY_COMMANDS);
        topics.put(EventTypes.RELEASE_INVENTORY, KafkaTopics.INVENTORY_COMMANDS);
        topics.put(EventTypes.CONFIRM_INVENTORY, KafkaTopics.INVENTORY_COMMANDS);
        topics.put(EventTypes.PROCESS_PAYMENT, KafkaTopics.PAYMENT_COMMANDS);
        topics.put(EventTypes.REFUND_PAYMENT, KafkaTopics.PAYMENT_COMMANDS);
        topics.put(EventTypes.RESTOCK_INVENTORY, KafkaTopics.INVENTORY_COMMANDS);
        topics.put(EventTypes.NOTIFICATION_SEND, KafkaTopics.NOTIFICATION_SEND);

        topics.put(EventTypes.INVENTORY_RESERVED, KafkaTopics.INVENTORY_RESERVED);
        topics.put(EventTypes.INVENTORY_FAILED, KafkaTopics.INVENTORY_FAILED);
        topics.put(EventTypes.INVENTORY_RELEASED, KafkaTopics.INVENTORY_RELEASED);
        topics.put(EventTypes.INVENTORY_CONFIRMED, KafkaTopics.INVENTORY_CONFIRMED);
        topics.put(EventTypes.INVENTORY_RESTOCKED, KafkaTopics.INVENTORY_RESTOCKED);
        topics.put(EventTypes.PAYMENT_COMPLETED, KafkaTopics.PAYMENT_COMPLETED);
        topics.put(EventTypes.PAYMENT_FAILED, KafkaTopics.PAYMENT_FAILED);
        topics.put(EventTypes.PAYMENT_REFUNDED, KafkaTopics.PAYMENT_REFUNDED);
        topics.put(EventTypes.NOTIFICATION_SENT, KafkaTopics.NOTIFICATION_SENT);

        topics.put(EventTypes.ORDER_CREATED, KafkaTopics.ORDER_CREATED);
        topics.put(EventTypes.ORDER_COMPLETED, KafkaTopics.ORDER_COMPLETED);
        topics.put(EventTypes.USER_ERASED, KafkaTopics.USER_ERASED);
        topics.put(EventTypes.ORDER_CANCELLED, KafkaTopics.ORDER_CANCELLED);

        // Returns. Their own topics rather than the payment saga's, because nothing
        // orchestrates a refund for goods that have already come back.
        topics.put(EventTypes.REFUND_RETURN, KafkaTopics.RETURN_REFUND_COMMANDS);
        topics.put(EventTypes.RETURN_REFUNDED, KafkaTopics.RETURN_REFUNDED);
        topics.put(EventTypes.RESTOCK_RETURNED_GOODS, KafkaTopics.RETURN_RESTOCK_COMMANDS);
        TOPIC_BY_ALIAS = Map.copyOf(topics);
    }

    private KafkaTypeMappings() {
        throw new AssertionError("No instances");
    }

    /**
     * Renders the mapping table in the format expected by {@code spring.json.type.mapping},
     * i.e. {@code alias:fqcn,alias:fqcn}.
     */
    public static String mappingProperty() {
        return CLASS_BY_ALIAS.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> e.getKey() + ":" + e.getValue().getName())
                .collect(Collectors.joining(","));
    }

    /** @throws IllegalArgumentException when the alias is unknown. */
    public static Class<? extends DomainEvent> classFor(String alias) {
        Class<? extends DomainEvent> type = CLASS_BY_ALIAS.get(alias);
        if (type == null) {
            throw new IllegalArgumentException("Unknown event type alias: " + alias);
        }
        return type;
    }

    /** @throws IllegalArgumentException when the type is not a registered message. */
    public static String aliasFor(Class<? extends DomainEvent> type) {
        String alias = ALIAS_BY_CLASS.get(type);
        if (alias == null) {
            throw new IllegalArgumentException("Unregistered event type: " + type.getName());
        }
        return alias;
    }

    /** @throws IllegalArgumentException when the alias is unknown. */
    public static String topicFor(String alias) {
        String topic = TOPIC_BY_ALIAS.get(alias);
        if (topic == null) {
            throw new IllegalArgumentException("No topic registered for event type: " + alias);
        }
        return topic;
    }

    public static Map<String, Class<? extends DomainEvent>> classByAlias() {
        return CLASS_BY_ALIAS;
    }
}
