package com.braintwinx.repository;

import com.braintwinx.entity.Role;
import com.braintwinx.entity.User;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;

/**
 * Persistence for {@link User}.
 *
 * <p>Lookups are by {@code username} or {@code publicId} only. There is deliberately no
 * finder that accepts a password or hash: credential comparison belongs in the
 * authentication service against a fetched user, never in a query the database could log.
 */
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByUsername(String username);

    Optional<User> findByPublicId(String publicId);

    boolean existsByUsername(String username);

    boolean existsByEmail(String email);

    /**
     * Counts enabled users holding a role. Used to refuse the removal of the last
     * remaining administrator, which would otherwise lock the system out of its own
     * user management.
     */
    @Query("select count(u) from User u where u.role = :role and u.enabled = true")
    long countEnabledByRole(@Param("role") Role role);
}
