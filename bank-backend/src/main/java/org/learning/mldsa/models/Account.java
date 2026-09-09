package org.learning.mldsa.models;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;

/**
 * A customer's account.
 *
 * Note what is deliberately absent: there is no balance field. A balance is derived by
 * summing this account's postings (see PostingRepository.sumBalance), never stored and
 * mutated in place. A stored counter cannot answer "how did it reach this number", cannot
 * be reconciled against its own history, and silently carries forward the effect of any
 * operation that once updated it wrongly — which is exactly what an immutable posting
 * history avoids.
 */
@Entity
@Table(name = "accounts", schema = "ledger")
@Data
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "account_id")
    private Long accountId;

    // The 16-digit number the customer sees, distinct from the surrogate accountId above.
    // Unique so it can be used to address the account, and never reissued.
    @Column(name = "account_number", nullable = false, unique = true, updatable = false, length = 16)
    private String accountNumber;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false, updatable = false)
    private User owner;

    // Single currency for now, but modelled from the start with a real value rather than
    // assumed: supporting a second currency later becomes new data, not a schema change.
    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "opened_at", nullable = false, updatable = false)
    private Instant openedAt;
}
