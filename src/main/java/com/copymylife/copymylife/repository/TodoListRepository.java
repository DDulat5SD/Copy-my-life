package com.copymylife.copymylife.repository;

import com.copymylife.copymylife.model.TodoList;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TodoListRepository extends JpaRepository<TodoList, Long> {

    @Query("SELECT l FROM TodoList l WHERE l.user.id = :userId ORDER BY l.createdAt DESC, l.id DESC")
    List<TodoList> findAllOf(@Param("userId") Long userId);

    @Query("SELECT l FROM TodoList l WHERE l.user.id = :userId AND l.isPublic = true ORDER BY l.createdAt DESC, l.id DESC")
    List<TodoList> findPublicOf(@Param("userId") Long userId);

    Optional<TodoList> findByIdAndUserId(Long id, Long userId);
}
