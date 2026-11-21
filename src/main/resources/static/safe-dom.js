// Output encoding for values that come from users. Pick the helper that matches where
// the value ends up: HTML text or attribute, an image URL, or a CSS background.
(function () {

    const HTML_ESCAPES = {
        "&": "&amp;",
        "<": "&lt;",
        ">": "&gt;",
        '"': "&quot;",
        "'": "&#39;"
    };

    const RASTER_DATA_URL = /^data:image\/(png|jpe?g|gif|webp);/i;
    const CONTROL_CHARACTERS = /[\u0000-\u001f\u007f]/;

    function escapeHtml(value) {
        return String(value ?? "").replace(/[&<>"']/g, (char) => HTML_ESCAPES[char]);
    }

    // Keep in step with ImageUrlPolicy on the server. Returns "" for anything unsafe.
    function safeImageUrl(value) {
        const url = String(value ?? "").trim();

        if (!url || CONTROL_CHARACTERS.test(url)) {
            return "";
        }

        if (RASTER_DATA_URL.test(url)) {
            return url;
        }

        try {
            const protocol = new URL(url, "https://relative.invalid/").protocol;
            return protocol === "http:" || protocol === "https:" ? url : "";
        } catch (error) {
            return "";
        }
    }

    // For src="..." inside an HTML template.
    function imageSrc(value) {
        return escapeHtml(safeImageUrl(value));
    }

    // For style.backgroundImage. Inside a style="..." template, also pass it through escapeHtml.
    function cssImageUrl(value) {
        const url = safeImageUrl(value);

        if (!url) {
            return "";
        }

        return `url("${url.replace(/["\\]/g, (char) => encodeURIComponent(char))}")`;
    }

    window.SafeDom = Object.freeze({
        escapeHtml,
        safeImageUrl,
        imageSrc,
        cssImageUrl
    });
})();
