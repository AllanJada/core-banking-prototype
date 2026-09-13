package org.learning.mldsa.repositories;

import jakarta.persistence.LockModeType;
import org.learning.mldsa.models.Account;
import org.learning.mldsa.models.AccountType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, Long> {

    Optional<Account> findByOwner_UserId(Long ownerId);

    Optional<Account> findByOwner_UserIdAndType(Long ownerId, AccountType type);

    Optional<Account> findByAccountNumber(String accountNumber);

    boolean existsByAccountNumber(String accountNumber);

    long countByType(AccountType type);

    List<Account> findByType(AccountType type);

    long countByInstitution_UserIdAndType(Long institutionId, AccountType type);

    /**
     * One customer's account, found only if it is held at the given institution.
     *
     * The institution is part of the lookup rather than checked after loading, so a customer
     * of another bank comes back exactly as absent as an id that was never issued — there is
     * no second response a caller could use to learn that the id exists elsewhere.
     */
    Optional<Account> findByOwner_UserIdAndInstitution_UserIdAndType(Long ownerId, Long institutionId,
                                                                     AccountType type);

    /** A page of one institution's accounts of a type, newest first, with their owners loaded. */
    @EntityGraph(attributePaths = "owner")
    Page<Account> findByInstitution_UserIdAndTypeOrderByOpenedAtDescAccountIdDesc(
            Long institutionId, AccountType type, Pageable pageable);

    List<Account> findByInstitution_UserIdInAndType(Collection<Long> institutionIds, AccountType type);

    /**
     * An institution's settlement account, locked for the rest of the transaction.
     *
     * Taken before an inter-bank payment reads the position it is about to check against the
     * net debit cap, so two payments leaving the same institution at once cannot both be told
     * there is room for one of them. Only the debited side is ever locked: a credited position
     * only rises, so there is nothing to race for, and locking exactly one row means two
     * institutions paying each other at the same moment cannot deadlock.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select a from Account a
            where a.institution.userId = :institutionId
              and a.type = org.learning.mldsa.models.AccountType.SETTLEMENT
            """)
    Optional<Account> findSettlementForUpdate(@Param("institutionId") Long institutionId);

    /**
     * How many accounts of a type each of the given institutions holds, in one grouped query.
     * An institution with none is absent from the result, which callers read as zero.
     */
    @Query("""
            select new org.learning.mldsa.repositories.InstitutionCount(a.institution.userId, count(a))
            from Account a
            where a.type = :type
              and a.institution.userId in :institutionIds
            group by a.institution.userId
            """)
    List<InstitutionCount> countPerInstitution(@Param("type") AccountType type,
                                               @Param("institutionIds") Collection<Long> institutionIds);
}
