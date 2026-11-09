-- Double-entry ledger schema.
-- Amounts are stored as BIGINT minor units (paise, cents) to avoid floating-point error.
-- A journal entry is the atomic unit of change; its postings must sum to zero.

CREATE TABLE account (
    id            UUID PRIMARY KEY,
    code          VARCHAR(64)  NOT NULL UNIQUE,
    name          VARCHAR(255) NOT NULL,
    currency      CHAR(3)      NOT NULL,
    type          VARCHAR(16)  NOT NULL CHECK (type IN ('ASSET', 'LIABILITY', 'EQUITY', 'INCOME', 'EXPENSE')),
    version       BIGINT       NOT NULL DEFAULT 0,  -- optimistic locking (Days 7-8)
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE journal_entry (
    id               UUID PRIMARY KEY,
    idempotency_key  VARCHAR(128) NOT NULL UNIQUE,  -- retries never double-post (Days 5-6)
    description      VARCHAR(512),
    currency         CHAR(3)      NOT NULL,
    effective_date   DATE         NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE posting (
    id                UUID PRIMARY KEY,
    journal_entry_id  UUID   NOT NULL REFERENCES journal_entry (id),
    account_id        UUID   NOT NULL REFERENCES account (id),
    amount_minor      BIGINT NOT NULL CHECK (amount_minor <> 0),  -- signed: + debit, - credit
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_posting_account   ON posting (account_id);
CREATE INDEX idx_posting_journal   ON posting (journal_entry_id);
CREATE INDEX idx_journal_effective ON journal_entry (effective_date);

-- Ledger rows are append-only: corrections are new reversing entries, never updates.
CREATE FUNCTION forbid_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'ledger table % is append-only', TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER journal_entry_append_only
    BEFORE UPDATE OR DELETE ON journal_entry
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();

CREATE TRIGGER posting_append_only
    BEFORE UPDATE OR DELETE ON posting
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();

-- Defence in depth: the domain enforces zero-sum, and so does the database,
-- checked at commit time so all postings of an entry can be inserted first.
CREATE FUNCTION check_entry_balanced() RETURNS trigger AS $$
DECLARE
    total BIGINT;
BEGIN
    SELECT COALESCE(SUM(amount_minor), 0) INTO total
    FROM posting WHERE journal_entry_id = NEW.journal_entry_id;
    IF total <> 0 THEN
        RAISE EXCEPTION 'journal entry % is unbalanced by % minor units', NEW.journal_entry_id, total;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER posting_balanced
    AFTER INSERT ON posting
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION check_entry_balanced();
