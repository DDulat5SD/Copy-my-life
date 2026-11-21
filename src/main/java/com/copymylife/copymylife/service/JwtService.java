package com.copymylife.copymylife.service;

import com.copymylife.copymylife.model.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class JwtService {

    // Tokens issued before this claim existed have no version, which counts as 0.
    public static final String VERSION_CLAIM = "ver";

    private static final Pattern BASE64URL_PART = Pattern.compile("[A-Za-z0-9_-]+={0,2}");

    @Value("${jwt.secret}")
    String secret;

    @Value("${jwt.expiration}")
    long expiration;

    private SecretKey getSigningKey(){

        byte[] keyBytes = Decoders.BASE64.decode(secret);
        return Keys.hmacShaKeyFor(keyBytes);

    }

    public String generateToken(User user) {

        Date now = new Date();

        Date expirationDate =  new Date(System.currentTimeMillis() + expiration);

        // The random id keeps two sign-ins in the same second from sharing one token.
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(user.getId().toString())
                .claim(VERSION_CLAIM, user.currentTokenVersion())
                .issuedAt(now)
                .expiration(expirationDate)
                .signWith(getSigningKey())
                .compact();


    }

    // The token's claims when it is spelled canonically and its signature and expiry are valid; empty otherwise.
    public Optional<Claims> readClaims(String token) {
        try {
            return Optional.of(parse(token));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public Long extractUserId(String token) {

        String subject = parse(token).getSubject();

        return Long.parseLong(subject);
    }

    // jjwt also accepts look-alikes of a token: "=" padding, or another last character whose
    // extra bits Base64URL decoding throws away. They carry the same signature but hash
    // differently, so they would slip past a sign-out. Only the one canonical spelling is accepted.
    private Claims parse(String token) {
        if (!isCanonical(token)) {
            throw new MalformedJwtException("JWT is not in canonical Base64URL form");
        }

        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public static boolean isCanonical(String token) {
        return canonicalForm(token).map(token::equals).orElse(false);
    }

    // The one spelling of a header.payload.signature token: each part unpadded Base64URL with
    // its unused low bits zero. Empty when the text is not three Base64URL parts.
    public static Optional<String> canonicalForm(String token) {
        if (token == null) {
            return Optional.empty();
        }

        String[] parts = token.split("\\.", -1);

        if (parts.length != 3) {
            return Optional.empty();
        }

        StringBuilder canonical = new StringBuilder();

        for (String part : parts) {
            if (!BASE64URL_PART.matcher(part).matches()) {
                return Optional.empty();
            }

            byte[] bytes;

            try {
                bytes = Base64.getUrlDecoder().decode(part);
            } catch (IllegalArgumentException exception) {
                return Optional.empty();
            }

            if (!canonical.isEmpty()) {
                canonical.append('.');
            }
            canonical.append(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
        }

        return Optional.of(canonical.toString());
    }

    public boolean isTokenValid(String token){
        try {
            extractUserId(token);
            return true;
        }
        catch (JwtException | IllegalArgumentException e){
            return false;
        }
    }

    public static int versionOf(Claims claims) {
        Object version = claims.get(VERSION_CLAIM);
        return version instanceof Number number ? number.intValue() : 0;
    }

    // What is stored for a signed-out token: never the token itself.
    public static String fingerprint(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
