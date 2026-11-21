package com.copymylife.copymylife.service;

import com.copymylife.copymylife.exception.FileUploadRejectedException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Accepts a file only when its name, declared type and actual bytes all agree on an allowed
 * format, then stores it under a generated name. The client's file name is never used as a path.
 */
@Service
public class FileUploadService {

    private static final String ROUTINE_FOLDER = "routines";
    private static final String AVATAR_FOLDER = "avatars";
    private static final String POST_FOLDER = "posts";
    // Exactly the names store() gives post photos; nothing else is ever deleted.
    private static final Pattern STORED_POST_IMAGE =
            Pattern.compile("/uploads/posts/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(?:jpg|png|gif|webp))");
    private static final long IMAGE_LIMIT = 5L * 1024 * 1024;
    private static final int HEADER_SIZE = 64;
    private static final String NOT_ALLOWED = "Only JPEG, PNG, WebP or GIF images can be uploaded.";

    private enum Format {
        JPEG("jpg", "jpeg", Set.of("jpg", "jpeg", "jpe", "jfif"), Set.of("image/jpeg", "image/jpg", "image/pjpeg")),
        PNG("png", "png", Set.of("png"), Set.of("image/png", "image/x-png")),
        GIF("gif", "gif", Set.of("gif"), Set.of("image/gif")),
        WEBP("webp", null, Set.of("webp"), Set.of("image/webp"));

        private final String storedExtension;
        private final String imageIoName;
        private final Set<String> extensions;
        private final Set<String> mimeTypes;

        Format(String storedExtension, String imageIoName, Set<String> extensions, Set<String> mimeTypes) {
            this.storedExtension = storedExtension;
            this.imageIoName = imageIoName;
            this.extensions = extensions;
            this.mimeTypes = mimeTypes;
        }
    }

    private final Path uploadRoot;

    public FileUploadService(@Value("${app.upload-dir:uploads}") String uploadDir) {
        this.uploadRoot = Path.of(uploadDir);
    }

    public String storeRoutineImage(MultipartFile file) {
        return store(file, ROUTINE_FOLDER);
    }

    public String storeAvatar(MultipartFile file) {
        return store(file, AVATAR_FOLDER);
    }

    public String storePostImage(MultipartFile file) {
        return store(file, POST_FOLDER);
    }

    // The file behind a post photo this service stored, with its media type. Anything else,
    // such as a path that tries to leave the posts folder, finds nothing.
    public Optional<StoredImage> openPostImage(String url) {
        if (url == null) {
            return Optional.empty();
        }

        Matcher stored = STORED_POST_IMAGE.matcher(url);

        if (!stored.matches()) {
            return Optional.empty();
        }

        Path file = uploadRoot.resolve(POST_FOLDER).resolve(stored.group(1));

        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }

        String extension = extensionOf(stored.group(1));
        String type = switch (extension) {
            case "png" -> "image/png";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            default -> "image/jpeg";
        };

