package com.copymylife.copymylife.controller;

import com.copymylife.copymylife.dto.CommentRequest;
import com.copymylife.copymylife.dto.PostCommentResponse;
import com.copymylife.copymylife.dto.PostLikeResponse;
import com.copymylife.copymylife.dto.PostResponse;
import com.copymylife.copymylife.service.FileUploadService;
import com.copymylife.copymylife.service.PostCommentService;
import com.copymylife.copymylife.service.PostLikeService;
import com.copymylife.copymylife.service.PostService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

// Every call needs a signed-in user (SecurityConfig); the service decides what that user may see or change.
@RestController
public class PostController {

    private final PostService postService;
    private final PostLikeService postLikeService;
    private final PostCommentService postCommentService;

    public PostController(PostService postService, PostLikeService postLikeService, PostCommentService postCommentService) {
        this.postService = postService;
        this.postLikeService = postLikeService;
        this.postCommentService = postCommentService;
    }

    @GetMapping("/posts")
    public ResponseEntity<List<PostResponse>> getMine(Authentication authentication) {
        return ResponseEntity.ok(postService.getMine(authentication.getName()));
    }

    @GetMapping("/users/{userId}/posts")
    public ResponseEntity<List<PostResponse>> getPublicOf(@PathVariable Long userId, Authentication authentication) {
        return ResponseEntity.ok(postService.getPublicOf(authentication.getName(), userId));
    }

    @GetMapping("/posts/{id}")
    public ResponseEntity<PostResponse> get(@PathVariable Long id, Authentication authentication) {
        return ResponseEntity.ok(postService.get(authentication.getName(), id));
    }

    // Kept out of shared caches, and never sniffed into anything but the image type it was checked as.
    @GetMapping("/posts/{id}/image")
    public ResponseEntity<Resource> image(@PathVariable Long id, Authentication authentication) {
        FileUploadService.StoredImage photo = postService.photo(authentication.getName(), id);

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(photo.mediaType()))
                .cacheControl(CacheControl.noStore().cachePrivate())
                .header("X-Content-Type-Options", "nosniff")
                .body(new FileSystemResource(photo.file()));
    }

    // New posts are private unless asked otherwise.
    @PostMapping(value = "/posts", consumes = "multipart/form-data")
    public ResponseEntity<PostResponse> create(
            @RequestParam(required = false) String text,
            @RequestParam(defaultValue = "false") boolean isPublic,
            @RequestParam(required = false) MultipartFile image,
            Authentication authentication
    ) {
        return ResponseEntity.ok(postService.create(authentication.getName(), text, isPublic, image));
    }

    @PutMapping(value = "/posts/{id}", consumes = "multipart/form-data")
    public ResponseEntity<PostResponse> update(
            @PathVariable Long id,
            @RequestParam(required = false) String text,
            @RequestParam(required = false) Boolean isPublic,
            @RequestParam(required = false) MultipartFile image,
            @RequestParam(defaultValue = "false") boolean removeImage,
            Authentication authentication
    ) {
        return ResponseEntity.ok(postService.update(authentication.getName(), id, text, isPublic, image, removeImage));
    }

    @DeleteMapping("/posts/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id, Authentication authentication) {
        postService.delete(authentication.getName(), id);
        return ResponseEntity.noContent().build();
    }

    // US-15: likes and comments. Liking twice or unliking twice changes nothing more.
    @PostMapping("/posts/{id}/like")
    public ResponseEntity<PostLikeResponse> like(@PathVariable Long id, Authentication authentication) {
        return ResponseEntity.ok(postLikeService.like(authentication.getName(), id));
    }

    @DeleteMapping("/posts/{id}/like")
    public ResponseEntity<PostLikeResponse> unlike(@PathVariable Long id, Authentication authentication) {
        return ResponseEntity.ok(postLikeService.unlike(authentication.getName(), id));
    }

    @GetMapping("/posts/{id}/comments")
    public ResponseEntity<List<PostCommentResponse>> comments(@PathVariable Long id, Authentication authentication) {
        return ResponseEntity.ok(postCommentService.getComments(authentication.getName(), id));
    }

    @PostMapping("/posts/{id}/comments")
    public ResponseEntity<PostCommentResponse> addComment(
            @PathVariable Long id,
            @RequestBody(required = false) CommentRequest request,
            Authentication authentication
    ) {
        String text = request == null ? null : request.getText();
        return ResponseEntity.ok(postCommentService.addComment(authentication.getName(), id, text));
    }

    @DeleteMapping("/posts/{id}/comments/{commentId}")
    public ResponseEntity<Void> deleteComment(
            @PathVariable Long id,
            @PathVariable Long commentId,
            Authentication authentication
    ) {
        postCommentService.deleteComment(authentication.getName(), id, commentId);
        return ResponseEntity.noContent().build();
    }
}
