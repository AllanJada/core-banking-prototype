package org.learning.mldsa.repositories;

import org.learning.mldsa.models.DebitCard;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DebitCardRepository extends JpaRepository<DebitCard, Long> {

    Optional<DebitCard> findByAccount_AccountId(Long accountId);

    boolean existsByCardNumber(String cardNumber);
}
