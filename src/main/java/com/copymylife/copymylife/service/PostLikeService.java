package com.copymylife.copymylife.service;

import com.copymylife.copymylife.dto.PostLikeResponse;
import com.copymylife.copymylife.model.Post;
import com.copymylife.copymylife.model.User;
import com.copymylife.copymylife.repository.PostLikeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// US-15: liking a post. Whoever may see a post may like it; for anyone else a private post
// answers exactly like a missing one (PostService.viewable). Both calls are safe to repeat.
@Service
public class PostLikeService {

    private final PostService postService;
    private final PostLikeRepository postLikeRepository;

    public PostLikeService(PostService postService, PostLikeRepository postLikeRepository) {
        this.postService = postService;
        this.postLikeRepository = postLikeRepository;
    }

    @Transactional
    public PostLikeResponse like(String email, Long postId) {
        User viewer = postService.requireUser(email);
        Post post = postService.viewable(viewer, postId);

        postLikeRepository.likeOnce(post.getId(), viewer.getId());

        return response(post, viewer);
    }

    @Transactional
    public PostLikeResponse unlike(String email, Long postId) {
        User viewer = postService.requireUser(email);
        Post post = postService.viewable(viewer, postId);

        postLikeRepository.unlike(post.getId(), viewer.getId());

        return response(post, viewer);
    }

    private PostLikeResponse response(Post post, User viewer) {
        return new PostLikeResponse(
                post.getId(),
                postLikeRepository.countByPostId(post.getId()),
                postLikeRepository.existsByPostIdAndUserId(post.getId(), viewer.getId())
        );
    }
}
