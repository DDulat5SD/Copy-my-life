package com.copymylife.copymylife.repository;

import com.copymylife.copymylife.model.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    @Query("""
        SELECT CASE WHEN COUNT(u) > 0 THEN true ELSE false END
        FROM User u
        WHERE lower(u.email) = lower(:email)
        """)
    boolean existsByEmailIgnoreCase(@Param("email") String email);

    @Query("""
        SELECT CASE WHEN COUNT(u) > 0 THEN true ELSE false END
        FROM User u
        WHERE lower(u.email) = lower(:email)
          AND u.id <> :id
        """)
    boolean existsByEmailIgnoreCaseAndIdNot(@Param("email") String email, @Param("id") Long id);

    @Query("""
        SELECT u FROM User u
        WHERE lower(u.email) = lower(:email)
        """)
    List<User> findAllByEmailIgnoreCase(@Param("email") String email);

    Optional<User> findByEmail(String email);

    // Holds the user's row until the transaction ends, so one user's tracking changes run one at a time.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM User u WHERE u.email = :email")
    Optional<User> findByEmailForUpdate(@Param("email") String email);

    // An exact match wins so that accounts differing only by case, created before
    // the unique index existed, can each still sign in. Ambiguous input matches nobody.
    default Optional<User> findByLoginEmail(String email) {
        if (email == null) {
            return Optional.empty();
        }

        List<User> matches = findAllByEmailIgnoreCase(email);
        List<User> exact = matches.stream()
                .filter(user -> user.getEmail().equals(email))
                .toList();
        List<User> candidates = exact.isEmpty() ? matches : exact;

        return candidates.size() == 1 ? Optional.of(candidates.get(0)) : Optional.empty();
    }

    long countByEmailNot(String email);

    @Query("""
        SELECT CASE WHEN COUNT(u) > 0 THEN true ELSE false END
        FROM User u
        WHERE lower(u.username) = lower(:username)
          AND u.id <> :id
        """)
    boolean existsByUsernameIgnoreCaseAndIdNot(@Param("username") String username, @Param("id") Long id);

    // US-05: people by name, username, profession or goals, all shown on a public profile.
    // :pattern comes from SearchText, so % and _ are matched as themselves.
    @Query("""
        SELECT u FROM User u
        WHERE u.role = 'USER'
          AND u.banned = false
          AND u.profileHiddenAt IS NULL
          AND (
              LOWER(COALESCE(u.name, '')) LIKE :pattern ESCAPE '\\'
              OR LOWER(COALESCE(u.username, '')) LIKE :pattern ESCAPE '\\'
              OR LOWER(COALESCE(u.profession, '')) LIKE :pattern ESCAPE '\\'
              OR LOWER(COALESCE(u.goals, '')) LIKE :pattern ESCAPE '\\'
          )
        ORDER BY u.name ASC, u.id ASC
        """)
    List<User> searchPeople(
            @Param("pattern") String pattern,
            Pageable pageable
    );

    // US-26: the members AI matching may suggest: anyone whose profile others may see, who has
    // written something about themself or shares a routine of their own; the newest first. The
    // account that holds the built-in Explore routines is not a member.
    @Query("""
        SELECT u FROM User u
        WHERE u.role = 'USER'
          AND u.banned = false
          AND u.profileHiddenAt IS NULL
          AND u.id <> :userId
          AND u.email <> :systemEmail
          AND (
              TRIM(COALESCE(u.interests, '')) <> ''
              OR TRIM(COALESCE(u.goals, '')) <> ''
              OR TRIM(COALESCE(u.bio, '')) <> ''
              OR TRIM(COALESCE(u.profession, '')) <> ''
              OR EXISTS (
                  SELECT r.id FROM Routine r
                  WHERE r.user = u AND r.isPublic = true AND r.deleted = false AND r.hiddenAt IS NULL
                    AND (r.builtIn = false OR r.builtIn IS NULL)
              )
          )
        ORDER BY u.id DESC
        """)
    List<User> findLifestyleMatchCandidates(
            @Param("userId") Long userId,
            @Param("systemEmail") String systemEmail,
            Pageable pageable
    );
}