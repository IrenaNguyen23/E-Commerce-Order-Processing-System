package com.commerceflow.inventoryservice.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Turning what a customer typed into a {@code tsquery}.
 *
 * <p>Small, and worth pinning, because everything it handles is something that reaches the
 * database as SQL. {@code to_tsquery} has its own operator syntax: an unfiltered {@code &} or
 * {@code !} is a syntax error raised at somebody who was only trying to search for a product.
 */
class ProductSearchQueryTest {

    @Test
    @DisplayName("words are ANDed, so more words narrow the result")
    void wordsAreAnded() {
        // "laptop stand" should find laptop stands, not everything mentioning either word.
        assertThat(ProductService.toTsQuery("laptop stand")).isEqualTo("laptop & stand:*");
    }

    @Test
    @DisplayName("only the last word is a prefix, so search-as-you-type works")
    void lastWordIsAPrefix() {
        // A customer half way through typing "laptop" should already be seeing laptops. Making
        // every word a prefix instead would make "a b" match nearly the whole catalogue.
        assertThat(ProductService.toTsQuery("lap")).isEqualTo("lap:*");
        assertThat(ProductService.toTsQuery("wireless ear")).isEqualTo("wireless & ear:*");
    }

    @Test
    @DisplayName("tsquery operators typed by a customer are dropped, not escaped")
    void operatorsAreStripped() {
        // The whole point. Passing this through would be a database syntax error thrown at
        // somebody searching for a product.
        assertThat(ProductService.toTsQuery("laptop & !stand")).isEqualTo("laptop & stand:*");
        assertThat(ProductService.toTsQuery("a|b")).isEqualTo("a & b:*");
        assertThat(ProductService.toTsQuery("'; DROP TABLE products; --"))
                .isEqualTo("drop & table & products:*");
    }

    @Test
    @DisplayName("digits and accented letters survive")
    void alphanumericsSurvive() {
        assertThat(ProductService.toTsQuery("CF-LAPTOP-001")).isEqualTo("cf & laptop & 001:*");
        assertThat(ProductService.toTsQuery("café")).isEqualTo("café:*");
    }

    @Test
    @DisplayName("a term with nothing searchable in it produces null rather than a broken query")
    void punctuationOnlyIsNull() {
        // Null tells the caller to fall back to browsing. An empty tsquery string would be a
        // syntax error, and a bare ":*" would match everything.
        assertThat(ProductService.toTsQuery("!!!")).isNull();
        assertThat(ProductService.toTsQuery("   ")).isNull();
    }
}
