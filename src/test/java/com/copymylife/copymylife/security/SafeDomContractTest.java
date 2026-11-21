package com.copymylife.copymylife.security;

import com.copymylife.copymylife.service.ImageUrlPolicy;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SafeDomContractTest {

    @Test
    void browserHelperKeepsItsContract() throws Exception {
        Process process;
        try {
            process = new ProcessBuilder("node", "src/test/e2e/safe-dom.test.mjs").redirectErrorStream(true).start();
        } catch (IOException exception) {
            Assumptions.abort("node not found");
            return;
        }

        String output = new String(process.getInputStream().readAllBytes());
        assertTrue(process.waitFor(60, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue(), output);
    }

    @Test
    void serverPolicyMatchesTheBrowserHelper() {
        for (String url : List.of(
                "javascript:alert(1)", "JAVASCRIPT:alert(1)", " javascript:alert(1)", "java\tscript:alert(1)",
                "java\nscript:alert(1)", "\u0001javascript:alert(1)", "vbscript:msgbox(1)",
                "data:text/html,<script>alert(1)</script>", "data:image/svg+xml,<svg onload=alert(1)>",
                "blob:https://example.com/x", "file:///etc/passwd", "ftp://example.com/a.png"
        )) {
            assertFalse(ImageUrlPolicy.isSafe(url), url);
        }

        for (String url : List.of(
                "https://i.ibb.co/abc/photo.jpg", "HTTP://example.com/a.png", "/uploads/routines/a.png",
                "img/halloween/01-billy.jpg", "//cdn.example.com/a.png", "data:image/png;base64,iVBORw0KGgo="
        )) {
            assertTrue(ImageUrlPolicy.isSafe(url), url);
        }

        assertTrue(ImageUrlPolicy.isSafe(null));
        assertTrue(ImageUrlPolicy.isSafe(""));
    }
}
