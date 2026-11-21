package com.copymylife.copymylife.service;

import com.copymylife.copymylife.model.Category;
import com.copymylife.copymylife.model.Post;
import com.copymylife.copymylife.model.Routine;
import com.copymylife.copymylife.model.User;
import com.copymylife.copymylife.repository.CategoryRepository;
import com.copymylife.copymylife.repository.CategoryVoteRepository;
import com.copymylife.copymylife.repository.PostCommentRepository;
import com.copymylife.copymylife.repository.PostRepository;
import com.copymylife.copymylife.repository.RoutineRepository;
import com.copymylife.copymylife.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * US-25: what an administrator changes in public content. Edits go through the same rules as
 * the owners' own forms. Hiding sets a mark the owner cannot clear: the content leaves every
 * public view (RoutineAccessService, UserService.isVisiblePublicUser, the public queries) and
 * stays with its owner until an administrator shows it again. Administrator accounts are not
 * changed here, as with bans. Every call is reached only through /admin-api (SecurityConfig).
 */
@Service
public class AdminContentService {

    static final int MAX_CATEGORY_LENGTH = 40;

    private final RoutineRepository routineRepository;
    private final PostRepository postRepository;
    private final PostService postService;
    private final PostCommentRepository postCommentRepository;
    private final UserRepository userRepository;
    private final CategoryRepository categoryRepository;
    private final CategoryVoteRepository categoryVoteRepository;
    private final JdbcTemplate jdbcTemplate;

    public AdminContentService(
            RoutineRepository routineRepository,
            PostRepository postRepository,
            PostService postService,
            PostCommentRepository postCommentRepository,
            UserRepository userRepository,
            CategoryRepository categoryRepository,
            CategoryVoteRepository categoryVoteRepository,
            JdbcTemplate jdbcTemplate
    ) {
        this.routineRepository = routineRepository;
        this.postRepository = postRepository;
        this.postService = postService;
        this.postCommentRepository = postCommentRepository;
        this.userRepository = userRepository;
        this.categoryRepository = categoryRepository;
        this.categoryVoteRepository = categoryVoteRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    // ---- routines ----

    // Title, description and category; the length and the activities stay the owner's.
    @Transactional
    public Routine editRoutine(Long routineId, String title, String description, String category) {
        Routine routine = liveRoutine(routineId);

        RoutineRules.validate(title, description, category, routine.getDurationMinutes());

        String chosen = category.trim();
        boolean offered = categoryRepository.findByNameKey(key(chosen)).isPresent();
        // A routine may keep a category the list no longer offers, such as Halloween.
        boolean kept = routine.getCategory() != null && key(routine.getCategory()).equals(key(chosen));

        if (!offered && !kept) {
            throw new IllegalArgumentException("Choose a category from the list");
        }

        routine.setTitle(title.trim());
        routine.setDescription(description);
        routine.setCategory(chosen);

        return routineRepository.saveAndFlush(routine);
    }

    // Hiding twice keeps the first time.
    @Transactional
    public Routine setRoutineHidden(Long routineId, boolean hidden) {
        Routine routine = liveRoutine(routineId);

        if (hidden != routine.isHiddenByAdmin()) {
            routine.setHiddenAt(hidden ? Instant.now() : null);
        }

        return routineRepository.saveAndFlush(routine);
    }

    private Routine liveRoutine(Long routineId) {
        return routineRepository.findById(routineId)
                .filter(routine -> !routine.isDeleted())
                .orElseThrow(() -> notFound("Routine not found"));
    }

    // ---- posts ----

    @Transactional(readOnly = true)
    public List<Post> getPosts() {
        return postRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id")));
    }

    // The caption only; a post that has no photo still needs some text.
    @Transactional
    public Post editPost(Long postId, String text) {
        Post post = post(postId);
        String caption = PostService.caption(text);

        if (caption == null && post.getImageUrl() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Write something or add a photo");
        }

        post.setText(caption);

        return postRepository.saveAndFlush(post);
    }

    @Transactional
    public Post setPostHidden(Long postId, boolean hidden) {
        Post post = post(postId);

        if (hidden != post.isHiddenByAdmin()) {
            post.setHiddenAt(hidden ? Instant.now() : null);
        }

        return postRepository.saveAndFlush(post);
    }

    @Transactional
    public void removePost(Long postId) {
        postService.remove(post(postId));
    }

    // US-24: a comment under a post goes as its author would remove it.
    @Transactional
    public void removePostComment(Long commentId) {
        postCommentRepository.delete(postCommentRepository.findById(commentId)
                .orElseThrow(() -> notFound("Comment not found")));
    }

    private Post post(Long postId) {
        return postRepository.findById(postId).orElseThrow(() -> notFound("Post not found"));
    }

    // ---- public profiles ----

    // A field left out keeps its value; an empty one clears an optional field.
    @Transactional
    public void editProfile(Long userId, ProfileEdit edit) {
        User user = member(userId);
        ProfileEdit given = edit == null ? new ProfileEdit(null, null, null, null, null, null) : edit;

        String name = given.name() == null ? user.getName() : ProfileRules.name(given.name());
        String username = given.username() == null ? user.getUsername() : ProfileRules.username(given.username());
        String profession = given.profession() == null ? user.getProfession()
                : ProfileRules.text(given.profession(), ProfileRules.MAX_PROFESSION_LENGTH, "Profession");
        String bio = given.bio() == null ? user.getBio()
                : ProfileRules.text(given.bio(), ProfileRules.MAX_TEXT_LENGTH, "Bio");
        String interests = given.interests() == null ? user.getInterests()
                : ProfileRules.text(given.interests(), ProfileRules.MAX_TEXT_LENGTH, "Interests");
        String goals = given.goals() == null ? user.getGoals()
                : ProfileRules.text(given.goals(), ProfileRules.MAX_TEXT_LENGTH, "Goals");

        if (username != null && !username.equals(user.getUsername())) {
            ProfileRules.requireValidUsername(username);

            // As in UserService.updateProfile: two claims on one name wait for each other.
            jdbcTemplate.query("SELECT pg_advisory_xact_lock(hashtext(?))", resultSet -> null,
                    "username:" + username.toLowerCase(Locale.ROOT));

            if (userRepository.existsByUsernameIgnoreCaseAndIdNot(username, user.getId())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "That username is already taken");
            }
        }

        user.setName(name);
        user.setUsername(username);
        user.setProfession(profession);
        user.setBio(bio);
        user.setInterests(interests);
        user.setGoals(goals);
        userRepository.saveAndFlush(user);
    }

