package com.copymylife.copymylife.repository;

import com.copymylife.copymylife.model.FriendStatus;
import com.copymylife.copymylife.model.Friendship;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface FriendshipRepository extends JpaRepository<Friendship, Long> {

    // US-29: every row of the pair; there is one at most now, but rows from before the pair
    // index may still hold two.
    @Query("""
            SELECT f FROM Friendship f
            WHERE (f.sender.id = :user1Id AND f.receiver.id = :user2Id)
               OR (f.sender.id = :user2Id AND f.receiver.id = :user1Id)
            ORDER BY f.id
            """)
    List<Friendship> findAllBetweenUsers(
            @Param("user1Id") Long user1Id,
            @Param("user2Id") Long user2Id
    );

    List<Friendship> findByReceiverIdAndStatusOrderByCreatedAtDesc(
            Long receiverId,
            FriendStatus status
    );

    List<Friendship> findBySenderIdAndStatusOrderByCreatedAtDesc(
            Long senderId,
            FriendStatus status
    );

    @Query("""
            SELECT f FROM Friendship f
            WHERE f.status = :status
              AND (f.sender.id = :userId OR f.receiver.id = :userId)
            ORDER BY f.updatedAt DESC
            """)
    List<Friendship> findByUserIdAndStatus(
            @Param("userId") Long userId,
            @Param("status") FriendStatus status
    );

    Optional<Friendship> findByIdAndReceiverId(
            Long id,
            Long receiverId
    );

    Optional<Friendship> findByIdAndSenderId(
            Long id,
            Long senderId
    );

    // US-29: a request held while it is answered or called off, so two answers never both count.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT f FROM Friendship f WHERE f.id = :id AND f.receiver.id = :receiverId")
    Optional<Friendship> findByIdAndReceiverIdForUpdate(@Param("id") Long id, @Param("receiverId") Long receiverId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT f FROM Friendship f WHERE f.id = :id AND f.sender.id = :senderId")
    Optional<Friendship> findByIdAndSenderIdForUpdate(@Param("id") Long id, @Param("senderId") Long senderId);
}