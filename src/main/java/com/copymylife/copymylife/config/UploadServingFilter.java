package com.copymylife.copymylife.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Locale;
import java.util.Map;

/**
 * Protects every stored file, including ones saved before uploads were validated: only passive
 * media extensions are served, each with a fixed type, and the browser may never run or sniff them.
 * Stored files are left in place; anything else simply answers 404.
 */
@Component
public class UploadServingFilter extends OncePerRequestFilter {

    private static final String UPLOADS_PATH = "/uploads/";

    private static final Map<String, String> SERVED_TYPES = Map.ofEntries(
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("jpe", "image/jpeg"),
            Map.entry("jfif", "image/jpeg"),
            Map.entry("pjpeg", "image/jpeg"),
            Map.entry("pjp", "image/jpeg"),
            Map.entry("png", "image/png"),
            Map.entry("gif", "image/gif"),
            Map.entry("webp", "image/webp"),
            Map.entry("bmp", "image/bmp"),
            Map.entry("avif", "image/avif"),
            Map.entry("heic", "image/heic"),
            Map.entry("heif", "image/heif"),
            Map.entry("tif", "image/tiff"),
            Map.entry("tiff", "image/tiff"),
            Map.entry("ico", "image/x-icon"),
            Map.entry("mp4", "video/mp4"),
            Map.entry("m4v", "video/mp4"),
            Map.entry("webm", "video/webm"),
            Map.entry("mov", "video/quicktime"),
            Map.entry("ogv", "video/ogg")
    );

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getServletPath().startsWith(UPLOADS_PATH);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String type = SERVED_TYPES.get(extensionOf(request.getServletPath()));

        if (type == null) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Content-Security-Policy", "default-src 'none'; sandbox");

        filterChain.doFilter(request, new FixedTypeResponse(response, type));
    }

    private String extensionOf(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');

        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * Fixes the type at the moment a file is written, whatever the resource handler guessed
     * from its name. Error responses, such as a missing file, keep their own type.
     */
    private static final class FixedTypeResponse extends HttpServletResponseWrapper {

        private final String type;

        private FixedTypeResponse(HttpServletResponse response, String type) {
            super(response);
            this.type = type;
        }

        @Override
        public ServletOutputStream getOutputStream() throws IOException {
            if (getStatus() < SC_BAD_REQUEST) {
                setContentType(type);
            }
            return super.getOutputStream();
        }
    }
}
