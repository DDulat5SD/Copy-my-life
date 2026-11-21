package com.copymylife.copymylife.security;

import com.copymylife.copymylife.service.EmailService;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises uploads and the serving of already stored files over real HTTP. Files go to a
 * throwaway folder; TEST_DB_URL must point at a local *_test database.
 */
@EnabledIfEnvironmentVariable(
        named = "TEST_DB_URL",
        matches = "jdbc:postgresql://(127\\.0\\.0\\.1|localhost):\\d+/\\w+_test"
)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=${TEST_DB_URL}",
                "spring.datasource.username=${TEST_DB_USERNAME:test}",
                "spring.datasource.password=${TEST_DB_PASSWORD:test}",
                "jwt.secret=dGVzdC1vbmx5LWtleS10ZXN0LW9ubHkta2V5LXRlc3Qtb25seS1rZXktdGVzdC1vbmx5",
                "spring.mail.username=test",
                "spring.mail.password=test",
                "MAILJET_API_KEY=test",
                "MAILJET_SECRET_KEY=test",
                "MAILJET_SENDER_EMAIL=test@invalid.test"
        }
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UploadSecurityRegressionTest {

    private static final String BOUNDARY = "UploadRegressionBoundary";
    private static final int MB = 1024 * 1024;
    private static final String GENERATED_NAME = "/uploads/routines/[0-9a-f]{8}-[0-9a-f-]{27}\\.%s";

    // A real 1x1 lossy WebP.
    private static final byte[] WEBP = Base64.getDecoder()
            .decode("UklGRiIAAABXRUJQVlA4IBYAAAAwAQCdASoBAAEADsD+JaQAA3AAAAAA");
    private static final byte[] MP4 = concat(
            new byte[]{0, 0, 0, 24}, ascii("ftypmp42"), new byte[]{0, 0, 0, 0}, ascii("mp42isom"));
    private static final byte[] WEBM = concat(
            new byte[]{0x1A, 0x45, (byte) 0xDF, (byte) 0xA3, (byte) 0x9F, 0x42, (byte) 0x82, (byte) 0x84}, ascii("webm"));
    private static final byte[] HTML = ascii("<!doctype html><script>window.__mark && __mark('stored-html')</script>");
    private static final byte[] SVG = ascii("<svg xmlns=\"http://www.w3.org/2000/svg\"><script>__mark('stored-svg')</script></svg>");

    private static Path uploadRoot;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();
    private byte[] jpeg;
    private byte[] png;
    private byte[] gif;
    private String token;

    @LocalServerPort
    private int port;

    @MockitoBean
    private EmailService emailService;

    @DynamicPropertySource
    static void isolateUploads(DynamicPropertyRegistry registry) throws Exception {
        uploadRoot = Files.createTempDirectory("copymylife-uploads");
        registry.add("app.upload-dir", uploadRoot::toString);
    }

    @BeforeAll
    void prepare() throws Exception {
        jpeg = image("jpeg");
        png = image("png");
        gif = image("gif");
        token = register();
    }

    private static byte[] image(String format) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), format, out);
        return out.toByteArray();
    }

    // ---- accepted uploads ----

    @Test
    void realImagesAreAcceptedAndServedWithGeneratedNames() throws Exception {
        assertStored(upload("photo.jpg", "image/jpeg", jpeg), "jpg", "image/jpeg", jpeg);
        assertStored(upload("PHOTO.JPEG", "image/jpeg", jpeg), "jpg", "image/jpeg", jpeg);
        assertStored(upload("scan.jfif", "image/jpeg", jpeg), "jpg", "image/jpeg", jpeg);
        assertStored(upload("photo.png", "image/png", png), "png", "image/png", png);
        assertStored(upload("photo.webp", "image/webp", WEBP), "webp", "image/webp", WEBP);
        assertStored(upload("photo.gif", "image/gif", gif), "gif", "image/gif", gif);
    }

    @Test
    void routineCanBeUpdatedWithANewImage() throws Exception {
        JsonNode created = json.readTree(upload("first.png", "image/png", png).body());

        HttpResponse<String> updated = sendRoutine("PUT", "/routines/" + created.path("id").asLong(), token,
                "second.jpg", "image/jpeg", jpeg);

        assertEquals(200, updated.statusCode(), updated.body());
        assertTrue(json.readTree(updated.body()).path("imageUrl").asString().matches(GENERATED_NAME.formatted("jpg")));
    }

    // ---- rejected uploads ----

    @Test
    void activeContentIsRejected() throws Exception {
        assertRejected(upload("evil.html", "text/html", HTML), 415);
        assertRejected(upload("evil.htm", "text/html", HTML), 415);
        assertRejected(upload("evil.svg", "image/svg+xml", SVG), 415);
        assertRejected(upload("evil.js", "text/javascript", ascii("__mark('js')")), 415);
        assertRejected(upload("evil.xml", "application/xml", ascii("<?xml version=\"1.0\"?><a/>")), 415);
        assertRejected(upload("evil.xhtml", "application/xhtml+xml", HTML), 415);
        assertRejected(upload("evil.exe", "application/octet-stream", ascii("MZ-not-really")), 415);
        assertRejected(upload("evil.sh", "text/plain", ascii("#!/bin/sh\n")), 415);
    }

    @Test
    void forgedExtensionOrMimeIsRejected() throws Exception {
        assertRejected(upload("evil.jpg", "image/jpeg", HTML), 415);
        assertRejected(upload("evil.png", "image/png", SVG), 415);
        assertRejected(upload("photo.jpg.html", "image/jpeg", jpeg), 415);
        assertRejected(upload("photo.html", "text/html", jpeg), 415);
        assertRejected(upload("photo.jpg", "text/html", jpeg), 415);
        assertRejected(upload("photo.jpg", "image/png", jpeg), 415);
        assertRejected(upload("photo.png", "image/png", jpeg), 415);
        assertRejected(upload("photo", "image/jpeg", jpeg), 415);
        assertRejected(upload("clip.mp4", "video/mp4", HTML), 415);
        assertRejected(upload("clip.mp4", "image/jpeg", MP4), 415);
    }

    @Test
    void headerOnlyImagesAreRejected() throws Exception {
        assertRejected(upload("fake.gif", "image/gif", ascii("GIF89a<script>__mark('poly')</script>")), 415);
        assertRejected(upload("fake.png", "image/png", concat(Arrays.copyOf(png, 8), HTML)), 415);
        assertRejected(upload("fake.jpg", "image/jpeg", concat(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}, HTML)), 415);
        assertRejected(upload("fake.webp", "image/webp", concat(ascii("RIFF"), new byte[]{10, 0, 0, 0}, ascii("WEBP"), HTML)), 415);
    }

    @Test
    void rejectedUploadsLeaveNoFileAndNoRoutine() throws Exception {
        long filesBefore = storedFileCount();
        int routinesBefore = myRoutineCount();

        assertRejected(upload("evil.html", "text/html", HTML), 415);
        assertRejected(upload("evil.jpg", "image/jpeg", HTML), 415);

        assertEquals(filesBefore, storedFileCount());
        assertEquals(routinesBefore, myRoutineCount());
    }

    @Test
    void uploadWithoutTokenIsRefused() throws Exception {
        long before = storedFileCount();

        assertEquals(403, sendRoutine("POST", "/routines", null, "photo.jpg", "image/jpeg", jpeg).statusCode());
        assertEquals(before, storedFileCount());
    }

    @Test
    void clientFileNameIsNeverUsedAsAPath() throws Exception {
        Path outside = uploadRoot.getParent().resolve("traversal-" + UUID.randomUUID() + ".jpg");

        for (String name : List.of(
                "../../" + outside.getFileName(),
                "..\\..\\" + outside.getFileName(),
                outside.toAbsolutePath().toString(),
                "/etc/" + outside.getFileName(),
                "..%2f..%2f" + outside.getFileName(),
                "a\"b<c>.jpg"
        )) {
            HttpResponse<String> response = upload(name, "image/jpeg", jpeg);

            assertEquals(200, response.statusCode(), name);
            assertTrue(json.readTree(response.body()).path("imageUrl").asString().matches(GENERATED_NAME.formatted("jpg")), name);
            assertFalse(Files.exists(outside), name);
        }
    }

    // ---- size limits ----

    @Test
    void imageSizeLimitIsFiveMegabytes() throws Exception {
        assertEquals(200, upload("max.jpg", "image/jpeg", padded(jpeg, 5 * MB)).statusCode());
        assertRejected(upload("big.jpg", "image/jpeg", padded(jpeg, 5 * MB + 1)), 413);
        assertRejected(upload("big.png", "image/png", padded(png, 6 * MB)), 413);
    }

    @Test
    void videoUploadIsNotPartOfThisRelease() throws Exception {
        long before = storedFileCount();

        assertRejected(upload("clip.mp4", "video/mp4", MP4), 415);
        assertRejected(upload("clip.webm", "video/webm", WEBM), 415);
        assertRejected(upload("clip.mov", "video/quicktime", MP4), 415);
        assertRejected(upload("big.mp4", "video/mp4", padded(MP4, 6 * MB)), 413);
        assertRejected(upload("big.webm", "video/webm", padded(WEBM, 51 * MB)), 413);

        assertEquals(before, storedFileCount());
    }

    // ---- files stored before validation existed ----

    @Test
    void storedActiveFilesAreNotServed() throws Exception {
        for (String name : List.of(
                "legacy.html", "legacy.HTML", "legacy.htm", "legacy.xhtml", "legacy.svg", "legacy.SVG", "legacy.svgz",
                "legacy.xml", "legacy.js", "legacy.mjs", "legacy.css", "legacy.jsp", "legacy.php", "legacy.exe",
                "legacy.pdf", "legacy.swf", "legacy.txt", "legacy_no_extension", "legacy.jpg.html", "legacy.html."
        )) {
            store(name, HTML);

            HttpResponse<String> response = get("/uploads/routines/" + encode(name));

            assertEquals(404, response.statusCode(), name);
            assertFalse(response.body().contains("__mark"), name);
        }
    }

    @Test
    void storedHtmlDisguisedAsAnImageCannotRenderAsAPage() throws Exception {
        store("disguised.jpg", HTML);

        HttpResponse<String> response = get("/uploads/routines/disguised.jpg");

        assertEquals(200, response.statusCode());
        assertEquals("image/jpeg", contentType(response));
        assertProtectedHeaders(response);
    }

    @Test
    void passiveExtensionsWithoutAKnownMimeStillGetASafeType() throws Exception {
        store("legacy.jfif", jpeg);
        store("legacy.heic", jpeg);

        assertEquals("image/jpeg", contentType(get("/uploads/routines/legacy.jfif")));
        assertEquals("image/heic", contentType(get("/uploads/routines/legacy.heic")));
    }

    @Test
    void existingPublicImagesAndVideosKeepTheirUrls() throws Exception {
        Map<String, byte[]> legacy = new LinkedHashMap<>();
        legacy.put("3f2a9a54-1111-4222-8333-444455556666_my_photo.jpg", jpeg);
        legacy.put("3f2a9a54-1111-4222-8333-444455556666_сурет.png", png);
        legacy.put("UPPER.JPG", jpeg);
        legacy.put("anim.gif", gif);
        legacy.put("modern.webp", WEBP);
        legacy.put("old.bmp", jpeg);
        legacy.put("clip.mp4", padded(MP4, 2048));
        legacy.put("clip.webm", WEBM);
        legacy.put("clip.mov", MP4);

        for (Map.Entry<String, byte[]> file : legacy.entrySet()) {
            store(file.getKey(), file.getValue());

            HttpResponse<byte[]> response = getBytes("/uploads/routines/" + encode(file.getKey()), null);

            assertEquals(200, response.statusCode(), file.getKey());
            assertArrayEquals(file.getValue(), response.body(), file.getKey());
            assertEquals("nosniff", response.headers().firstValue("X-Content-Type-Options").orElse(""));
        }
    }

    @Test
    void videosSupportRangeRequests() throws Exception {
        store("range.mp4", padded(MP4, 4096));

        HttpResponse<byte[]> response = getBytes("/uploads/routines/range.mp4", "bytes=0-99");

        assertEquals(206, response.statusCode());
        assertEquals(100, response.body().length);
        assertEquals("video/mp4", response.headers().firstValue("Content-Type").orElse(""));
    }

    @Test
    void pathTricksCannotReachOtherFiles() throws Exception {
        Files.writeString(uploadRoot.getParent().resolve("outside-file.jpg"), "OUTSIDE-FILE-CONTENT");

        for (String path : List.of(
                "/uploads/../outside-file.jpg",
                "/uploads/%2e%2e/outside-file.jpg",
                "/uploads/..%2foutside-file.jpg",
                "/uploads/routines/",
                "/uploads/"
        )) {
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());

            assertTrue(response.statusCode() == 400 || response.statusCode() == 404, path + " -> " + response.statusCode());
            assertFalse(response.body().contains("OUTSIDE-FILE-CONTENT"), path);
        }
    }

    @Test
    void frontendCheckScriptIsPublicAndLoadedWhereFilesAreChosen() throws Exception {
        assertEquals(200, get("/upload-check.js").statusCode());

        for (String page : List.of("/profile.html", "/routine.html")) {
            String source = get(page).body();

            assertTrue(source.contains("<script src=\"upload-check.js\"></script>"), page);
            assertTrue(source.contains("UploadCheck.watchImageInput"), page);
            assertFalse(source.contains("accept=\"image/*\""), page);
        }
    }

    @Test
    void missingFilesAnswerNotFoundAndNotAServerError() throws Exception {
        for (String name : List.of("missing.jpg", "missing.png", "missing.webp", "missing.mp4", "missing.heic", "missing.html", "missing.svg")) {
            assertEquals(404, get("/uploads/routines/" + name).statusCode(), name);
        }
    }

    // ---- real browser ----

    @Test
    void storedFilesDoNotRunInABrowser() throws Exception {
        String chrome = findChrome();
        Assumptions.assumeTrue(chrome != null, "Chrome not found; set CHROME_BIN");
        Assumptions.assumeTrue(nodeAvailable(), "node not found");

        store("browser-page.html", HTML);
        store("browser-page.svg", SVG);
        store("browser-disguised.jpg", HTML);
        store("browser-disguised.png", SVG);
        store("browser-no-extension", HTML);
        store("browser-photo.png", png);
        String uploaded = json.readTree(upload("browser-upload.webp", "image/webp", WEBP).body()).path("imageUrl").asString();

        Path dir = Files.createTempDirectory("upload-browser");
        Path results = dir.resolve("results.json");
        Files.writeString(dir.resolve("fixtures.json"), json.writeValueAsString(Map.of(
                "base", "http://127.0.0.1:" + port,
                "goodImages", List.of("/uploads/routines/browser-photo.png", uploaded),
                "pages", List.of(
                        "/uploads/routines/browser-page.html",
                        "/uploads/routines/browser-page.svg",
                        "/uploads/routines/browser-disguised.jpg",
                        "/uploads/routines/browser-disguised.png",
                        "/uploads/routines/browser-no-extension"
                ))));

        Process process = new ProcessBuilder(
                "node", "src/test/e2e/upload-browser-check.mjs", chrome,
                dir.resolve("fixtures.json").toString(), results.toString()
        ).redirectErrorStream(true).redirectOutput(dir.resolve("node.log").toFile()).start();

        assertTrue(process.waitFor(3, TimeUnit.MINUTES), "Browser check timed out");
        assertEquals(0, process.exitValue(), () -> readQuietly(dir.resolve("node.log")));

        JsonNode result = json.readTree(Files.readString(results));
        List<String> ran = new ArrayList<>();
        for (JsonNode page : result.path("pages")) {
            if (!page.path("marks").isEmpty()) {
                ran.add(page.path("url").asString() + " executed " + page.path("marks"));
            }
        }
        assertEquals(List.of(), ran);
        assertEquals(List.of(), json.treeToValue(result.path("brokenImages"), List.class));
    }

    // ---- helpers ----

    private void assertStored(HttpResponse<String> response, String extension, String type, byte[] content) throws Exception {
        assertEquals(200, response.statusCode(), response.body());
        String url = json.readTree(response.body()).path("imageUrl").asString();
        assertTrue(url.matches(GENERATED_NAME.formatted(extension)), url);

        HttpResponse<byte[]> served = getBytes(url, null);
        assertEquals(200, served.statusCode());
        assertArrayEquals(content, served.body());
        assertEquals(type, served.headers().firstValue("Content-Type").orElse(""));
        assertEquals("nosniff", served.headers().firstValue("X-Content-Type-Options").orElse(""));
        assertTrue(served.headers().firstValue("Content-Security-Policy").orElse("").contains("sandbox"));
    }

    private void assertRejected(HttpResponse<String> response, int status) throws Exception {
        assertEquals(status, response.statusCode(), response.body());
        assertFalse(json.readTree(response.body()).path("message").asString().isBlank(), response.body());
    }

    private void assertProtectedHeaders(HttpResponse<?> response) {
        assertEquals("nosniff", response.headers().firstValue("X-Content-Type-Options").orElse(""));
        assertTrue(response.headers().firstValue("Content-Security-Policy").orElse("").contains("sandbox"));
    }

    private String contentType(HttpResponse<?> response) {
        return response.headers().firstValue("Content-Type").orElse("");
    }

    private Path uploadRoot() {
        return uploadRoot;
    }

    private void store(String name, byte[] content) throws Exception {
        Path folder = uploadRoot().resolve("routines");
        Files.createDirectories(folder);
        // A trailing dot cannot be created on Windows, so write the nearest name the OS allows.
        Files.write(folder.resolve(name.endsWith(".") && File.separatorChar == '\\' ? name.substring(0, name.length() - 1) : name), content);
    }

    private long storedFileCount() throws Exception {
        Path folder = uploadRoot().resolve("routines");
        if (!Files.exists(folder)) {
            return 0;
        }
        try (var files = Files.list(folder)) {
            return files.count();
        }
    }

    private int myRoutineCount() throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(uri("/routines"))
                .header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofString());
        return json.readTree(response.body()).size();
    }

    private static String encode(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<byte[]> getBytes(String path, String range) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path)).GET();
        if (range != null) {
            builder.header("Range", range);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private String register() throws Exception {
        String body = json.writeValueAsString(Map.of(
                "name", "Upload QA",
                "email", "upload_" + UUID.randomUUID().toString().substring(0, 12) + "@audit.test",
                "password", "Audit#12345"
        ));
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(uri("/auth/register"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return json.readTree(response.body()).path("token").asString();
    }

    private HttpResponse<String> upload(String fileName, String contentType, byte[] content) throws Exception {
        return sendRoutine("POST", "/routines", token, fileName, contentType, content);
    }

    private HttpResponse<String> sendRoutine(String method, String path, String bearer, String fileName, String contentType, byte[] content) throws Exception {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("title", "Upload routine");
        fields.put("description", "d");
        fields.put("category", "Morning");
        fields.put("durationMinutes", "30");
        fields.put("isPublic", "false");
        for (Map.Entry<String, String> field : fields.entrySet()) {
            body.write(("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"" + field.getKey() + "\"\r\n\r\n"
                    + field.getValue() + "\r\n").getBytes(StandardCharsets.UTF_8));
        }
        body.write(("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"image\"; filename=\""
                + fileName.replace("\"", "%22") + "\"\r\nContent-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(content);
        body.write(("\r\n--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8));

        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .method(method, HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()));
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static byte[] padded(byte[] content, int size) {
        return Arrays.copyOf(content, size);
    }

    private static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    private static String readQuietly(Path path) {
        try {
            return Files.readString(path);
        } catch (Exception exception) {
            return "(no output)";
        }
    }

    private static boolean nodeAvailable() {
        try {
            return new ProcessBuilder("node", "--version").redirectErrorStream(true).start().waitFor() == 0;
        } catch (Exception exception) {
            return false;
        }
    }

    private static String findChrome() {
        List<String> candidates = new ArrayList<>();
        if (System.getenv("CHROME_BIN") != null) {
            candidates.add(System.getenv("CHROME_BIN"));
        }
        candidates.add("C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe");
        candidates.add("C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe");
        candidates.add("/usr/bin/google-chrome");
        candidates.add("/usr/bin/chromium");
        candidates.add("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome");
        return candidates.stream().filter(path -> new File(path).isFile()).findFirst().orElse(null);
    }
}
