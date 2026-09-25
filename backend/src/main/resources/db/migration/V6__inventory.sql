-- Per-Show inventory, owned by the booking module and created when a Show is published. Show, Section and Seat
-- ids are plain uuids with no foreign keys into the catalogue's tables: booking only knows the catalogue through
-- its public API. An approved Venue's layout never changes, so the ids stay valid.

-- One row per Seat of each Seated Section of a published Show. Phase 3 adds BOOKED.
CREATE TABLE seat_inventory (
    show_id  uuid        NOT NULL,
    seat_id  uuid        NOT NULL,
    status   varchar(16) NOT NULL,
    -- The Hold that owns the Seat while it's HELD.
    hold_id  uuid,
    PRIMARY KEY (show_id, seat_id),
    CONSTRAINT seat_inventory_status CHECK (status IN ('AVAILABLE', 'HELD')),
    CONSTRAINT seat_inventory_hold CHECK ((status = 'HELD') = (hold_id IS NOT NULL))
);

-- One remaining-capacity counter per General Admission Section of a published Show.
CREATE TABLE general_admission_inventory (
    show_id     uuid    NOT NULL,
    section_id  uuid    NOT NULL,
    capacity    integer NOT NULL,
    available   integer NOT NULL,
    PRIMARY KEY (show_id, section_id),
    -- The last line of defence against overselling.
    CONSTRAINT general_admission_inventory_available CHECK (available >= 0 AND available <= capacity)
);

-- Shows published before Phase 2 get their inventory now, with everything still available. This one-off backfill
-- is the only place booking reads the catalogue's tables; from here on, inventory is created from the catalogue's
-- ShowPublished event and read port.
INSERT INTO seat_inventory (show_id, seat_id, status)
SELECT show.id, seat.id, 'AVAILABLE'
FROM show
JOIN section ON section.venue_id = show.venue_id
JOIN seat ON seat.section_id = section.id
WHERE show.status = 'PUBLISHED';

INSERT INTO general_admission_inventory (show_id, section_id, capacity, available)
SELECT show.id, section.id, section.capacity, section.capacity
FROM show
JOIN section ON section.venue_id = show.venue_id
WHERE show.status = 'PUBLISHED' AND section.kind = 'GENERAL_ADMISSION';
