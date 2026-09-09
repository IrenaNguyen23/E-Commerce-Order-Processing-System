package com.commerceflow.orderservice.config;

import org.springframework.boot.autoconfigure.cache.RedisCacheManagerBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import com.commerceflow.orderservice.dto.OrderResponse;
import com.commerceflow.orderservice.service.OrderQueryService;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Redis cache in front of the read model.
 *
 * <p>Bound to the concrete {@code OrderResponse} type rather than relying on Jackson default
 * typing: records are implicitly final, so no polymorphic type header would ever be written and
 * the entry would come back as a map.
 */
@Configuration(proxyBeanMethods = false)
public class CacheConfig {

    @Bean
    public RedisCacheManagerBuilderCustomizer orderCacheCustomizer(ObjectMapper objectMapper,
                                                                   OrderProperties properties) {
        RedisCacheConfiguration orderCache = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(properties.getReadModelCacheTtl())
                .disableCachingNullValues()
                .prefixCacheNameWith("commerceflow:order:")
                .serializeKeysWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new Jackson2JsonRedisSerializer<>(objectMapper,
                                OrderResponse.class)));

        return builder -> builder.withCacheConfiguration(OrderQueryService.CACHE_ORDER, orderCache);
    }
}
