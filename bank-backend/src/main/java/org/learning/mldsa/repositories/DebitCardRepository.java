package org.learning.mldsa.repositories;

import org.learning.mldsa.models.DebitCard;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface DebitCardRepository extends JpaRepository<DebitCard, Long> {

    Optional<DebitCard> findByAccount_AccountId(Long accountId);

    List<DebitCard> findByAccount_AccountIdIn(Collection<Long> accountIds);

    boolean existsByCardNumber(String cardNumber);
}
