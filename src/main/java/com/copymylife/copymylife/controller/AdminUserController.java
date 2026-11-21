
package com.copymylife.copymylife.controller;

import com.copymylife.copymylife.model.User;
import com.copymylife.copymylife.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/admin-api/users")
public class AdminUserController {

    private final UserRepository userRepository;

    public AdminUserController(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public record AdminUserResponse(
            Long id,
            String name,
            String username,
            String email,
            String role,
            boolean banned
    ) {}

    private AdminUserResponse toResponse(User user) {
        return new AdminUserResponse(
                user.getId(),
                user.getName(),
                user.getUsername(),
                user.getEmail(),
                user.getRole(),
                user.isBanned()
        );
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<AdminUserResponse> getUsers() {
        return userRepository.findAll()
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @GetMapping("/me")
    public AdminUserResponse getAdmin(Authentication authentication) {
        User admin = userRepository.findByEmail(
                authentication.getName()
        ).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.UNAUTHORIZED
        ));

        return toResponse(admin);
    }

    @PutMapping("/{userId}/ban")
    @Transactional
    public AdminUserResponse ban(
            @PathVariable Long userId,
            Authentication authentication
    ) {
        return changeBan(userId, true, authentication);
    }

    @PutMapping("/{userId}/unban")
    @Transactional
    public AdminUserResponse unban(
            @PathVariable Long userId,
            Authentication authentication
    ) {
        return changeBan(userId, false, authentication);
    }

    private AdminUserResponse changeBan(
            Long userId,
            boolean banned,
            Authentication authentication
    ) {
        User target = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "User not found"
                ));

        if (target.getEmail().equalsIgnoreCase(authentication.getName())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "You cannot ban yourself"
            );
        }

        if ("ADMIN".equals(target.getRole())) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Administrator accounts cannot be banned here"
            );
        }

        target.setBanned(banned);

        return toResponse(userRepository.save(target));
    }
}
