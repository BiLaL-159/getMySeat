-- The cleanup job looks for active Holds whose expiry time has come.
CREATE INDEX hold_active_expires_at ON hold (expires_at) WHERE status = 'ACTIVE';
