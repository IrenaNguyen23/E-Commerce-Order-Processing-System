package com.commerceflow.authservice.entity;

/** Roles granted to an account; written into the {@code roles} claim of the access token. */
public enum Role {

    /** Ordinary shopper: may place and read their own orders. */
    CUSTOMER,

    /** Back-office operator: may manage the catalogue and read every order. */
    ADMIN
}
