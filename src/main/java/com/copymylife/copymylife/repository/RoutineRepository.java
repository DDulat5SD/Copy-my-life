
package com.copymylife.copymylife.repository;

import com.copymylife.copymylife.model.Routine;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface RoutineRepository extends JpaRepository<Routine, Long> {

    List<Routine> findByUserIdAndDeletedFalse(Long userId);

    Optional<Routine> findByIdAndUserIdAndDeletedFalse(
            Long id,
            Long userId
    );

    // Explore's community list; like the search, it leaves out the routines of banned users.
    @Query("""
            SELECT r
            FROM Routine r
            JOIN r.user u
            WHERE r.isPublic = true
              AND r.deleted = false
              AND r.hiddenAt IS NULL
              AND (r.builtIn = false OR r.builtIn IS NULL)
              AND u.banned = false
            """)
    List<Routine> findPublicUserRoutines();

    @Query("""
            SELECT r
            FROM Routine r
            WHERE r.builtIn = true
              AND r.deleted = false
              AND r.hiddenAt IS NULL
            """)
    List<Routine> findByBuiltInTrue();

    // US-05: each public or built-in routine a search finds, once, by its title, description,
    // category or the habits in it, and a community routine also by its author's name, username,
    // profession or goals, all shown on a public profile. A habit counts by its title and by its
    // type: the category of the routine it was made in, when that routine may be seen.
    // :pattern comes from SearchText. Private and deleted routines, and those of banned users,
    // are never found.
    @Query("""
            SELECT DISTINCT r.id
            FROM Routine r
            JOIN r.user u
            WHERE r.deleted = false
              AND r.hiddenAt IS NULL
              AND (r.builtIn = true OR (r.isPublic = true AND u.banned = false))
              AND (:category = '' OR LOWER(TRIM(COALESCE(r.category, ''))) = :category)
              AND (
                  LOWER(COALESCE(r.title, '')) LIKE :pattern ESCAPE '\\'
                  OR LOWER(COALESCE(r.description, '')) LIKE :pattern ESCAPE '\\'
                  OR LOWER(COALESCE(r.category, '')) LIKE :pattern ESCAPE '\\'
                  OR ((r.builtIn = false OR r.builtIn IS NULL) AND (
                      LOWER(COALESCE(u.name, '')) LIKE :pattern ESCAPE '\\'
                      OR LOWER(COALESCE(u.username, '')) LIKE :pattern ESCAPE '\\'
                      OR LOWER(COALESCE(u.profession, '')) LIKE :pattern ESCAPE '\\'
                      OR LOWER(COALESCE(u.goals, '')) LIKE :pattern ESCAPE '\\'
                  ))
                  OR EXISTS (
                      SELECT 1
                      FROM RoutineHabit rh
                      JOIN rh.habit h
                      LEFT JOIN h.routine hr
                      WHERE rh.routine = r
                        AND rh.removedAt IS NULL
                        AND (
                            LOWER(COALESCE(h.title, '')) LIKE :pattern ESCAPE '\\'
                            OR (hr.deleted = false
                                AND hr.hiddenAt IS NULL
                                AND (hr.isPublic = true OR hr.builtIn = true)
                                AND LOWER(COALESCE(hr.category, '')) LIKE :pattern ESCAPE '\\')
                        )
                  )
              )
            ORDER BY r.id
            """)
    List<Long> searchVisibleRoutineIds(
            @Param("pattern") String pattern,
            @Param("category") String category
    );

    @Query("""
        SELECT CASE WHEN COUNT(r) > 0 THEN true ELSE false END
        FROM Routine r
        WHERE r.title = :title
          AND r.builtIn = true
        """)
    boolean existsByTitleAndBuiltInTrue(@Param("title") String title);

    @Query("""
            SELECT COUNT(r)
            FROM Routine r
            WHERE r.builtIn = true
              AND r.deleted = false
            """)
    long countBuiltInRoutines();

    @Query("""
            SELECT COUNT(r)
            FROM Routine r
            WHERE r.isPublic = true
              AND r.deleted = false
              AND r.hiddenAt IS NULL
              AND (r.builtIn = false OR r.builtIn IS NULL)
            """)
    long countPublicUserRoutines();

    @Query("""
            SELECT COUNT(DISTINCT r.user.id)
            FROM Routine r
            WHERE r.isPublic = true
              AND r.deleted = false
              AND r.hiddenAt IS NULL
              AND (r.builtIn = false OR r.builtIn IS NULL)
            """)
    long countPublicUserCreators();

    @Query("""
            SELECT COUNT(DISTINCT r.user.id)
            FROM Routine r
            WHERE r.isPublic = true
              AND r.deleted = false
              AND r.hiddenAt IS NULL
              AND r.user IS NOT NULL
              AND r.user.email <> 'explore@copymylife.local'
            """)
    long countPublicCreators();

    @Query("""
            SELECT COUNT(r)
            FROM Routine r
            WHERE r.isPublic = true
              AND r.deleted = false
              AND r.hiddenAt IS NULL
            """)
    long countPublicRoutines();

    @Query("""
            SELECT r
            FROM Routine r
            WHERE r.user.id = :userId
              AND r.isPublic = true
              AND r.deleted = false
              AND r.hiddenAt IS NULL
            ORDER BY r.id DESC
            """)
    List<Routine> findPublicRoutinesByUserId(
            @Param("userId") Long userId
    );

    @Query("""
            SELECT r
            FROM Routine r
            JOIN r.user u
            WHERE r.deleted = false
              AND r.hiddenAt IS NULL
              AND (r.builtIn = true OR (r.isPublic = true AND u.banned = false))
            """)
    List<Routine> findRankingRoutines();

    // US-25: how many live routines are filed under a category, whatever its case.
    @Query("""
            SELECT COUNT(r)
            FROM Routine r
            WHERE r.deleted = false
              AND LOWER(TRIM(COALESCE(r.category, ''))) = :nameKey
            """)
    long countByCategoryKey(@Param("nameKey") String nameKey);

    // US-25: renaming a category carries every routine filed under it, deleted ones too.
    @Modifying
    @Query("""
            UPDATE Routine r
            SET r.category = :name
            WHERE LOWER(TRIM(COALESCE(r.category, ''))) = :nameKey
            """)
    int renameCategory(@Param("nameKey") String nameKey, @Param("name") String name);

    List<Routine> findByDeletedFalse();

    // US-28: holds the routine's row, so its category votes and their count never interleave.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM Routine r WHERE r.id = :id")
    Optional<Routine> findByIdForUpdate(@Param("id") Long id);

    // US-26: the routines these members share themselves (not the built-in Explore catalogue).
    @Query("""
            SELECT r
            FROM Routine r
            WHERE r.user.id IN :userIds
              AND r.isPublic = true
              AND r.deleted = false
              AND r.hiddenAt IS NULL
              AND (r.builtIn = false OR r.builtIn IS NULL)
            ORDER BY r.id DESC
            """)
    List<Routine> findPublicRoutinesByUserIdIn(
            @Param("userIds") Collection<Long> userIds
    );

    // Routine assistant: routines anyone may see that can serve as examples: the built-in
    // catalogue and members' public routines, never private, deleted or hidden ones, nor those of
    // banned members or of profiles an administrator hid, nor the user's own.
    @Query("""
            SELECT r
            FROM Routine r
            JOIN r.user u
            WHERE r.deleted = false
              AND r.hiddenAt IS NULL
              AND u.id <> :userId
              AND (r.builtIn = true OR (r.isPublic = true AND u.banned = false AND u.profileHiddenAt IS NULL))
            ORDER BY r.ratingCount DESC, r.id DESC
            """)
    List<Routine> findAssistantExamples(@Param("userId") Long userId, Pageable page);

    // US-12: one row per published routine of a creator (public, not deleted, not hidden by an
    // administrator). Each number is its own subquery, so no join multiplies another: copies count
    // the routines saved as a copy of this one by other members who are not banned, deleted copies
    // included (they were made); likes and saves are counted as the routine page counts them.
    @Query("""
            SELECT r.id AS routineId,
                   r.title AS title,
                   r.averageRating AS averageRating,
                   r.ratingCount AS ratingCount,
                   (SELECT COUNT(c) FROM Routine c
                    WHERE c.copiedFrom = r AND c.user <> r.user AND c.user.banned = false) AS copiesCount,
                   (SELECT COUNT(l) FROM LikedRoutine l WHERE l.routine = r) AS likesCount,
                   (SELECT COUNT(s) FROM SavedRoutine s WHERE s.routine = r) AS savesCount
            FROM Routine r
            WHERE r.user.id = :userId
              AND r.isPublic = true
              AND r.deleted = false
              AND r.hiddenAt IS NULL
            """)
    List<CreatorRoutineStats> findCreatorRoutineStats(@Param("userId") Long userId);

    interface CreatorRoutineStats {
        Long getRoutineId();

        String getTitle();

        Double getAverageRating();

        Long getRatingCount();

        Long getCopiesCount();

        Long getLikesCount();

        Long getSavesCount();
    }
}
