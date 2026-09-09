package com.commerceflow.authservice.entity;

/**
 * What an address may be used for.
 *
 * <p>Kept separate because they genuinely differ: a parcel goes where someone can receive it, a
 * card statement goes where the bank has the cardholder registered. Most customers use one address
 * for both, which is why {@link #BOTH} is the default rather than making people fill the form
 * twice.
 */
public enum AddressType {

    /** Parcels only. */
    SHIPPING,

    /** Invoices and card verification only. */
    BILLING,

    /** The common case. */
    BOTH;

    /** Whether this address may be offered when the customer is choosing where to ship. */
    public boolean usableForShipping() {
        return this != BILLING;
    }

    /** Whether this address may be offered as a billing address. */
    public boolean usableForBilling() {
        return this != SHIPPING;
    }
}
