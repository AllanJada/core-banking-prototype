package org.learning.mldsa.repositories;

import org.learning.mldsa.models.Role;
import org.learning.mldsa.models.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepositories extends JpaRepository<User, Long> {
    boolean existsByName(String name);

    /** Whether any account of this role exists — used to detect an unprovisioned system. */
    boolean existsByRole(Role role);

    Optional<User> findByName(String name);

}
