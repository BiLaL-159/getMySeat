-- Holds: a Customer's temporary claim on a Show's inventory, owned by the booking module. As with inventory, Show,
-- Section and Seat ids are plain uuids with no foreign keys into the catalogue's tables.
CREATE TABLE hold (
    id                uuid        PRIMARY KEY,
    -- The Keycloak subject of the Customer who owns the Hold.
    customer_subject  uuid        NOT NULL,
    show_id           uuid        NOT NULL,
    status            varchar(16) NOT NULL,
    expires_at        timestamptz NOT NULL,
    created_at        timestamptz NOT NULL,
    -- Whole paise: the sum of every item's Section Price times its quantity.
    total_paise       bigint      NOT NULL,
    currency          varchar(3)  NOT NULL,
    version           bigint      NOT NULL,
    -- Phase 3 adds the status of a Hold converted to a Booking.
    CONSTRAINT hold_status CHECK (status IN ('ACTIVE', 'RELEASED', 'EXPIRED')),
    CONSTRAINT hold_total_positive CHECK (total_paise > 0)
);

-- What a Hold claims: one Seat, or a quantity of General Admission places in one Section. The Section Price and,
-- for a Seat, its row label and number are copied at Hold time, so the Hold reads the same for its whole life.
CREATE TABLE hold_item (
    hold_id           uuid        NOT NULL REFERENCES hold (id) ON DELETE CASCADE,
    position          integer     NOT NULL,
    kind              varchar(24) NOT NULL,
    section_id        uuid        NOT NULL,
    seat_id           uuid,
    row_label         varchar(3),
    seat_number       integer,
    quantity          integer     NOT NULL,
    price_paise       bigint      NOT NULL,
    currency          varchar(3)  NOT NULL,
    PRIMARY KEY (hold_id, position),
    CONSTRAINT hold_item_kind CHECK (kind IN ('SEAT', 'GENERAL_ADMISSION')),
    -- A Seat item names its Seat and is one ticket; a General Admission item names only its Section.
    CONSTRAINT hold_item_seat CHECK (
        (kind = 'SEAT' AND seat_id IS NOT NULL AND row_label IS NOT NULL AND seat_number IS NOT NULL AND quantity = 1)
        OR (kind = 'GENERAL_ADMISSION' AND seat_id IS NULL AND row_label IS NULL AND seat_number IS NULL
            AND quantity >= 1)),
    CONSTRAINT hold_item_price_positive CHECK (price_paise > 0)
);

-- A HELD Seat always belongs to a real Hold.
ALTER TABLE seat_inventory
    ADD CONSTRAINT seat_inventory_hold_fk FOREIGN KEY (hold_id) REFERENCES hold (id);

CREATE INDEX seat_inventory_hold ON seat_inventory (hold_id) WHERE hold_id IS NOT NULL;
