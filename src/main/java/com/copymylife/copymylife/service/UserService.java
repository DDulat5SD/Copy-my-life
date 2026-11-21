package com.copymylife.copymylife.service;

import com.copymylife.copymylife.dto.LoginRequest;
import com.copymylife.copymylife.dto.UserResponse;
import com.copymylife.copymylife.dto.UserUpdateRequest;
import com.copymylife.copymylife.model.User;
import com.copymylife.copymylife.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import com.copymylife.copymylife.dto.ProfileResponse;
import com.copymylife.copymylife.dto.ProfileUpdateRequest;
import com.copymylife.copymylife.dto.UserSearchResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
public class UserService {
    private static final String EMAIL_UNIQUE_INDEX = "users_email_lower_uq";
    // US-05: a people search lists at most this many, in name order.
    private static final int MAX_PEOPLE_FOUND = 50;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JdbcTemplate jdbcTemplate;

    public UserService (UserRepository userRepository, PasswordEncoder passwordEncoder, JdbcTemplate jdbcTemplate) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jdbcTemplate = jdbcTemplate;
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private boolean isEmailConflict(DataIntegrityViolationException exception) {
        String message = exception.getMostSpecificCause().getMessage();
        return message != null && message.contains(EMAIL_UNIQUE_INDEX);
    }

    public Optional<User> registerUser(String name, String email, String password){
        String normalizedEmail = normalizeEmail(email);

        if (userRepository.existsByEmailIgnoreCase(normalizedEmail)){
             return Optional.empty();
        }

        User user = new User();
        user.setName(name);
        user.setEmail(normalizedEmail);
        user.setPassword(passwordEncoder.encode(password));
        user.setRole("USER");
        user.setBanned(false);

        try {
            return Optional.of(userRepository.save(user));
        } catch (DataIntegrityViolationException exception) {
            if (isEmailConflict(exception)) {
                return Optional.empty();
            }
            throw exception;
        }
    }

    public List<UserResponse> getUsers(){
        List<User> users = userRepository.findAll();
        List<UserResponse> responses = new ArrayList<>();

        for (User user : users){
            UserResponse userResponse = new UserResponse();

            userResponse.setId(user.getId());
            userResponse.setEmail(user.getEmail());
            userResponse.setName(user.getName());

            responses.add(userResponse);
            
        }

        return responses;
    }

    public Optional<UserResponse> getUserById(long id){
        Optional<User> user = userRepository.findById(id);
        

        if (user.isPresent()){
            User existUser = user.get();

            UserResponse userResponse = new UserResponse();

            userResponse.setId(existUser.getId());
            userResponse.setEmail(existUser.getEmail());
            userResponse.setName(existUser.getName());

            return Optional.of(userResponse);
        }

        return Optional.empty();
    }

    public void deleteById(long id){
        userRepository.deleteById(id);
    }

    public Optional<UserResponse> updateUser(Long id, UserUpdateRequest request){
        Optional<User> user = userRepository.findById(id);

        if (user.isPresent()){
            User existUser = user.get();

            String normalizedEmail = normalizeEmail(request.getEmail());

            if (userRepository.existsByEmailIgnoreCaseAndIdNot(normalizedEmail, id)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Email is already in use");
            }

            existUser.setName(request.getName());
            existUser.setEmail(normalizedEmail);

            User updatedUser;

            try {
                updatedUser = userRepository.save(existUser);
            } catch (DataIntegrityViolationException exception) {
                if (isEmailConflict(exception)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Email is already in use");
                }
                throw exception;
            }

            UserResponse userResponse = new UserResponse();

            userResponse.setId(updatedUser.getId());
            userResponse.setEmail(updatedUser.getEmail());
            userResponse.setName(updatedUser.getName());

            return Optional.of(userResponse);
        }

        return Optional.empty();
    }

    public Optional<User> login(LoginRequest loginRequest){
        Optional<User> user = userRepository.findByLoginEmail(loginRequest.getEmail());
        if (user.isPresent()){
            User existUser = user.get();

            boolean passwordCorrect = passwordEncoder.matches(loginRequest.getPassword(),existUser.getPassword());
            if (passwordCorrect && !existUser.isBanned()) {
                return Optional.of(existUser);
            }
        }
        return Optional.empty();


    }

    public Optional<UserResponse> getUserByEmail(String email){
        Optional<User> user = userRepository.findByEmail(email);
        if (user.isPresent()) {

            User existUser = user.get();

            UserResponse userResponse = new UserResponse();

            userResponse.setId(existUser.getId());
            userResponse.setEmail(existUser.getEmail());
            userResponse.setName(existUser.getName());

            return Optional.of(userResponse);
        }

        return Optional.empty();
    }

