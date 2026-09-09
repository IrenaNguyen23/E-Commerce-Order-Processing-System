-- =====================================================================================
-- What the browser needs to finish a payment.
--
-- With a real acquirer the saga creates a charge the customer has not authorised yet: there is
-- no card on file and nobody at the keyboard when PROCESS_PAYMENT runs. The client secret is
-- what lets them complete it on the order screen afterwards.
--
-- It is stored rather than fetched on demand because the order screen polls every two seconds,
-- and asking the acquirer each time would turn one customer waiting into a rate limit. It is
-- scoped to a single PaymentIntent and authorises paying that intent and nothing else, which is
-- what makes it safe to send to a browser at all.
-- =====================================================================================

ALTER TABLE payments ADD COLUMN client_secret VARCHAR(256);

COMMENT ON COLUMN payments.client_secret IS
    'Authorises the browser to complete this one charge. Returned only to the payment owner.';
