package com.commerceflow.orderservice.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.commerceflow.common.dto.ApiResponse;
import com.commerceflow.common.exception.BusinessException;
import com.commerceflow.common.exception.ErrorCode;
import com.commerceflow.orderservice.dto.CatalogProduct;
import com.commerceflow.orderservice.dto.ProductLookupRequest;

import lombok.extern.slf4j.Slf4j;

/**
 * Reads prices and product names from Inventory Service while an order is being placed.
 *
 * <p>This is the one synchronous hop in the whole flow, and it is deliberate: an order has to be
 * priced before it can be accepted, and pricing is a read of data another service owns. Everything
 * that follows — reserving, charging, notifying — is driven asynchronously by the orchestrator.
 *
 * <p>It is also a read, never a write, so a catalogue outage can only reject new orders; it can
 * never leave a saga half finished.
 */
@Slf4j
@Component
public class ProductCatalogClient {

    private static final ParameterizedTypeReference<ApiResponse<List<CatalogProduct>>> LOOKUP_TYPE =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient restClient;

    public ProductCatalogClient(RestClient inventoryRestClient) {
        this.restClient = inventoryRestClient;
    }

    /**
     * Prices a basket in a single call.
     *
     * @param productIds ids to look up; duplicates are tolerated
     * @return the products that exist, keyed by id — a missing id simply has no entry
     * @throws BusinessException with {@link ErrorCode#SERVICE_UNAVAILABLE} when the catalogue
     *     cannot be reached, so the customer gets a 503 rather than a half-priced order
     */
    public Map<UUID, CatalogProduct> lookup(List<UUID> productIds) {
        if (productIds == null || productIds.isEmpty()) {
            return Map.of();
        }
        List<UUID> distinct = productIds.stream().distinct().toList();

        try {
            ApiResponse<List<CatalogProduct>> response = restClient.post()
                    .uri("/api/products/lookup")
                    .body(new ProductLookupRequest(distinct))
                    .retrieve()
                    .body(LOOKUP_TYPE);

            List<CatalogProduct> products =
                    response == null || response.data() == null ? List.of() : response.data();

            Map<UUID, CatalogProduct> byId = new LinkedHashMap<>();
            products.forEach(product -> byId.put(product.id(), product));
            return byId;
        } catch (RestClientException ex) {
            log.error("Catalogue lookup for {} product(s) failed", distinct.size(), ex);
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE,
                    "The product catalogue is currently unavailable, please retry", ex);
        }
    }
}
