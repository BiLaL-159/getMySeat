-- Organizer Applications: a Customer asks to become an Organizer and an Admin decides.
CREATE TABLE organizer_application (
    id                 uuid          PRIMARY KEY,
    applicant_subject  uuid          NOT NULL,
    -- Copied from the token; Keycloak allows 255 characters each for first and last name.
    applicant_name     varchar(512)  NOT NULL,
    applicant_email    varchar(320),
    organisation_name  varchar(200)  NOT NULL,
    contact_phone      varchar(32)   NOT NULL,
    description        varchar(2000) NOT NULL,
    status             varchar(16)   NOT NULL,
    rejection_reason   varchar(1000),
    decided_by         uuid,
    decided_at         timestamptz,
    created_at         timestamptz   NOT NULL,
    version            bigint        NOT NULL,
    CONSTRAINT organizer_application_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    -- A decision always records who made it and when; only a rejection carries a reason.
    CONSTRAINT organizer_application_decision CHECK (
        (status = 'PENDING' AND decided_by IS NULL AND decided_at IS NULL AND rejection_reason IS NULL)
        OR (status = 'APPROVED' AND decided_by IS NOT NULL AND decided_at IS NOT NULL AND rejection_reason IS NULL)
        OR (status = 'REJECTED' AND decided_by IS NOT NULL AND decided_at IS NOT NULL AND rejection_reason IS NOT NULL)
    )
);

-- At most one PENDING application per applicant.
CREATE UNIQUE INDEX organizer_application_one_pending
    ON organizer_application (applicant_subject) WHERE status = 'PENDING';

-- The Admin queue (by status, oldest first) and an applicant's latest application.
CREATE INDEX organizer_application_queue ON organizer_application (status, created_at, id);
CREATE INDEX organizer_application_applicant ON organizer_application (applicant_subject, created_at);
