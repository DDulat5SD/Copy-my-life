package com.copymylife.copymylife.service;

import com.copymylife.copymylife.dto.PostCommentResponse;
import com.copymylife.copymylife.model.Post;
import com.copymylife.copymylife.model.PostComment;
import com.copymylife.copymylife.model.User;
import com.copymylife.copymylife.repository.PostCommentRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

// US-15: comments under a post. Whoever may see a post may read and write its comments; for
// anyone else a private post answers exactly like a missing one (PostService.viewable).
// A comment is removed only by its author. Text is stored as typed; pages show it as text.
@Service
public class PostCommentService {

    static final int MAX_TEXT_LENGTH = 1000;

    private final PostService postService;
    private final PostCommentRepository postCommentRepository;

    public PostCommentService(PostService postService, PostCommentRepository postCommentRepository) {
        this.postService = postService;
        this.postCommentRepository = postCommentRepository;
    }

    @Transactional(readOnly = true)
    public List<PostCommentResponse> getComments(String email, Long postId) {
        User viewer = postService.requireUser(email);
        Post post = postService.viewable(viewer, postId);

        return postCommentRepository.findShownOf(post.getId()).stream()
                .map(comment -> toResponse(comment, viewer))
                .toList();
    }

    @Transactional
    public PostCommentResponse addComment(String email, Long postId, String text) {
        String value = text(text);
        User viewer = postService.requireUser(email);
        Post post = postService.viewable(viewer, postId);

        PostComment comment = new PostComment();
        comment.setPost(post);
        comment.setUser(viewer);
        comment.setText(value);

        return toResponse(postCommentRepository.saveAndFlush(comment), viewer);
    }

    @Transactional
    public void deleteComment(String email, Long postId, Long commentId) {
        User viewer = postService.requireUser(email);
        Post post = postService.viewable(viewer, postId);

        // Someone else's comment looks the same as one that is not there.
        if (postCommentRepository.deleteOwn(commentId, post.getId(), viewer.getId()) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Comment not found");
        }
    }

    static String text(String value) {
        String text = value == null ? "" : value.strip();

        if (text.isEmpty()) {
            throw invalid("Write a comment first");
        }

        if (text.length() > MAX_TEXT_LENGTH) {
            throw invalid("A comment can be at most " + MAX_TEXT_LENGTH + " characters");
        }

        return text;
    }

    private PostCommentResponse toResponse(PostComment comment, User viewer) {
        User author = comment.getUser();

        return new PostCommentResponse(
                comment.getId(),
                comment.getPost().getId(),
                comment.getText(),
                author.getId().equals(viewer.getId()),
                author.getId(),
                author.getName(),
                author.getUsername(),
                ImageUrlPolicy.isSafe(author.getAvatarUrl()) ? author.getAvatarUrl() : null,
                comment.getCreatedAt()
        );
    }

    private static ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
