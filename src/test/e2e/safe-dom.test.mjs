// Contract test for safe-dom.js. Started by SafeDomContractTest, or run with: node safe-dom.test.mjs
import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import {dirname, join} from "node:path";
import {fileURLToPath} from "node:url";
import vm from "node:vm";

const root = dirname(fileURLToPath(import.meta.url));
const sandbox = {window: {}, URL, encodeURIComponent};
vm.runInNewContext(readFileSync(join(root, "../../main/resources/static/safe-dom.js"), "utf8"), sandbox);
const {escapeHtml, safeImageUrl, imageSrc, cssImageUrl} = sandbox.window.SafeDom;

// HTML text and attribute contexts
assert.equal(escapeHtml(`<img src=x onerror="a('b')">&`), "&lt;img src=x onerror=&quot;a(&#39;b&#39;)&quot;&gt;&amp;");
assert.equal(escapeHtml(null), "");
assert.equal(escapeHtml(undefined), "");
assert.equal(escapeHtml(0), "0");
assert.equal(escapeHtml("Plain text"), "Plain text");

// URLs refused
for (const url of [
    "javascript:alert(1)",
    "JAVASCRIPT:alert(1)",
    " javascript:alert(1)",
    "java\tscript:alert(1)",
    "java\nscript:alert(1)",
    "\u0001javascript:alert(1)",
    "vbscript:msgbox(1)",
    "data:text/html,<script>alert(1)</script>",
    "data:image/svg+xml,<svg onload=alert(1)>",
    "blob:https://example.com/x",
    "file:///etc/passwd",
    "ftp://example.com/a.png"
]) {
    assert.equal(safeImageUrl(url), "", url);
}

// URLs kept unchanged
for (const url of [
    "https://i.ibb.co/abc/photo.jpg",
    "http://example.com/a.png",
    "/uploads/routines/a.png",
    "img/halloween/01-billy.jpg",
    "//cdn.example.com/a.png",
    "data:image/png;base64,iVBORw0KGgo=",
    "data:image/jpeg;base64,AAAA"
]) {
    assert.equal(safeImageUrl(url), url, url);
}
assert.equal(safeImageUrl(null), "");
assert.equal(safeImageUrl(""), "");

// src attribute: an allowed URL still cannot break out
assert.equal(imageSrc(`https://x.test/a.png" onerror="alert(1)`), "https://x.test/a.png&quot; onerror=&quot;alert(1)");
assert.equal(imageSrc("javascript:alert(1)"), "");

// CSS context: the value cannot leave url("...")
assert.equal(cssImageUrl("https://x.test/a.png"), `url("https://x.test/a.png")`);
assert.equal(cssImageUrl(`https://x.test/a");}body{display:none`), `url("https://x.test/a%22);}body{display:none")`);
assert.equal(cssImageUrl("https://x.test/a\\b"), `url("https://x.test/a%5Cb")`);
assert.equal(cssImageUrl("javascript:alert(1)"), "");
assert.equal(cssImageUrl(null), "");

console.log("safe-dom contract OK");
