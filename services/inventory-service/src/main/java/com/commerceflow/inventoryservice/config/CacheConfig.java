package com.commerceflow.inventoryservice.config;

import java.time.Duration;

import org.springframework.boot.autoconfigure.cache.RedisCacheManagerBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import com.commerceflow.inventoryservice.dto.ProductResponse;
import com.commerceflow.inventoryservice.service.ProductService;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Redis cache for single product reads.
 *
 * <p>Each cache is bound to one concrete value type rather than relying on Jackson default
 * typing: {@code ProductResponse} is a record, and records are implicitly final, so polymorphic
 * type headers would never be written and the cached entry would come back as a map.
 */
@Configuration(proxyBeanMethods = false)
public class CacheConfig {

    @Bean
    public RedisCacheManagerBuilderCustomizer productCacheCustomizer(ObjectMapper objectMapper) {
        RedisCacheConfiguration productCache = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(10))
                .disableCachingNullValues()
                .prefixCacheNameWith("commerceflow:inventory:")
                .serializeKeysWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new Jackson2JsonRedisSerializer<>(objectMapper,
                                ProductResponse.class)));

        return builder -> builder
                .withCacheConfiguration(ProductService.CACHE_BY_ID, productCache)
                .withCacheConfiguration(ProductService.CACHE_BY_SKU, productCache);
    }
}
