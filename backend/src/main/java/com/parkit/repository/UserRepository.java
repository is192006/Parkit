package com.parkit.repository;

import com.parkit.domain.entity.User;
import com.parkit.domain.enums.UserRole;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Data access for {@link com.parkit.domain.entity.User}. Email lookups are case-insensitive (FR-08). */
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);

    boolean existsByRole(UserRole role);

    Page<User> findByRole(UserRole role, Pageable pageable);

    Optional<User> findByIdAndRole(Long id, UserRole role);

    /** Admin customer search by name, email or phone (case-insensitive substring). */
    @Query("""
            select u from User u
            where u.role = :role
              and (lower(u.name) like lower(concat('%', :q, '%'))
                   or lower(u.email) like lower(concat('%', :q, '%'))
                   or u.phone like concat('%', :q, '%'))
            """)
    Page<User> searchByRole(@Param("role") UserRole role, @Param("q") String q, Pageable pageable);
}
