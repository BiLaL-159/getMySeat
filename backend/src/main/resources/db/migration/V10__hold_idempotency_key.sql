-- The Idempotency-Key of each Hold creation that succeeded, per Customer, so a retry gets the original Hold back.
-- A request that fails writes nothing here, so a retry with its key tries again. The cleanup job deletes keys after
-- 24 hours.
CREATE TABLE hold_idempotency_key (
    -- The Keycloak subject of the Customer who sent the key: the same key from two Customers is two keys.
    customer_subject  uuid         NOT NULL,
    idempotency_key   varchar(255) NOT NULL,
    -- SHA-256, in hex, of the Show id and the request body with its items sorted.
    request_hash      varchar(64)  NOT NULL,
    -- Deferred, because the key is written before its Hold: a second request with the same key then waits on the
    -- primary key before it can look for, and release, the Hold the first one is making.
    hold_id           uuid         NOT NULL REFERENCES hold (id) DEFERRABLE INITIALLY DEFERRED,
    created_at        timestamptz  NOT NULL,
    PRIMARY KEY (customer_subject, idempotency_key)
);

CREATE INDEX hold_idempotency_key_created_at ON hold_idempotency_key (created_at);
