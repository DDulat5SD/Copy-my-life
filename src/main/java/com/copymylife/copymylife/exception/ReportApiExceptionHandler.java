package com.copymylife.copymylife.exception;

import com.copymylife.copymylife.controller.UserReportController;
import com.copymylife.copymylife.service.ReportService;
import org.springframework.beans.TypeMismatchException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

// US-24: the Report dialog shows why a report was refused, and tells "already reported" (409,
// code ALREADY_REPORTED) apart from the rest by its code. Missing and private content give the
// same "not found" (ReportService); nothing about the server's insides is ever sent. Asked before
// GlobalExceptionHandler, whose IllegalArgumentException answer would echo a bad id.
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = UserReportController.class)
public class ReportApiExceptionHandler {

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<?> handleRefusal(ResponseStatusException exception) {
        String code = exception instanceof ReportService.ReportRefusedException refusal
                ? refusal.getCode()
                : exception.getStatusCode().value() == HttpStatus.NOT_FOUND.value() ? "NOT_FOUND" : "REFUSED";
        String reason = exception.getReason();

        return ResponseEntity.status(exception.getStatusCode())
                .body(Map.of("message", reason == null ? "" : reason, "code", code));
    }

    // No body, or a body that is not JSON: there is no reason to read.
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<?> handleUnreadable(HttpMessageNotReadableException exception) {
        return ResponseEntity.badRequest()
                .body(Map.of("message", ReportService.INVALID_REASON, "code", "INVALID_REASON"));
    }

    // An id that is not a number names no content.
    @ExceptionHandler(TypeMismatchException.class)
    public ResponseEntity<?> handleBadId(TypeMismatchException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("message", "Content not found", "code", "NOT_FOUND"));
    }
}
