package com.copymylife.copymylife.service;

import java.util.regex.Pattern;

/**
 * Decides which image URLs may be stored. Keep in step with safeImageUrl in safe-dom.js.
 */
public final class ImageUrlPolicy {

    private static final Pattern SCHEME = Pattern.compile("^([a-zA-Z][a-zA-Z0-9+.\\-]*):");
    private static final Pattern RASTER_DATA_URL =
            Pattern.compile("^data:image/(png|jpe?g|gif|webp);", Pattern.CASE_INSENSITIVE);
    private static final Pattern CONTROL_CHARACTERS = Pattern.compile("[\\x00-\\x1f\\x7f]");

    private ImageUrlPolicy() {
    }

    public static boolean isSafe(String url) {
        if (url == null || url.isBlank()) {
            return true;
        }

        String trimmed = url.strip();

        if (CONTROL_CHARACTERS.matcher(trimmed).find()) {
            return false;
        }

        if (RASTER_DATA_URL.matcher(trimmed).find()) {
            return true;
        }

        var scheme = SCHEME.matcher(trimmed);

        if (!scheme.find()) {
            return true;
        }

        String name = scheme.group(1);

        return name.equalsIgnoreCase("http") || name.equalsIgnoreCase("https");
    }

    public static void requireSafe(String url) {
        if (!isSafe(url)) {
            throw new IllegalArgumentException("Image URL must use http or https");
        }
    }
}
