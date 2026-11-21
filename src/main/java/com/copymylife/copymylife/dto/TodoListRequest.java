package com.copymylife.copymylife.dto;

// A field left out keeps its current value.
public record TodoListRequest(String title, Boolean isPublic) {
}
