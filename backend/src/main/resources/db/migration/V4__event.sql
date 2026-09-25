-- Events: what an Organizer sells, independent of when or where it happens. Drafts are private to their owner;
-- published Events are public. V3 is taken by Venues.
CREATE TABLE event (
    id             uuid          PRIMARY KEY,
    -- The Organizer's Keycloak subject; the access module owns people, so there's no foreign key.
    owner_subject  uuid          NOT NULL,
    title          varchar(200)  NOT NULL,
    description    varchar(5000) NOT NULL,
    category       varchar(32)   NOT NULL,
    -- ISO 639-1 language code, such as en or hi.
    language       varchar(2)    NOT NULL,
    status         varchar(16)   NOT NULL,
    created_at     timestamptz   NOT NULL,
    published_at   timestamptz,
    version        bigint        NOT NULL,
    CONSTRAINT event_status CHECK (status IN ('DRAFT', 'PUBLISHED')),
    CONSTRAINT event_category CHECK (category IN
        ('MUSIC', 'COMEDY', 'THEATRE', 'DANCE', 'SPORTS', 'CONFERENCE', 'WORKSHOP', 'FAMILY', 'OTHER')),
    -- Only a published Event has a publication time.
    CONSTRAINT event_published CHECK ((status = 'PUBLISHED') = (published_at IS NOT NULL))
);

-- An Organizer's own Events, and the public list of published Events, newest first.
CREATE INDEX event_owner ON event (owner_subject, created_at);
CREATE INDEX event_published_recent ON event (published_at DESC, id) WHERE status = 'PUBLISHED';
