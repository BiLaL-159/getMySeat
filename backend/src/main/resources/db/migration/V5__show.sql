-- Shows: one occurrence of an Event at an approved Venue, and the Section Price the Organizer sets for each of
-- the Venue's Sections. Phase 1 creates no per-Show inventory; Phase 2 adds it keyed on Show x Seat and
-- Show x General Admission Section.
CREATE TABLE show (
    id            uuid        PRIMARY KEY,
    event_id      uuid        NOT NULL REFERENCES event (id),
    venue_id      uuid        NOT NULL REFERENCES venue (id),
    starts_at     timestamptz NOT NULL,
    status        varchar(16) NOT NULL,
    created_at    timestamptz NOT NULL,
    published_at  timestamptz,
    version       bigint      NOT NULL,
    CONSTRAINT show_status CHECK (status IN ('DRAFT', 'PUBLISHED')),
    -- Only a published Show has a publication time.
    CONSTRAINT show_published CHECK ((status = 'PUBLISHED') = (published_at IS NOT NULL))
);

-- The Shows of an Event, soonest first.
CREATE INDEX show_event ON show (event_id, starts_at, id);

CREATE TABLE section_price (
    show_id       uuid       NOT NULL REFERENCES show (id) ON DELETE CASCADE,
    section_id    uuid       NOT NULL REFERENCES section (id),
    -- Whole paise, so amounts never round.
    amount_paise  bigint     NOT NULL,
    currency      varchar(3) NOT NULL,
    -- One Section Price per Show and Section.
    PRIMARY KEY (show_id, section_id),
    CONSTRAINT section_price_amount_positive CHECK (amount_paise > 0),
    CONSTRAINT section_price_currency CHECK (currency = 'INR')
);
