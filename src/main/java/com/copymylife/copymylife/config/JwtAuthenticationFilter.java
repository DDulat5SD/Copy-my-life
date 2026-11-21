package com.copymylife.copymylife.config;

import com.copymylife.copymylife.model.User;
import com.copymylife.copymylife.repository.UserRepository;
import com.copymylife.copymylife.service.JwtService;
import com.copymylife.copymylife.service.TokenRevocationService;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final TokenRevocationService tokenRevocationService;

    public JwtAuthenticationFilter(JwtService jwtService, UserRepository userRepository, TokenRevocationService tokenRevocationService) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
        this.tokenRevocationService = tokenRevocationService;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String authHeader = request.getHeader("Authorization");


        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = authHeader.substring(7);

        Optional<Claims> claims = jwtService.readClaims(token);

        if (claims.isPresent()){
            Optional<User> user = parseUserId(claims.get()).flatMap(userRepository::findById);

            // A banned user, a signed-out token, or one from before a password reset is not accepted.
            if (user.isPresent()
                    && !user.get().isBanned()
                    && JwtService.versionOf(claims.get()) == user.get().currentTokenVersion()
                    && !tokenRevocationService.isRevoked(token)) {
                User currentUser = user.get();

                String role = currentUser.getRole();

                if (!"ADMIN".equals(role)) {
                    role = "USER";
                }

                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(
                                currentUser.getEmail(),
                                null,
                                List.of(
                                        new SimpleGrantedAuthority("ROLE_" + role)
                                )
                        );

                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
        }

        filterChain.doFilter(request,response);
    }

    private Optional<Long> parseUserId(Claims claims) {
        try {
            return Optional.of(Long.parseLong(claims.getSubject()));
        } catch (NumberFormatException exception) {
            return Optional.empty();
        }
    }
}
