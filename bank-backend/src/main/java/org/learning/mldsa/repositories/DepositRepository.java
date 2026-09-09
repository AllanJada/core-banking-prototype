package org.learning.mldsa.repositories;

import org.learning.mldsa.models.Deposit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DepositRepository extends JpaRepository<Deposit, Long> {

    List<Deposit> findByAccount_AccountIdOrderByDepositedAtDescDepositIdDesc(Long accountId);
}
