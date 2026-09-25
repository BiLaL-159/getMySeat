-- Venues and their Section layout. An Organizer drafts a Venue and an Admin approves it; once approved the
-- layout is fixed, and Seats keep their ids so Phase 2 inventory can reference them.
CREATE TABLE venue (
    id                uuid         PRIMARY KEY,
    owner_subject     uuid         NOT NULL,
    name              varchar(200) NOT NULL,
    address           varchar(500) NOT NULL,
    city              varchar(100) NOT NULL,
    time_zone         varchar(64)  NOT NULL,
    status            varchar(16)  NOT NULL,
    rejection_reason  varchar(1000),
    decided_by        uuid,
    decided_at        timestamptz,
    submitted_at      timestamptz,
    created_at        timestamptz  NOT NULL,
    version           bigint       NOT NULL,
    CONSTRAINT venue_status CHECK (status IN ('DRAFT', 'PENDING_REVIEW', 'APPROVED', 'REJECTED')),
    -- Decisions record who made them and when; only a rejection carries a reason.
    CONSTRAINT venue_decision CHECK (
        (status IN ('DRAFT', 'PENDING_REVIEW') AND decided_by IS NULL AND decided_at IS NULL AND rejection_reason IS NULL)
        OR (status = 'APPROVED' AND decided_by IS NOT NULL AND decided_at IS NOT NULL AND rejection_reason IS NULL)
        OR (status = 'REJECTED' AND decided_by IS NOT NULL AND decided_at IS NOT NULL AND rejection_reason IS NOT NULL)
    )
);

CREATE INDEX venue_owner ON venue (owner_subject, created_at);
CREATE INDEX venue_review_queue ON venue (status, submitted_at, id);
CREATE INDEX venue_approved_by_city ON venue (lower(city), lower(name)) WHERE status = 'APPROVED';

CREATE TABLE section (
    id        uuid         PRIMARY KEY,
    venue_id  uuid         NOT NULL REFERENCES venue (id) ON DELETE CASCADE,
    name      varchar(100) NOT NULL,
    kind      varchar(24)  NOT NULL,
    capacity  integer,
    position  integer      NOT NULL,
    CONSTRAINT section_kind CHECK (kind IN ('SEATED', 'GENERAL_ADMISSION')),
    -- General Admission Sections have a capacity; Seated Sections are sized by their Seats.
    CONSTRAINT section_capacity CHECK (
        (kind = 'GENERAL_ADMISSION' AND capacity IS NOT NULL AND capacity > 0)
        OR (kind = 'SEATED' AND capacity IS NULL)
    ),
    -- Lets seat reference (id, kind), so only Seated Sections can have Seats.
    CONSTRAINT section_id_kind UNIQUE (id, kind)
);

-- Section names are unique within a Venue, ignoring case.
CREATE UNIQUE INDEX section_name_per_venue ON section (venue_id, lower(name));

CREATE TABLE seat (
    id           uuid        PRIMARY KEY,
    section_id   uuid        NOT NULL,
    section_kind varchar(24) NOT NULL DEFAULT 'SEATED',
    row_label    varchar(3)  NOT NULL,
    seat_number  integer     NOT NULL,
    position     integer     NOT NULL,
    CONSTRAINT seat_only_in_seated_section CHECK (section_kind = 'SEATED'),
    -- Rows are letters, so a label such as A11 can only mean row A, Seat 11.
    CONSTRAINT seat_row_label CHECK (row_label ~ '^[A-Z]{1,3}$'),
    CONSTRAINT seat_section FOREIGN KEY (section_id, section_kind) REFERENCES section (id, kind) ON DELETE CASCADE,
    CONSTRAINT seat_number_positive CHECK (seat_number > 0),
    -- Seat labels (row + number) are unique within a Section.
    CONSTRAINT seat_label_per_section UNIQUE (section_id, row_label, seat_number)
);
