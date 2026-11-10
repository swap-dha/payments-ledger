-- Days 7-8: concurrent debits must never overdraw an account.
--
-- Decision: pessimistic row locks, not optimistic versioning. A hot account (a merchant's
-- settlement account, a bank's own cash account) receives many concurrent postings; with
-- optimistic locking most of them would fail and retry. Row locks queue them instead.
-- The adapter takes the locks explicitly (SELECT ... FOR UPDATE in account-id order, so two
-- transfers in opposite directions cannot deadlock); this trigger is the backstop that holds
-- even for writes that bypass the application.

ALTER TABLE account DROP COLUMN version;  -- planned for optimistic locking; not needed now

ALTER TABLE account ADD COLUMN allow_negative BOOLEAN NOT NULL DEFAULT false;

-- Signed running balance (+ debit, - credit). Derived data: posting remains the source of
-- truth, and a reconciliation test asserts the two always agree.
ALTER TABLE account ADD COLUMN balance_minor BIGINT NOT NULL DEFAULT 0;

UPDATE account a
SET balance_minor = COALESCE((SELECT SUM(p.amount_minor) FROM posting p WHERE p.account_id = a.id), 0);

CREATE FUNCTION apply_posting_to_balance() RETURNS trigger AS $$
DECLARE
    acc     account%ROWTYPE;
    natural_balance BIGINT;
BEGIN
    -- The UPDATE takes the row lock, so concurrent postings to one account are serialised here.
    UPDATE account
    SET balance_minor = balance_minor + NEW.amount_minor
    WHERE id = NEW.account_id
    RETURNING * INTO acc;

    natural_balance := CASE WHEN acc.type IN ('ASSET', 'EXPENSE') THEN acc.balance_minor ELSE -acc.balance_minor END;

    IF NOT acc.allow_negative AND natural_balance < 0 THEN
        RAISE EXCEPTION 'insufficient funds: account % would have a balance of % minor units', acc.code, natural_balance;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER posting_updates_balance
    AFTER INSERT ON posting
    FOR EACH ROW EXECUTE FUNCTION apply_posting_to_balance();