    public Optional<ProfileResponse> getProfileByEmail(String email) {

        Optional<User> user = userRepository.findByEmail(email);

        if (user.isPresent()) {

            User existUser = user.get();

            ProfileResponse profileResponse = new ProfileResponse();

            profileResponse.setId(existUser.getId());
            profileResponse.setName(existUser.getName());
            profileResponse.setEmail(existUser.getEmail());
            profileResponse.setUsername(existUser.getUsername());
            profileResponse.setProfession(existUser.getProfession());
            profileResponse.setBio(existUser.getBio());
            profileResponse.setInterests(existUser.getInterests());
            profileResponse.setGoals(existUser.getGoals());
            profileResponse.setAvatarUrl(existUser.getAvatarUrl());
            profileResponse.setHiddenByAdmin(existUser.isProfileHiddenByAdmin());
            

            return Optional.of(profileResponse);
        }

        return Optional.empty();
    }

    @Transactional
    public Optional<ProfileResponse> updateProfile(
            String email,
            ProfileUpdateRequest request
    ) {

        ImageUrlPolicy.requireSafe(request.getAvatarUrl());

        String name = ProfileRules.name(request.getName());
        String username = ProfileRules.username(request.getUsername());
        String profession = ProfileRules.text(request.getProfession(), ProfileRules.MAX_PROFESSION_LENGTH, "Profession");
        String bio = ProfileRules.text(request.getBio(), ProfileRules.MAX_TEXT_LENGTH, "Bio");
        String interests = ProfileRules.text(request.getInterests(), ProfileRules.MAX_TEXT_LENGTH, "Interests");
        String goals = ProfileRules.text(request.getGoals(), ProfileRules.MAX_TEXT_LENGTH, "Goals");

        Optional<User> user = userRepository.findByEmail(email);

        if (user.isPresent()) {

            User existUser = user.get();

            // A username already on the account stays even if it is shared with an older account.
            if (username != null && !username.equals(existUser.getUsername())) {
                ProfileRules.requireValidUsername(username);

                // Two people claiming the same name at once wait for each other here.
                jdbcTemplate.query(
                        "SELECT pg_advisory_xact_lock(hashtext(?))",
                        resultSet -> null,
                        "username:" + username.toLowerCase(Locale.ROOT)
                );

                if (userRepository.existsByUsernameIgnoreCaseAndIdNot(username, existUser.getId())) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "That username is already taken");
                }
            }

            existUser.setName(name);
            existUser.setUsername(username);
            existUser.setProfession(profession);
            existUser.setBio(bio);
            existUser.setInterests(interests);
            existUser.setGoals(goals);
            existUser.setAvatarUrl(request.getAvatarUrl());

            return Optional.of(toProfile(userRepository.saveAndFlush(existUser)));
        }

        return Optional.empty();
    }

    // The photo passed the same checks as routine images; the stored path replaces the old one.
    @Transactional
    public Optional<ProfileResponse> updateAvatar(String email, String avatarUrl) {

        return userRepository.findByEmail(email).map(user -> {
            user.setAvatarUrl(avatarUrl);
            return toProfile(userRepository.save(user));
        });
    }

    private ProfileResponse toProfile(User user) {

        ProfileResponse profileResponse = new ProfileResponse();

        profileResponse.setId(user.getId());
        profileResponse.setName(user.getName());
        profileResponse.setEmail(user.getEmail());
        profileResponse.setUsername(user.getUsername());
        profileResponse.setProfession(user.getProfession());
        profileResponse.setBio(user.getBio());
        profileResponse.setInterests(user.getInterests());
        profileResponse.setGoals(user.getGoals());
        profileResponse.setAvatarUrl(user.getAvatarUrl());
        profileResponse.setHiddenByAdmin(user.isProfileHiddenByAdmin());

        return profileResponse;
    }

    public List<UserSearchResponse> searchUsers(
            String query,
            String currentEmail
    ) {

        List<UserSearchResponse> responses = new ArrayList<>();
        String text = SearchText.normalize(query);

        if (text.isEmpty()) {
            return responses;
        }

        // One more than shown, so that leaving the searcher out still leaves a full page.
        List<User> users =
                userRepository.searchPeople(SearchText.containsPattern(text), PageRequest.of(0, MAX_PEOPLE_FOUND + 1));

        for (User user : users) {

            if (user.getEmail().equals(currentEmail)) {
                continue;
            }

            UserSearchResponse response =
                    new UserSearchResponse();

            response.setId(user.getId());
            response.setName(user.getName());
            response.setUsername(user.getUsername());
            response.setAvatarUrl(user.getAvatarUrl());
            response.setProfession(user.getProfession());

            responses.add(response);
        }

        return responses.size() > MAX_PEOPLE_FOUND ? responses.subList(0, MAX_PEOPLE_FOUND) : responses;
    }

    public boolean isVisiblePublicUser(Long userId) {
        return userRepository.findById(userId)
                .filter(user -> "USER".equals(user.getRole()))
                .filter(user -> !user.isBanned())
                // US-25: a profile an administrator hid is not shown to anyone else.
                .filter(user -> !user.isProfileHiddenByAdmin())
                .isPresent();
    }

}

