package com.kitdesk.repository;

import com.kitdesk.domain.User;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmailIgnoreCase(String email);

    /**
     * Loads the student and locks their row ({@code SELECT ... FOR UPDATE}) until the current
     * transaction ends. Every other transaction that locks the same student waits here, so one
     * student's bookings run one at a time. This is what keeps the category limit safe.
     * Must be called inside a transaction; the service always locks the student before the unit.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> lockStudent(@Param("id") Long id);
}