    @Transactional
    public void setProfileHidden(Long userId, boolean hidden) {
        User user = member(userId);

        if (hidden != user.isProfileHiddenByAdmin()) {
            user.setProfileHiddenAt(hidden ? Instant.now() : null);
        }

        userRepository.saveAndFlush(user);
    }

    // Removes what the public profile shows about the person; the name, username and account stay.
    @Transactional
    public void removeProfileDetails(Long userId) {
        User user = member(userId);

        user.setProfession(null);
        user.setBio(null);
        user.setInterests(null);
        user.setGoals(null);
        user.setAvatarUrl(null);
        userRepository.saveAndFlush(user);
    }

    public record ProfileEdit(String name, String username, String profession, String bio, String interests, String goals) {
    }

    private User member(Long userId) {
        User user = userRepository.findById(userId).orElseThrow(() -> notFound("User not found"));

        if (!"USER".equals(user.getRole())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Administrator accounts can't be changed here");
        }

        return user;
    }

    // ---- categories ----

    @Transactional(readOnly = true)
    public List<Category> getCategories() {
        return categoryRepository.findAllByOrderByPositionAscIdAsc();
    }

    @Transactional(readOnly = true)
    public long routineCount(Category category) {
        return routineRepository.countByCategoryKey(category.getNameKey());
    }

    // New categories go at the end of the list.
    @Transactional
    public Category createCategory(String name) {
        String chosen = categoryName(name);

        requireFreeName(chosen, null);

        Category category = new Category();
        category.setName(chosen);
        category.setNameKey(key(chosen));
        category.setPosition(categoryRepository.findMaxPosition() + 1);

        return saveCategory(category);
    }

    // The routines filed under the old name move with it.
    @Transactional
    public Category renameCategory(Long categoryId, String name) {
        Category category = category(categoryId);
        String chosen = categoryName(name);

        requireFreeName(chosen, category.getId());

        routineRepository.renameCategory(category.getNameKey(), chosen);
        category.setName(chosen);
        category.setNameKey(key(chosen));

        return saveCategory(category);
    }

    // Only an empty category goes, so no routine is left filed under a name the list lacks.
    @Transactional
    public void removeCategory(Long categoryId) {
        Category category = category(categoryId);
        long used = routineRepository.countByCategoryKey(category.getNameKey());

        if (used > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    used + (used == 1 ? " routine is" : " routines are") + " filed under \"" + category.getName()
                            + "\". Move " + (used == 1 ? "it" : "them") + " to another category first.");
        }

        // US-28: community votes refer to their category, and they stay as they were cast.
        long votes = categoryVoteRepository.countByCategoryId(category.getId());

        if (votes > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    votes + (votes == 1 ? " community vote chose" : " community votes chose") + " \"" + category.getName()
                            + "\", so it stays on the list.");
        }

        categoryRepository.delete(category);
    }

    private Category category(Long categoryId) {
        return categoryRepository.findById(categoryId).orElseThrow(() -> notFound("Category not found"));
    }

    private void requireFreeName(String name, Long ownId) {
        categoryRepository.findByNameKey(key(name))
                .filter(found -> !found.getId().equals(ownId))
                .ifPresent(found -> {
                    throw taken();
                });
    }

    // The unique name key settles two administrators adding the same name at once.
    private Category saveCategory(Category category) {
        try {
            return categoryRepository.saveAndFlush(category);
        } catch (DataIntegrityViolationException exception) {
            throw taken();
        }
    }

    private static String categoryName(String value) {
        String name = value == null ? "" : value.trim().replaceAll("\\s+", " ");

        if (name.isEmpty()) {
            throw new IllegalArgumentException("Enter a category name");
        }

        if (name.length() > MAX_CATEGORY_LENGTH) {
            throw new IllegalArgumentException("A category name can be at most " + MAX_CATEGORY_LENGTH + " characters");
        }

        if (key(name).equals("all")) {
            throw new IllegalArgumentException("\"All\" already means every category on Explore, so it can't be a category name");
        }

        return name;
    }

    private static String key(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }

    private static ResponseStatusException taken() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "A category with that name already exists");
    }

    private static ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }
}
