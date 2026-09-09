package com.commerceflow.orderservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.commerceflow.common.context.CorrelationContext;

/** HTTP client used for the single synchronous hop into Inventory Service. */
@Configuration(proxyBeanMethods = false)
public class RestClientConfig {

    @Bean
    public RestClient inventoryRestClient(RestClient.Builder builder,
                                          InventoryClientProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.getConnectTimeout());
        requestFactory.setReadTimeout(properties.getReadTimeout());

        return builder
                .baseUrl(properties.getBaseUrl())
                .requestFactory(requestFactory)
                // Propagate the correlation id so one customer action stays one trace across
                // the synchronous hop as well as the asynchronous ones.
                .requestInterceptor((request, body, execution) -> {
                    String correlationId = CorrelationContext.get();
                    if (correlationId != null) {
                        request.getHeaders().add(CorrelationContext.HEADER, correlationId);
                    }
                    return execution.execute(request, body);
                })
                .build();
    }
}
