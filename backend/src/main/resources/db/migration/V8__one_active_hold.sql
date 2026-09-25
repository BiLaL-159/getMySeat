-- A Customer has at most one active Hold per Show: a new Hold releases the old one in the same transaction, and
-- this index turns two Holds racing past that release into a unique violation instead of two active Holds.
CREATE UNIQUE INDEX hold_one_active_per_customer_show ON hold (customer_subject, show_id) WHERE status = 'ACTIVE';
