package com.copymylife.copymylife.repository;

import com.copymylife.copymylife.model.TodoTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface TodoTaskRepository extends JpaRepository<TodoTask, Long> {

    @Query("SELECT t FROM TodoTask t WHERE t.todoList.id IN :listIds ORDER BY t.position, t.id")
    List<TodoTask> findAllIn(@Param("listIds") Collection<Long> listIds);

    Optional<TodoTask> findByIdAndTodoListId(Long id, Long todoListId);

    long countByTodoListId(Long todoListId);

    @Query("SELECT COALESCE(MAX(t.position), -1) FROM TodoTask t WHERE t.todoList.id = :listId")
    int findMaxPosition(@Param("listId") Long listId);
}
