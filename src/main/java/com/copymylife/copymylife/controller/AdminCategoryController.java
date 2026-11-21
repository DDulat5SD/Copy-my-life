package com.copymylife.copymylife.controller;

import com.copymylife.copymylife.model.Category;
import com.copymylife.copymylife.service.AdminContentService;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;

// US-25: the categories routines are filed under, with how many live routines each has.
@RestController
@RequestMapping("/admin-api/categories")
public class AdminCategoryController {

    private final AdminContentService adminContentService;

    public AdminCategoryController(AdminContentService adminContentService) {
        this.adminContentService = adminContentService;
    }

    public record AdminCategoryResponse(Long id, String name, int position, long routineCount) {}

    public record AdminCategoryRequest(String name) {}

    private AdminCategoryResponse toResponse(Category category) {
        return new AdminCategoryResponse(
                category.getId(),
                category.getName(),
                category.getPosition(),
                adminContentService.routineCount(category)
        );
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<AdminCategoryResponse> getCategories() {
        return adminContentService.getCategories().stream().map(this::toResponse).toList();
    }

    @PostMapping
    @Transactional
    public AdminCategoryResponse createCategory(@RequestBody(required = false) AdminCategoryRequest request) {
        return toResponse(adminContentService.createCategory(request == null ? null : request.name()));
    }

    @PutMapping("/{categoryId}")
    @Transactional
    public AdminCategoryResponse renameCategory(
            @PathVariable Long categoryId,
            @RequestBody(required = false) AdminCategoryRequest request
    ) {
        return toResponse(adminContentService.renameCategory(categoryId, request == null ? null : request.name()));
    }

    @DeleteMapping("/{categoryId}")
    public ResponseEntity<Void> removeCategory(@PathVariable Long categoryId) {
        adminContentService.removeCategory(categoryId);
        return ResponseEntity.noContent().build();
    }
}
