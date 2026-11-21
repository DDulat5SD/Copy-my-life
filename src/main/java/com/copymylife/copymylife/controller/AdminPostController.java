package com.copymylife.copymylife.controller;

import com.copymylife.copymylife.model.Post;
import com.copymylife.copymylife.model.User;
import com.copymylife.copymylife.repository.PostCommentRepository;
import com.copymylife.copymylife.repository.PostLikeRepository;
import com.copymylife.copymylife.service.AdminContentService;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

// US-25: every post, public or not, for administrators to edit, hide, show again or remove.
@RestController
@RequestMapping("/admin-api/posts")
public class AdminPostController {

    private final AdminContentService adminContentService;
    private final PostLikeRepository postLikeRepository;
    private final PostCommentRepository postCommentRepository;

    public AdminPostController(
            AdminContentService adminContentService,
            PostLikeRepository postLikeRepository,
            PostCommentRepository postCommentRepository
    ) {
        this.adminContentService = adminContentService;
        this.postLikeRepository = postLikeRepository;
        this.postCommentRepository = postCommentRepository;
    }

    public record AdminPostResponse(
            Long id,
            String text,
            boolean hasPhoto,
            boolean publicPost,
            boolean hidden,
            Long authorId,
            String authorName,
            String authorUsername,
            String authorEmail,
            Instant createdAt,
            long likeCount,
            long commentCount
    ) {}

    public record AdminPostEditRequest(String text) {}

    private AdminPostResponse toResponse(Post post) {
        User author = post.getUser();

        return new AdminPostResponse(
                post.getId(),
                post.getText(),
                post.getImageUrl() != null,
                post.isPublic(),
                post.isHiddenByAdmin(),
                author.getId(),
                author.getName(),
                author.getUsername(),
                author.getEmail(),
                post.getCreatedAt(),
                postLikeRepository.countByPostId(post.getId()),
                postCommentRepository.countShownOf(post.getId())
        );
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<AdminPostResponse> getPosts() {
        return adminContentService.getPosts().stream().map(this::toResponse).toList();
    }

    @PutMapping("/{postId}")
    @Transactional
    public AdminPostResponse editPost(
            @PathVariable Long postId,
            @RequestBody(required = false) AdminPostEditRequest request
    ) {
        return toResponse(adminContentService.editPost(postId, request == null ? null : request.text()));
    }

    @PutMapping("/{postId}/hide")
    @Transactional
    public AdminPostResponse hidePost(@PathVariable Long postId) {
        return toResponse(adminContentService.setPostHidden(postId, true));
    }

    @PutMapping("/{postId}/unhide")
    @Transactional
    public AdminPostResponse unhidePost(@PathVariable Long postId) {
        return toResponse(adminContentService.setPostHidden(postId, false));
    }

    @DeleteMapping("/{postId}")
    public ResponseEntity<Void> removePost(@PathVariable Long postId) {
        adminContentService.removePost(postId);
        return ResponseEntity.noContent().build();
    }
}
