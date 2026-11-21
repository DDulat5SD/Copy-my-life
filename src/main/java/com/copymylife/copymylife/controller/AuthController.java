package com.copymylife.copymylife.controller;

import com.copymylife.copymylife.dto.LoginRequest;
import com.copymylife.copymylife.dto.LoginResponse;
import com.copymylife.copymylife.dto.RegisterRequest;
import com.copymylife.copymylife.dto.ResetPasswordRequest;
import com.copymylife.copymylife.dto.UserResponse;
import com.copymylife.copymylife.model.User;
import com.copymylife.copymylife.service.JwtService;
import com.copymylife.copymylife.service.PasswordResetService;
import com.copymylife.copymylife.service.PasswordRules;
import com.copymylife.copymylife.service.TokenRevocationService;
import com.copymylife.copymylife.service.UserService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;

import java.util.Map;
import java.util.Optional;


@RestController
@RequestMapping("/auth")
public class AuthController {

    private final UserService userService;
    private final JwtService jwtService;
    private final PasswordResetService passwordResetService;
    private final TokenRevocationService tokenRevocationService;

    public AuthController(UserService userService, JwtService jwtService, PasswordResetService passwordResetService, TokenRevocationService tokenRevocationService) {
        this.userService = userService;
        this.jwtService = jwtService;
        this.passwordResetService = passwordResetService;
        this.tokenRevocationService = tokenRevocationService;
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(@Valid @RequestBody RegisterRequest request) {

        String name = request.getName() == null ? "" : request.getName().trim();
        String email = request.getEmail() == null ? "" : request.getEmail().trim();
        String password = request.getPassword() == null ? "" : request.getPassword();

        if (name.length() < 2) {
            return ResponseEntity.badRequest().body(
                    Map.of("field", "name", "message", "Please enter your full name.")
            );
        }

        if (!email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]{2,}$")) {
            return ResponseEntity.badRequest().body(
                    Map.of("field", "email", "message", "Please enter a valid email address.")
            );
        }

        if (!PasswordRules.isStrong(password)) {
            return ResponseEntity.badRequest().body(
                    Map.of("field", "password", "message", PasswordRules.MESSAGE)
            );
        }

        Optional<User> newUser = userService.registerUser(name, email, password);

        if (newUser.isPresent()) {

            User savedUser = newUser.get();

            UserResponse userResponse = new UserResponse();
            userResponse.setId(savedUser.getId());
            userResponse.setName(savedUser.getName());
            userResponse.setEmail(savedUser.getEmail());

            String token = jwtService.generateToken(savedUser);

            LoginResponse loginResponse = new LoginResponse();
            loginResponse.setToken(token);
            loginResponse.setUser(userResponse);

            return ResponseEntity.ok(loginResponse);
        }

        return ResponseEntity.status(409).build();
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest loginRequest) {
        Optional<User> loginUser= userService.login(loginRequest);
        if (loginUser.isPresent()){
            User newUser = loginUser.get();

            UserResponse userResponse = new UserResponse();

            userResponse.setId(newUser.getId());
            userResponse.setName(newUser.getName());
            userResponse.setEmail(newUser.getEmail());

            String token = jwtService.generateToken(newUser);

            LoginResponse loginResponse = new LoginResponse();
            loginResponse.setToken(token);
            loginResponse.setUser(userResponse);

            return ResponseEntity.ok(loginResponse);
        }
        return ResponseEntity.status(401)
                .body(Map.of("message", "Invalid email or password. Please try again."));
    }

    // The token in the request stops working on every device that still holds it.
    // Answers the same whether or not there was a valid token to sign out.
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestHeader(value = "Authorization", required = false) String authorization) {

        if (authorization != null && authorization.startsWith("Bearer ")) {
            tokenRevocationService.revoke(authorization.substring(7));
        }

        return ResponseEntity.noContent().build();
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<?> forgotPassword(@RequestBody Map<String, String> request) {

        String email = request.get("email");

        passwordResetService.createResetToken(email);

        // The same answer for every address, so it never tells who has an account.
        return ResponseEntity.ok(Map.of("message", "Reset link sent successfully"));
    }

    @PostMapping("/reset-password")
    public ResponseEntity<?> resetPassword(@RequestBody ResetPasswordRequest request) {

        passwordResetService.resetPassword(request.getToken(), request.getNewPassword());

        return ResponseEntity.ok(Map.of("message", "Password reset successfully"));
    }

}