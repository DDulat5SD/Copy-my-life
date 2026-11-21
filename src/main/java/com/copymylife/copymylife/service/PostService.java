package com.copymylife.copymylife.service;

import com.copymylife.copymylife.dto.PostResponse;
import com.copymylife.copymylife.model.Post;
import com.copymylife.copymylife.model.User;
import com.copymylife.copymylife.repository.PostCommentRepository;
import com.copymylife.copymylife.repository.PostLikeRepository;
import com.copymylife.copymylife.repository.PostRepository;
import com.copymylife.copymylife.repository.SavedPostRepository;
import com.copymylife.copymylife.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

// Posts: a caption, a photo, or both. Photos go through the same checks as every other upload
// (FileUploadService); video is not supported. Only the owner changes a post or its visibility,
// and anyone else gets the same "not found" for a private post as for a missing one.
@Service
public class PostService {

    static final int MAX_TEXT_LENGTH = 2000;

    private final PostRepository postRepository;
    private final UserRepository userRepository;
    private final UserService userService;
    private final FileUploadService fileUploadService;
    private final PostLikeRepository postLikeRepository;
    private final PostCommentRepository postCommentRepository;
    private final SavedPostRepository savedPostRepository;

    public PostService(
            PostRepository postRepository,
            UserRepository userRepository,
            UserService userService,
            FileUploadService fileUploadService,
            PostLikeRepository postLikeRepository,
            PostCommentRepository postCommentRepository,
            SavedPostRepository savedPostRepository
    ) {
        this.postRepository = postRepository;
        this.userRepository = userRepository;
        this.userService = userService;
        this.fileUploadService = fileUploadService;
        this.postLikeRepository = postLikeRepository;
        this.postCommentRepository = postCommentRepository;
        this.savedPostRepository = savedPostRepository;
    }

    @Transactional(readOnly = true)
    public List<PostResponse> getMine(String email) {
        User viewer = requireUser(email);
        return postRepository.findAllOf(viewer.getId()).stream().map(post -> toResponse(post, viewer)).toList();
    }

    // Another user's public posts; a banned or unknown user has none to show.
    @Transactional(readOnly = true)
    public List<PostResponse> getPublicOf(String email, Long userId) {
        User viewer = requireUser(email);

        if (!userService.isVisiblePublicUser(userId)) {
            throw notFound("User not found");
        }

        return postRepository.findPublicOf(userId).stream().map(post -> toResponse(post, viewer)).toList();
    }

    @Transactional(readOnly = true)
    public PostResponse get(String email, Long id) {
        User viewer = requireUser(email);
        return toResponse(viewable(viewer, id), viewer);
    }

    // The photo goes to exactly those who may see the post. Photo files are never served
    // directly (WebConfig, SecurityConfig), so this is the only way to them.
    @Transactional(readOnly = true)
    public FileUploadService.StoredImage photo(String email, Long id) {
        Post post = viewable(requireUser(email), id);

        return fileUploadService.openPostImage(post.getImageUrl())
                .orElseThrow(() -> notFound("Photo not found"));
    }

    // Also the gate for likes, comments and bookmarks (PostLikeService, PostCommentService,
    // SavedPostService).
    Post viewable(User viewer, Long id) {
        return postRepository.findById(id)
                .filter(post -> canView(viewer, post))
                .orElseThrow(() -> notFound("Post not found"));
    }

    // Its owner, or anyone while it is public, not hidden by an administrator and its author is
    // neither banned nor hidden.
    boolean canView(User viewer, Post post) {
        return isOwner(post, viewer)
                || (post.isPublic() && !post.isHiddenByAdmin()
                        && userService.isVisiblePublicUser(post.getUser().getId()));
    }

    @Transactional
    public PostResponse create(String email, String text, boolean isPublic, MultipartFile image) {
        User owner = requireUser(email);
        String caption = caption(text);

        if (caption == null && isEmpty(image)) {
            throw invalid("Write something or add a photo");
        }

        Post post = new Post();
        post.setUser(owner);
        post.setText(caption);
        post.setPublic(isPublic);
        // Stored last, once nothing else can refuse the post.
        post.setImageUrl(isEmpty(image) ? null : fileUploadService.storePostImage(image));

        return toResponse(postRepository.saveAndFlush(post), owner);
    }

    @Transactional
    public PostResponse update(String email, Long id, String text, Boolean isPublic, MultipartFile image, boolean removeImage) {
        User owner = requireUser(email);
        Post post = owned(owner, id);
        // Text left out keeps the caption; an empty one clears it.
        String caption = text == null ? post.getText() : caption(text);
        boolean keepsImage = post.getImageUrl() != null && !removeImage;

        if (caption == null && isEmpty(image) && !keepsImage) {
            throw invalid("Write something or add a photo");
        }

        post.setText(caption);

        if (isPublic != null) {
            post.setPublic(isPublic);
        }

        if (!isEmpty(image)) {
            deleteImageAfterCommit(post.getImageUrl());
            post.setImageUrl(fileUploadService.storePostImage(image));
        } else if (removeImage) {
            deleteImageAfterCommit(post.getImageUrl());
            post.setImageUrl(null);
        }

        return toResponse(postRepository.saveAndFlush(post), owner);
    }

    @Transactional
    public void delete(String email, Long id) {
        remove(owned(requireUser(email), id));
    }

    // Also how an administrator removes a post (AdminContentService); likes and comments go with it.
    void remove(Post post) {
        deleteImageAfterCommit(post.getImageUrl());
        postRepository.delete(post);
    }

    // The file goes only once the change is saved, so a failed save never loses a photo.
    private void deleteImageAfterCommit(String url) {
        if (url == null) {
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                fileUploadService.deletePostImage(url);
            }
        });
    }

    PostResponse toResponse(Post post, User viewer) {
        User author = post.getUser();

        // Where the photo is stored never leaves the server; clients ask the post for it.
        return new PostResponse(
                post.getId(),
                post.getText(),
                post.getImageUrl() == null ? null : "/posts/" + post.getId() + "/image",
                post.isPublic(),
                isOwner(post, viewer),
                author.getId(),
                author.getName(),
                author.getUsername(),
                ImageUrlPolicy.isSafe(author.getAvatarUrl()) ? author.getAvatarUrl() : null,
                post.getCreatedAt(),
                post.getUpdatedAt(),
                postLikeRepository.countByPostId(post.getId()),
                postLikeRepository.existsByPostIdAndUserId(post.getId(), viewer.getId()),
                postCommentRepository.countShownOf(post.getId()),
                post.isHiddenByAdmin(),
                savedPostRepository.existsByUserIdAndPostId(viewer.getId(), post.getId())
        );
    }

    private Post owned(User owner, Long id) {
        return postRepository.findByIdAndUserId(id, owner.getId())
                .orElseThrow(() -> notFound("Post not found"));
    }

    private boolean isOwner(Post post, User viewer) {
        return post.getUser().getId().equals(viewer.getId());
    }

    // Null when there is no text worth keeping.
    static String caption(String value) {
        String text = value == null ? "" : value.strip();

        if (text.length() > MAX_TEXT_LENGTH) {
            throw invalid("A post can be at most " + MAX_TEXT_LENGTH + " characters");
        }

        return text.isEmpty() ? null : text;
    }

    private static boolean isEmpty(MultipartFile file) {
        return file == null || file.isEmpty();
    }

    User requireUser(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found"));
    }

    private static ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }

    private static ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
