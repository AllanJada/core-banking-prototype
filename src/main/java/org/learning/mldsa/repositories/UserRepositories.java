package org.learning.mldsa.repositories;

import org.learning.mldsa.models.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepositories extends JpaRepository<User, Long> {
    boolean existsByName(String name);

    Optional<User> findByName(String name);

}
