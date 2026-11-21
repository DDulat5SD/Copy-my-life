package com.copymylife.copymylife.service;

import com.copymylife.copymylife.dto.TodoListRequest;
import com.copymylife.copymylife.dto.TodoListResponse;
import com.copymylife.copymylife.dto.TodoTaskRequest;
import com.copymylife.copymylife.model.TodoList;
import com.copymylife.copymylife.model.TodoTask;
import com.copymylife.copymylife.model.User;
import com.copymylife.copymylife.repository.TodoListRepository;
import com.copymylife.copymylife.repository.TodoTaskRepository;
import com.copymylife.copymylife.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// To-do lists: only the owner changes a list, its tasks or its visibility. Anyone else gets
// the same "not found" for a private list as for a missing one, so its existence never leaks.
@Service
public class TodoListService {

    static final int MAX_TITLE_LENGTH = 100;
    static final int MAX_TASK_LENGTH = 200;
    static final int MAX_TASKS = 100;

    private final TodoListRepository todoListRepository;
    private final TodoTaskRepository todoTaskRepository;
    private final UserRepository userRepository;
    private final UserService userService;

    public TodoListService(
            TodoListRepository todoListRepository,
            TodoTaskRepository todoTaskRepository,
            UserRepository userRepository,
            UserService userService
    ) {
        this.todoListRepository = todoListRepository;
        this.todoTaskRepository = todoTaskRepository;
        this.userRepository = userRepository;
        this.userService = userService;
    }

    @Transactional(readOnly = true)
    public List<TodoListResponse> getMine(String email) {
        User viewer = requireUser(email);
        return responses(todoListRepository.findAllOf(viewer.getId()), viewer);
    }

    // Another user's public lists; a banned or unknown user has none to show.
    @Transactional(readOnly = true)
    public List<TodoListResponse> getPublicOf(String email, Long userId) {
        User viewer = requireUser(email);

        if (!userService.isVisiblePublicUser(userId)) {
            throw notFound("User not found");
        }

        return responses(todoListRepository.findPublicOf(userId), viewer);
    }

    @Transactional(readOnly = true)
    public TodoListResponse get(String email, Long id) {
        User viewer = requireUser(email);

        TodoList list = todoListRepository.findById(id)
                .filter(found -> isOwner(found, viewer)
                        || (found.isPublic() && userService.isVisiblePublicUser(found.getUser().getId())))
                .orElseThrow(() -> notFound("To-do list not found"));

        return responses(List.of(list), viewer).get(0);
    }

    @Transactional
    public TodoListResponse create(String email, TodoListRequest request) {
        User owner = requireUser(email);

        TodoList list = new TodoList();
        list.setUser(owner);
        list.setTitle(requireTitle(request.title()));
        list.setPublic(Boolean.TRUE.equals(request.isPublic()));

        return responses(List.of(todoListRepository.saveAndFlush(list)), owner).get(0);
    }

    @Transactional
    public TodoListResponse update(String email, Long id, TodoListRequest request) {
        User owner = requireUser(email);
        TodoList list = owned(owner, id);

        if (request.title() != null) {
            list.setTitle(requireTitle(request.title()));
        }

        if (request.isPublic() != null) {
            list.setPublic(request.isPublic());
        }

        return responses(List.of(todoListRepository.saveAndFlush(list)), owner).get(0);
    }

    @Transactional
    public void delete(String email, Long id) {
        todoListRepository.delete(owned(requireUser(email), id));
    }

    @Transactional
    public TodoListResponse addTask(String email, Long id, TodoTaskRequest request) {
        User owner = requireUser(email);
        TodoList list = owned(owner, id);

        if (todoTaskRepository.countByTodoListId(id) >= MAX_TASKS) {
            throw invalid("A list can hold at most " + MAX_TASKS + " tasks");
        }

        TodoTask task = new TodoTask();
        task.setTodoList(list);
        task.setText(requireTaskText(request.text()));
        task.setPosition(todoTaskRepository.findMaxPosition(id) + 1);
        todoTaskRepository.saveAndFlush(task);

        return responses(List.of(list), owner).get(0);
    }

    @Transactional
    public TodoListResponse updateTask(String email, Long id, Long taskId, TodoTaskRequest request) {
        User owner = requireUser(email);
        TodoList list = owned(owner, id);
        TodoTask task = ownedTask(id, taskId);

        if (request.text() != null) {
            task.setText(requireTaskText(request.text()));
        }

        if (request.done() != null) {
            task.setDone(request.done());
        }

        todoTaskRepository.saveAndFlush(task);

        return responses(List.of(list), owner).get(0);
    }

    @Transactional
    public TodoListResponse deleteTask(String email, Long id, Long taskId) {
        User owner = requireUser(email);
        TodoList list = owned(owner, id);

        todoTaskRepository.delete(ownedTask(id, taskId));
        todoTaskRepository.flush();

        return responses(List.of(list), owner).get(0);
    }

    private List<TodoListResponse> responses(List<TodoList> lists, User viewer) {
        Map<Long, List<TodoListResponse.Task>> tasksByList = new HashMap<>();

        if (!lists.isEmpty()) {
            for (TodoTask task : todoTaskRepository.findAllIn(lists.stream().map(TodoList::getId).toList())) {
                tasksByList.computeIfAbsent(task.getTodoList().getId(), key -> new ArrayList<>())
                        .add(new TodoListResponse.Task(task.getId(), task.getText(), task.isDone()));
            }
        }

        return lists.stream()
                .map(list -> new TodoListResponse(
                        list.getId(),
                        list.getTitle(),
                        list.isPublic(),
                        isOwner(list, viewer),
                        list.getUser().getId(),
                        list.getUser().getName(),
                        list.getUser().getUsername(),
                        tasksByList.getOrDefault(list.getId(), List.of()),
                        list.getCreatedAt(),
                        list.getUpdatedAt()
                ))
                .toList();
    }

    private TodoList owned(User owner, Long id) {
        return todoListRepository.findByIdAndUserId(id, owner.getId())
                .orElseThrow(() -> notFound("To-do list not found"));
    }

    private TodoTask ownedTask(Long listId, Long taskId) {
        return todoTaskRepository.findByIdAndTodoListId(taskId, listId)
                .orElseThrow(() -> notFound("Task not found"));
    }

    private boolean isOwner(TodoList list, User viewer) {
        return list.getUser().getId().equals(viewer.getId());
    }

    private String requireTitle(String value) {
        String title = value == null ? "" : value.strip();

        if (title.isEmpty()) {
            throw invalid("Give the list a title");
        }

        if (title.length() > MAX_TITLE_LENGTH) {
            throw invalid("The title can be at most " + MAX_TITLE_LENGTH + " characters");
        }

        return title;
    }

    private String requireTaskText(String value) {
        String text = value == null ? "" : value.strip();

        if (text.isEmpty()) {
            throw invalid("Write what the task is");
        }

        if (text.length() > MAX_TASK_LENGTH) {
            throw invalid("A task can be at most " + MAX_TASK_LENGTH + " characters");
        }

        return text;
    }

    private User requireUser(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found"));
    }

    private static ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }

    private static ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