        return Optional.of(new StoredImage(file, type));
    }

    public record StoredImage(Path file, String mediaType) {
    }

    // Removes a post photo this service stored. Any other value, such as a path that tries to
    // leave the posts folder, matches nothing and is left alone.
    public void deletePostImage(String url) {
        if (url == null) {
            return;
        }

        Matcher stored = STORED_POST_IMAGE.matcher(url);

        if (!stored.matches()) {
            return;
        }

        try {
            Files.deleteIfExists(uploadRoot.resolve(POST_FOLDER).resolve(stored.group(1)));
        } catch (IOException exception) {
            // The post is already gone; a leftover file only takes disk space.
        }
    }

    private String store(MultipartFile file, String folderName) {
        Format format = validate(file);
        String name = UUID.randomUUID() + "." + format.storedExtension;

        try {
            Path folder = uploadRoot.resolve(folderName);
            Files.createDirectories(folder);

            try (InputStream content = file.getInputStream()) {
                Files.copy(content, folder.resolve(name));
            }
        } catch (IOException exception) {
            throw new RuntimeException("Image upload failed");
        }

        return "/uploads/" + folderName + "/" + name;
    }

    private Format validate(MultipartFile file) {
        byte[] header = readHeader(file);
        Format format = detect(header, file.getSize());

        if (format == null || !format.extensions.contains(extensionOf(file.getOriginalFilename()))) {
            throw rejected(HttpStatus.UNSUPPORTED_MEDIA_TYPE, NOT_ALLOWED);
        }

        if (!declaredTypeMatches(file.getContentType(), format)) {
            throw rejected(HttpStatus.UNSUPPORTED_MEDIA_TYPE, NOT_ALLOWED);
        }

        if (file.getSize() > IMAGE_LIMIT) {
            throw rejected(HttpStatus.CONTENT_TOO_LARGE, "This image is too large. The maximum size is 5 MB.");
        }

        if (format.imageIoName != null && !isReadableImage(file, format)) {
            throw rejected(HttpStatus.UNSUPPORTED_MEDIA_TYPE, NOT_ALLOWED);
        }

        return format;
    }

    private byte[] readHeader(MultipartFile file) {
        try (InputStream content = file.getInputStream()) {
            return content.readNBytes(HEADER_SIZE);
        } catch (IOException exception) {
            throw new RuntimeException("Image upload failed");
        }
    }

    private Format detect(byte[] header, long size) {
        if (startsWith(header, 0, 0xFF, 0xD8, 0xFF)) {
            return Format.JPEG;
        }
        if (startsWith(header, 0, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)) {
            return Format.PNG;
        }
        if (hasText(header, 0, "GIF87a") || hasText(header, 0, "GIF89a")) {
            return Format.GIF;
        }
        if (isWebp(header, size)) {
            return Format.WEBP;
        }
        return null;
    }

    private boolean isWebp(byte[] header, long size) {
        if (!hasText(header, 0, "RIFF") || !hasText(header, 8, "WEBP") || header.length < 16) {
            return false;
        }

        long declaredSize = (header[4] & 0xFFL)
                | (header[5] & 0xFFL) << 8
                | (header[6] & 0xFFL) << 16
                | (header[7] & 0xFFL) << 24;

        boolean knownChunk = hasText(header, 12, "VP8 ") || hasText(header, 12, "VP8L") || hasText(header, 12, "VP8X");

        return knownChunk && declaredSize + 8 <= size;
    }

    private boolean isReadableImage(MultipartFile file, Format format) {
        Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName(format.imageIoName);

        if (!readers.hasNext()) {
            return false;
        }

        ImageReader reader = readers.next();

        try (InputStream content = file.getInputStream();
             ImageInputStream stream = new MemoryCacheImageInputStream(content)) {
            reader.setInput(stream, true, true);
            return reader.getWidth(0) > 0 && reader.getHeight(0) > 0;
        } catch (IOException | RuntimeException exception) {
            return false;
        } finally {
            reader.dispose();
        }
    }

    private boolean declaredTypeMatches(String contentType, Format format) {
        if (contentType == null || contentType.isBlank()) {
            return true;
        }

        String type = contentType.split(";")[0].strip().toLowerCase(Locale.ROOT);

        return type.equals("application/octet-stream") || format.mimeTypes.contains(type);
    }

    private String extensionOf(String fileName) {
        if (fileName == null) {
            return "";
        }

        String name = fileName.substring(Math.max(fileName.lastIndexOf('/'), fileName.lastIndexOf('\\')) + 1);
        int dot = name.lastIndexOf('.');

        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private boolean startsWith(byte[] header, int offset, int... expected) {
        if (header.length < offset + expected.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if ((header[offset + i] & 0xFF) != expected[i]) {
                return false;
            }
        }
        return true;
    }

    private boolean hasText(byte[] header, int offset, String text) {
        byte[] expected = text.getBytes(StandardCharsets.ISO_8859_1);
        return header.length >= offset + expected.length
                && Arrays.equals(header, offset, offset + expected.length, expected, 0, expected.length);
    }

    private FileUploadRejectedException rejected(HttpStatus status, String message) {
        return new FileUploadRejectedException(status, message);
    }
}
