package org.learning.mldsa.repositories;

import org.learning.mldsa.models.Account;
import org.learning.mldsa.models.AccountType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
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
