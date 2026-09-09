-- =====================================================================================
-- C11 -- guest checkout.
--
-- A guest gets a real account with no password rather than a nullable user_id on orders.
--
-- The nullable alternative sounds lighter and is not: the basket, the address book, the
-- order history, the payment and the saga would each grow a second code path for a customer
-- who might not be a customer, and the first one that forgets is a null pointer somewhere
-- expensive.
--
-- It also gives the customer something: setting a password later through the ordinary reset
-- flow turns the guest account into theirs, with the order history already in it.
-- =====================================================================================

ALTER TABLE users ADD COLUMN guest BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN users.guest IS
    'Created by a checkout rather than by somebody registering. The account works normally;
     the flag exists so an operator can tell a real sign-up from a checkout -- otherwise the
     two are indistinguishable in the accounts list and every registration metric is wrong.';

-- Guests have a random password hash nobody knows, so sign-in simply fails rather than
-- relying on every future code path remembering to check this flag before comparing.

CREATE INDEX idx_users_guest ON users (guest) WHERE guest;
