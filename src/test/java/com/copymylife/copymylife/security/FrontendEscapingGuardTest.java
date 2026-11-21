package com.copymylife.copymylife.security;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fails when a page writes an API field straight into an HTML template, so a new unescaped
 * template cannot slip in unnoticed. Needs no database or browser.
 */
class FrontendEscapingGuardTest {

    private static final Path STATIC = Path.of("src/main/resources/static");

    private static final List<String> PAGES_WITH_API_TEMPLATES = List.of(
            "profile.html", "user-profile.html", "routine.html", "routine-run.js", "top-routines.html",
            "lifestyle-matches.js", "assistant.js"
    );

    private static final List<String> PAGES_USING_HELPER = List.of(
            "profile.html", "user-profile.html", "routine.html", "routine-run.html",
            "top-routines.html", "Explore.html", "main.html", "admin-profile.html"
    );

    private static final Pattern RAW_API_FIELD = Pattern.compile(
            "\\$\\{\\s*\\(?\\s*(routine|user|request|friend|habit|event|profile|item|c|comment)\\."
                    + "(title|name|fullName|username|description|bio|profession|category|icon"
                    + "|routineTitle|authorName|text|imageUrl|avatarUrl)\\b"
    );

    private static final Pattern RAW_URL_IN_ATTRIBUTE = Pattern.compile(
            "src=\"\\$\\{(?!SafeDom\\.imageSrc\\()|url\\(['\"]\\$\\{"
    );

    // showDeleteConfirm writes its message with textContent, and document.title is never parsed as HTML.
    private static boolean goesToPlainTextSink(String line) {
        return line.contains("`Are you sure you want to delete") || line.contains("document.title =");
    }

    @Test
    void apiFieldsAreNeverWrittenRawIntoTemplates() throws IOException {
        List<String> problems = new ArrayList<>();

        for (String page : PAGES_WITH_API_TEMPLATES) {
            List<String> lines = Files.readAllLines(STATIC.resolve(page));

            for (int i = 0; i < lines.size(); i++) {
                if (RAW_API_FIELD.matcher(lines.get(i)).find() && !goesToPlainTextSink(lines.get(i))) {
                    problems.add(page + ":" + (i + 1) + " " + lines.get(i).strip());
                }
            }
        }

        assertEquals(List.of(), problems);
    }

    @Test
    void imageUrlsInTemplatesGoThroughTheHelper() throws IOException {
        List<String> problems = new ArrayList<>();

        for (String page : List.of("profile.html", "user-profile.html", "routine.html", "routine-run.js", "top-routines.html", "Explore.html")) {
            List<String> lines = Files.readAllLines(STATIC.resolve(page));

            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                boolean catalogImage = page.equals("Explore.html") && line.contains("${routine.image}");

                if (!catalogImage && RAW_URL_IN_ATTRIBUTE.matcher(line).find()) {
                    problems.add(page + ":" + (i + 1) + " " + line.strip());
                }
            }
        }

        assertEquals(List.of(), problems);
    }

    @Test
    void escapingHelpersCoverQuotes() throws IOException {
        String helper = Files.readString(STATIC.resolve("safe-dom.js"));

        assertTrue(helper.contains("'\"': \"&quot;\""));
        assertTrue(helper.contains("\"'\": \"&#39;\""));
        assertFalse(Files.readString(STATIC.resolve("routine.html")).contains("return div.innerHTML"));
        assertFalse(Files.readString(STATIC.resolve("routine-run.js")).contains("return div.innerHTML"));
    }

    // The AI Assistant modules use SafeDom; the profile that mounts them loads the helper first.
    @Test
    void theProfileLoadsTheHelperBeforeTheAiModules() throws IOException {
        String profile = Files.readString(STATIC.resolve("profile.html"));
        int helper = profile.indexOf("<script src=\"safe-dom.js\"></script>");

        for (String module : List.of("lifestyle-matches.js", "assistant.js")) {
            int at = profile.indexOf("<script src=\"" + module + "\"></script>");
            assertTrue(helper >= 0 && at > helper, "profile.html loads " + module + " without safe-dom.js before it");
        }
    }

    @Test
    void pagesLoadTheHelperBeforeTheirOwnScripts() throws IOException {
        for (String page : PAGES_USING_HELPER) {
            String source = Files.readString(STATIC.resolve(page));
            int helper = source.indexOf("<script src=\"safe-dom.js\"></script>");

            assertTrue(helper >= 0, page + " does not load safe-dom.js");

            int firstUse = source.indexOf("SafeDom.");
            assertTrue(firstUse < 0 || helper < firstUse, page + " uses SafeDom before loading it");
        }
    }
}
