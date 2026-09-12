package org.learning.mldsa.repositories;

import org.learning.mldsa.models.AccountType;
import org.learning.mldsa.models.Posting;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface PostingRepository extends JpaRepository<Posting, Long> {

    Page<Posting> findByAccount_AccountIdOrderByPostedAtDescPostingIdDesc(Long accountId, Pageable pageable);

    /**
     * The account's balance, summed from its postings.
     *
     * Done as one aggregate in the database rather than by loading every posting and
     * adding them up in Java — an account's history only grows, so summing it in memory
     * would get slower for the rest of the account's life.
     *
     * coalesce covers the account that has no postings yet: SUM over no rows is null, and
     * a brand-new account's balance is zero, not "unknown".
     */
    @Query("""
            select coalesce(sum(
                case when p.direction = org.learning.mldsa.models.PostingDirection.CREDIT
                     then p.amount
                     else -p.amount
                end), 0)
            from Posting p
            where p.account.accountId = :accountId
            """)
    BigDecimal sumBalance(@Param("accountId") Long accountId);

    /**
     * Every account's balance, in one query, for the oversight view.
     *
     * Grouped in the database rather than calling sumBalance once per account: a console
     * listing every account would otherwise issue one query per row, getting slower
     * precisely as the thing it monitors grows.
     *
     * An account with no postings produces no group and is absent from the result. Callers
     * treat a missing entry as zero, which is what a new account's balance is.
     */
    @Query("""
            select new org.learning.mldsa.repositories.AccountBalance(
                p.account.accountId,
                coalesce(sum(
                    case when p.direction = org.learning.mldsa.models.PostingDirection.CREDIT
                         then p.amount
                         else -p.amount
                    end), 0))
            from Posting p
            group by p.account.accountId
            """)
    List<AccountBalance> sumBalancesByAccount();

    /**
     * Balances of just the given accounts — one page of a list rather than every account in
     * the system. As above, an account with no postings is absent and reads as zero.
     */
    @Query("""
            select new org.learning.mldsa.repositories.AccountBalance(
                p.account.accountId,
                coalesce(sum(
                    case when p.direction = org.learning.mldsa.models.PostingDirection.CREDIT
                         then p.amount
                         else -p.amount
                    end), 0))
            from Posting p
            where p.account.accountId in :accountIds
            group by p.account.accountId
            """)
    List<AccountBalance> sumBalancesOf(@Param("accountIds") Collection<Long> accountIds);

    /**
     * The combined balance of each given institution's accounts of one type: customer funds
     * held with CUSTOMER, the settlement position with SETTLEMENT.
     *
     * This is what lets the Central Bank supervise an institution without seeing its
     * customers — the sum crosses the identity boundary, the individual balances do not.
     * An institution with no postings of that type is absent, which callers read as zero.
     */
    @Query("""
            select new org.learning.mldsa.repositories.InstitutionBalance(
                p.account.institution.userId,
                coalesce(sum(
                    case when p.direction = org.learning.mldsa.models.PostingDirection.CREDIT
                         then p.amount
                         else -p.amount
                    end), 0))
            from Posting p
            where p.account.type = :type
              and p.account.institution.userId in :institutionIds
            group by p.account.institution.userId
            """)
    List<InstitutionBalance> sumBalancesPerInstitution(@Param("type") AccountType type,
                                                       @Param("institutionIds") Collection<Long> institutionIds);

    /**
     * Postings falling in a statement period, oldest first, as a statement reads.
     *
     * The period is half-open — from inclusive, to exclusive — so consecutive statements
     * abut exactly. A closed range on both ends would either double-count a posting landing
     * on a boundary or miss it, depending on how the end instant was rounded.
     */
    @Query("""
            select p from Posting p
            where p.account.accountId = :accountId
              and p.postedAt >= :from
              and p.postedAt < :toExclusive
            order by p.postedAt asc, p.postingId asc
            """)
    List<Posting> findForStatement(@Param("accountId") Long accountId,
                                   @Param("from") Instant from,
                                   @Param("toExclusive") Instant toExclusive);

    /**
     * The balance as it stood immediately before a statement period — its opening balance.
     *
     * Summing everything before the period, rather than storing a period-end figure, is
     * what lets any statement be regenerated at any time and still agree with every other:
     * opening balance plus the period's movements always equals the closing balance.
     */
    @Query("""
            select coalesce(sum(
                case when p.direction = org.learning.mldsa.models.PostingDirection.CREDIT
                     then p.amount
                     else -p.amount
                end), 0)
            from Posting p
            where p.account.accountId = :accountId
              and p.postedAt < :before
            """)
    BigDecimal sumBalanceBefore(@Param("accountId") Long accountId, @Param("before") Instant before);
}
