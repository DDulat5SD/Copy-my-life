package com.copymylife.copymylife.controller;

import com.copymylife.copymylife.service.AdminContentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

// US-25: the categories Explore and the routine forms offer, in the administrators' order.
// Open to visitors (SecurityConfig).
@RestController
public class CategoryController {

    private final AdminContentService adminContentService;

    public CategoryController(AdminContentService adminContentService) {
        this.adminContentService = adminContentService;
    }

    public record CategoryResponse(Long id, String name) {}

    @GetMapping("/categories")
    public ResponseEntity<List<CategoryResponse>> getCategories() {
        return ResponseEntity.ok(adminContentService.getCategories().stream()
                .map(category -> new CategoryResponse(category.getId(), category.getName()))
                .toList());
    }
}
