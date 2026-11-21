package com.copymylife.copymylife.exception;

import com.copymylife.copymylife.controller.AdminCategoryController;
import com.copymylife.copymylife.controller.AdminPostController;
import com.copymylife.copymylife.controller.AdminProfileController;
import com.copymylife.copymylife.controller.AdminReportController;
import com.copymylife.copymylife.controller.AdminRoutineController;
import com.copymylife.copymylife.controller.CalendarEventController;
import com.copymylife.copymylife.controller.CategoryVoteController;
import com.copymylife.copymylife.controller.FriendshipController;
import com.copymylife.copymylife.controller.HabitController;
import com.copymylife.copymylife.controller.HabitLibraryController;
import com.copymylife.copymylife.controller.LifestyleMatchingController;
import com.copymylife.copymylife.controller.PostController;
import com.copymylife.copymylife.controller.RoutineCommentController;
import com.copymylife.copymylife.controller.RoutineAssistantController;
import com.copymylife.copymylife.controller.RoutineHabitController;
import com.copymylife.copymylife.controller.TodoListController;
import com.copymylife.copymylife.controller.UserController;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

// The habit, calendar, profile, to-do, post and comment pages show why a change was refused ("Only 5 minutes remaining in this routine").
// Limited to these controllers; their reasons never tell a private routine apart from a missing one.
// US-25: the admin content pages show their refusals too; only administrators reach them.
// US-24: so does the admin reports page ("This report is already closed").
// The routine assistant explains its refusals too; it only ever answers about the caller's own routines.
@RestControllerAdvice(assignableTypes = {
        AdminCategoryController.class,
        AdminPostController.class,
        AdminProfileController.class,
        AdminReportController.class,
        AdminRoutineController.class,
        CategoryVoteController.class,
        FriendshipController.class,
        CalendarEventController.class,
        HabitController.class,
        HabitLibraryController.class,
        LifestyleMatchingController.class,
        PostController.class,
        RoutineCommentController.class,
        RoutineAssistantController.class,
        RoutineHabitController.class,
        TodoListController.class,
        UserController.class
})
public class HabitApiExceptionHandler {

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<?> handleRefusal(ResponseStatusException exception) {
        String reason = exception.getReason();

        return ResponseEntity.status(exception.getStatusCode())
                .body(Map.of("message", reason == null ? "" : reason));
    }
}
