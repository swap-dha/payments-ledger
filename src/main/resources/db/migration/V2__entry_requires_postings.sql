-- Closes the gap left by V1: a journal_entry row with no postings passed every check,
-- because the zero-sum trigger only fires when a posting is inserted.
-- Checked at COMMIT, so an entry can be inserted first and its postings after it.

CREATE FUNCTION check_entry_has_postings() RETURNS trigger AS $$
DECLARE
    legs INT;
BEGIN
    SELECT COUNT(*) INTO legs FROM posting WHERE journal_entry_id = NEW.id;
    IF legs < 2 THEN
        RAISE EXCEPTION 'journal entry % has % posting(s); at least 2 are required', NEW.id, legs;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER journal_entry_has_postings
    AFTER INSERT ON journal_entry
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION check_entry_has_postings();
