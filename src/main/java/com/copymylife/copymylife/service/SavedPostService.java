package com.copymylife.copymylife.service;

import com.copymylife.copymylife.dto.PostResponse;
import com.copymylife.copymylife.model.Post;
import com.copymylife.copymylife.model.SavedPost;
import com.copymylife.copymylife.model.User;
import com.copymylife.copymylife.repository.SavedPostRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

// US-16: bookmarking posts, as routines are bookmarked (SavedRoutineService). Only a post the user
// may see and that is public can be saved; someone else's private post answers like a missing one
// (PostService.viewable). Saved shows only what the user may still see, so a post made private,
// hidden by an administrator or whose author was banned drops out of it; saving again is safe.
@Service
public class SavedPostService {

    private final PostService postService;
    private final SavedPostRepository savedPostRepository;

    public SavedPostService(PostService postService, SavedPostRepository savedPostRepository) {
        this.postService = postService;
        this.savedPostRepository = savedPostRepository;
    }

    @Transactional
    public PostResponse save(String email, Long postId) {
        User viewer = postService.requireUser(email);
        Post post = postService.viewable(viewer, postId);

        if (!post.isPublic()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only public posts can be saved");
        }

        savedPostRepository.insertIfAbsent(viewer.getId(), post.getId(), Instant.now());

        return postService.toResponse(post, viewer);
    }

    @Transactional(readOnly = true)
    public List<PostResponse> list(String email) {
        User viewer = postService.requireUser(email);

        return savedPostRepository.findAllOf(viewer.getId()).stream()
                .map(SavedPost::getPost)
                .filter(post -> postService.canView(viewer, post))
                .map(post -> postService.toResponse(post, viewer))
                .toList();
    }

    // Removing works even when the post can no longer be seen, so nothing is left stuck.
    @Transactional
    public void remove(String email, Long postId) {
        User viewer = postService.requireUser(email);
        savedPostRepository.deletePair(viewer.getId(), postId);
    }
}
