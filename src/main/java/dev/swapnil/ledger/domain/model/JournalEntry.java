package dev.swapnil.ledger.domain.model;

import java.time.LocalDate;
import java.util.Currency;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The atomic unit of change in the ledger. Invariants, enforced at construction so an
 * invalid entry can never exist in memory:
 * <ul>
 *   <li>at least two postings</li>
 *   <li>postings sum to exactly zero (every debit has an equal credit)</li>
 *   <li>at least one debit and one credit</li>
 *   <li>no account appears twice (net it instead)</li>
 *   <li>an idempotency key, so a retried request maps to the same entry</li>
 * </ul>
 * The database enforces zero-sum again at commit time (see V1__init_ledger.sql).
 */
public final class JournalEntry {

    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;

    private final UUID id;
    private final String idempotencyKey;
    private final Currency currency;
    private final LocalDate effectiveDate;
    private final String description;
    private final List<Posting> postings;

    private JournalEntry(UUID id, String idempotencyKey, Currency currency,
                         LocalDate effectiveDate, String description, List<Posting> postings) {
        this.id = id;
        this.idempotencyKey = idempotencyKey;
        this.currency = currency;
        this.effectiveDate = effectiveDate;
        this.description = description;
        this.postings = postings;
    }

    public static JournalEntry create(String idempotencyKey, Currency currency, LocalDate effectiveDate,
                                      String description, List<Posting> postings) {
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(effectiveDate, "effectiveDate");
        Objects.requireNonNull(postings, "postings");
        validateIdempotencyKey(idempotencyKey);

        List<Posting> copy = List.copyOf(postings);
        if (copy.size() < 2) {
            throw new InvalidJournalEntryException("a journal entry needs at least two postings, got " + copy.size());
        }

        long sum = 0;
        boolean hasDebit = false;
        boolean hasCredit = false;
        var seen = new HashSet<AccountId>();
        for (Posting p : copy) {
            sum = Math.addExact(sum, p.amountMinor());
            hasDebit |= p.isDebit();
            hasCredit |= !p.isDebit();
            if (!seen.add(p.accountId())) {
                throw new InvalidJournalEntryException("account " + p.accountId() + " appears more than once");
            }
        }
        if (sum != 0) {
            throw new InvalidJournalEntryException("postings must sum to zero, off by " + sum + " minor units");
        }
        if (!hasDebit || !hasCredit) {
            throw new InvalidJournalEntryException("a journal entry needs at least one debit and one credit");
        }

        return new JournalEntry(UUID.randomUUID(), idempotencyKey, currency, effectiveDate, description, copy);
    }

    /** The common case: move {@code amountMinor} from one account to another. */
    public static JournalEntry transfer(String idempotencyKey, AccountId from, AccountId to, long amountMinor,
                                        Currency currency, LocalDate effectiveDate, String description) {
        if (from.equals(to)) {
            throw new InvalidJournalEntryException("cannot transfer to the same account");
        }
        return create(idempotencyKey, currency, effectiveDate, description,
                List.of(Posting.credit(from, amountMinor), Posting.debit(to, amountMinor)));
    }

    private static void validateIdempotencyKey(String key) {
        if (key == null || key.isBlank()) {
            throw new InvalidJournalEntryException("idempotency key is required");
        }
        if (key.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new InvalidJournalEntryException("idempotency key longer than " + MAX_IDEMPOTENCY_KEY_LENGTH);
        }
    }

    public UUID id() { return id; }
    public String idempotencyKey() { return idempotencyKey; }
    public Currency currency() { return currency; }
    public LocalDate effectiveDate() { return effectiveDate; }
    public String description() { return description; }
    public List<Posting> postings() { return postings; }
}
