package com.copymylife.copymylife.dto;

// A field left out keeps its current value.
public record TodoTaskRequest(String text, Boolean done) {
}
