package com.commerceflow.common.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.commerceflow.common.constant.EventTypes;
import com.commerceflow.common.constant.KafkaTopics;
import com.commerceflow.common.event.DomainEvent;
import com.commerceflow.common.event.OrderCreatedEvent;

class KafkaTypeMappingsTest {

    @Test
    @DisplayName("every registered alias round trips to its class and back")
    void aliasRoundTrip() {
        KafkaTypeMappings.classByAlias().forEach((alias, type) -> {
            assertThat(KafkaTypeMappings.classFor(alias)).isEqualTo(type);
            assertThat(KafkaTypeMappings.aliasFor(type)).isEqualTo(alias);
        });
    }

    @Test
    @DisplayName("every business topic carries at least one message type, and no type escapes the list")
    void everyTopicIsCovered() {
        Set<String> mappedTopics = KafkaTypeMappings.classByAlias().keySet().stream()
                .map(KafkaTypeMappings::topicFor)
                .collect(Collectors.toSet());

        // A topic with nothing bound to it is a topic nobody can publish to.
        assertThat(mappedTopics).containsAll(KafkaTopics.ALL);
        // And a type bound to a topic outside the list would never be created or documented.
        assertThat(KafkaTopics.ALL).containsAll(mappedTopics);
    }

    @Test
    @DisplayName("the three inventory commands deliberately share one topic")
    void inventoryCommandsShareATopic() {
        // Not an accident, and not a 1:1 mapping. All three address the same participant and the
        // same aggregate, so one partitioned topic guarantees a release can never overtake the
        // reserve it compensates. Splitting them would reintroduce that race.
        assertThat(KafkaTypeMappings.topicFor(EventTypes.RESERVE_INVENTORY))
                .isEqualTo(KafkaTopics.INVENTORY_COMMANDS);
        assertThat(KafkaTypeMappings.topicFor(EventTypes.RELEASE_INVENTORY))
                .isEqualTo(KafkaTopics.INVENTORY_COMMANDS);
        assertThat(KafkaTypeMappings.topicFor(EventTypes.CONFIRM_INVENTORY))
                .isEqualTo(KafkaTopics.INVENTORY_COMMANDS);
    }

    @Test
    @DisplayName("the rendered property is in alias:fqcn form and covers every event")
    void mappingPropertyFormat() {
        String property = KafkaTypeMappings.mappingProperty();

        List<String> pairs = Arrays.asList(property.split(","));
        assertThat(pairs).hasSize(KafkaTypeMappings.classByAlias().size());
        assertThat(pairs).allSatisfy(pair -> {
            String[] parts = pair.split(":");
            assertThat(parts).hasSize(2);
            assertThat(KafkaTypeMappings.classFor(parts[0]).getName()).isEqualTo(parts[1]);
        });
        assertThat(property).contains(EventTypes.ORDER_CREATED + ":" + OrderCreatedEvent.class.getName());
    }

    @Test
    @DisplayName("unknown aliases fail loudly rather than silently dropping a message")
    void unknownAliasIsRejected() {
        assertThatThrownBy(() -> KafkaTypeMappings.classFor("NOPE"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> KafkaTypeMappings.topicFor("NOPE"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> KafkaTypeMappings.aliasFor(UnregisteredEvent.class))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static final class UnregisteredEvent extends DomainEvent {
        @Override
        public String partitionKey() {
            return "none";
        }
    }
}
