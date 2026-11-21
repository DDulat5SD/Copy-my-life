package com.copymylife.copymylife.controller;

import com.copymylife.copymylife.dto.TodoListRequest;
import com.copymylife.copymylife.dto.TodoListResponse;
import com.copymylife.copymylife.dto.TodoTaskRequest;
import com.copymylife.copymylife.service.TodoListService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

// Every call needs a signed-in user (SecurityConfig); the service decides what that user may see or change.
@RestController
public class TodoListController {

    private final TodoListService todoListService;

    public TodoListController(TodoListService todoListService) {
        this.todoListService = todoListService;
    }

    @GetMapping("/todo-lists")
    public ResponseEntity<List<TodoListResponse>> getMine(Authentication authentication) {
        return ResponseEntity.ok(todoListService.getMine(authentication.getName()));
    }

    @GetMapping("/users/{userId}/todo-lists")
    public ResponseEntity<List<TodoListResponse>> getPublicOf(@PathVariable Long userId, Authentication authentication) {
        return ResponseEntity.ok(todoListService.getPublicOf(authentication.getName(), userId));
    }

    @GetMapping("/todo-lists/{id}")
    public ResponseEntity<TodoListResponse> get(@PathVariable Long id, Authentication authentication) {
        return ResponseEntity.ok(todoListService.get(authentication.getName(), id));
    }

    @PostMapping("/todo-lists")
    public ResponseEntity<TodoListResponse> create(@RequestBody TodoListRequest request, Authentication authentication) {
        return ResponseEntity.ok(todoListService.create(authentication.getName(), request));
    }

    @PutMapping("/todo-lists/{id}")
    public ResponseEntity<TodoListResponse> update(@PathVariable Long id, @RequestBody TodoListRequest request, Authentication authentication) {
        return ResponseEntity.ok(todoListService.update(authentication.getName(), id, request));
    }

    @DeleteMapping("/todo-lists/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id, Authentication authentication) {
        todoListService.delete(authentication.getName(), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/todo-lists/{id}/tasks")
    public ResponseEntity<TodoListResponse> addTask(@PathVariable Long id, @RequestBody TodoTaskRequest request, Authentication authentication) {
        return ResponseEntity.ok(todoListService.addTask(authentication.getName(), id, request));
    }

    @PutMapping("/todo-lists/{id}/tasks/{taskId}")
    public ResponseEntity<TodoListResponse> updateTask(
            @PathVariable Long id,
            @PathVariable Long taskId,
            @RequestBody TodoTaskRequest request,
            Authentication authentication
    ) {
        return ResponseEntity.ok(todoListService.updateTask(authentication.getName(), id, taskId, request));
    }

    @DeleteMapping("/todo-lists/{id}/tasks/{taskId}")
    public ResponseEntity<TodoListResponse> deleteTask(@PathVariable Long id, @PathVariable Long taskId, Authentication authentication) {
        return ResponseEntity.ok(todoListService.deleteTask(authentication.getName(), id, taskId));
    }
}
