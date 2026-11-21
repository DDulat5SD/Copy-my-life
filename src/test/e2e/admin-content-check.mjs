// US-25: drives the admin portal in headless Chrome. The administrator edits, hides and unhides a
// routine, edits, hides and removes a post, edits, hides and clears a profile and manages the
// categories; then the public pages are read to see that they follow. Finally the admin pages are
// measured in both designs from a 360 px phone to a 1920 px desktop.
// Started by AdminContentManagementRegressionTest; talks only to 127.0.0.1.
//
// Usage: node admin-content-check.mjs <chrome> <fixtures.json> <results.json>
import {spawn} from "node:child_process";
import {mkdtempSync, readFileSync, rmSync, writeFileSync} from "node:fs";
import {tmpdir} from "node:os";
import {join} from "node:path";

const [chromePath, fixturesPath, resultsPath] = process.argv.slice(2);
const fx = JSON.parse(readFileSync(fixturesPath, "utf8"));

if (!/^http:\/\/127\.0\.0\.1:\d+$/.test(fx.base)) {
    throw new Error("Refusing to run against a non-local target");
}

const pause = (ms) => new Promise((done) => setTimeout(done, ms));
const dialogs = [];
const errors = [];
// Optional: set ADMIN_CONTENT_SCREENSHOTS to a folder to keep a picture of each page.
const screenshots = process.env.ADMIN_CONTENT_SCREENSHOTS;

const HELPERS = `
    window.__t = {
        wait: (ms) => new Promise((done) => setTimeout(done, ms)),
        text: (el) => (el?.textContent || "").trim().replace(/\\s+/g, " "),
        visible: (el) => !!el && !el.hidden && el.offsetParent !== null,
        // Waits until a selector shows up, for pages that load their data first.
        until: async (selector, ms = 6000) => {
            for (let waited = 0; waited < ms; waited += 100) {
                const found = document.querySelector(selector);
                if (found) return found;
                await window.__t.wait(100);
            }
            return null;
        },
        status: async (path, token) => (await fetch(path, token ? {headers: {Authorization: "Bearer " + token}} : {})).status,
        read: async (path, token) => (await fetch(path, token ? {headers: {Authorization: "Bearer " + token}} : {})).json(),
        set: (field, value) => {
            field.value = value;
            field.dispatchEvent(new Event("input", {bubbles: true}));
            field.dispatchEvent(new Event("change", {bubbles: true}));
        },
        // Fills the open admin form and saves it.
        fill: async (values) => {
            const form = await window.__t.until(".admin-edit-modal form");
            for (const [name, value] of Object.entries(values)) {
                window.__t.set(form.elements.namedItem(name), value);
            }
            form.querySelector("[type=submit]").click();
            await window.__t.wait(1200);
        },
        confirm: async () => {
            (await window.__t.until(".admin-confirm-submit")).click();
            await window.__t.wait(1200);
        },
        act: async (row, action) => {
            const button = (await window.__t.until(row)).querySelector("[data-action='" + action + "']");
            button.click();
            await window.__t.wait(300);
        }
    };
`;

const LAYOUT = `(() => {
    const parse = (value) => {
        const found = value.match(/rgba?\\(([^)]+)\\)/);
        if (!found) return null;
        const parts = found[1].split(/[\\s,\\/]+/).filter(Boolean).map(Number);
        return {r: parts[0], g: parts[1], b: parts[2], a: parts.length > 3 ? parts[3] : 1};
    };
    const channel = (v) => { v /= 255; return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4); };
    const luminance = (c) => 0.2126 * channel(c.r) + 0.7152 * channel(c.g) + 0.0722 * channel(c.b);
    const background = (el) => {
        for (let node = el; node; node = node.parentElement) {
            const color = parse(getComputedStyle(node).backgroundColor);
            if (color && color.a > 0.5) return color;
        }
        return {r: 255, g: 255, b: 255, a: 1};
    };
    const contrast = (el) => {
        const a = luminance(parse(getComputedStyle(el).color));
        const b = luminance(background(el));
        return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
    };
    const visible = (el) => !!el && !el.hidden && el.offsetParent !== null;
    const content = document.getElementById("pageContent");
    // Tables scroll inside their own frame; what is in them may be wider than the phone.
    const parts = [...content.querySelectorAll("*")].filter(visible).filter((el) => !el.closest(".table-wrap"));
    const outside = parts.filter((el) => {
        const box = el.getBoundingClientRect();
        return box.width > 0 && (box.left < -1 || box.right > innerWidth + 1);
    });
    const texts = [...content.querySelectorAll("h2, p, label, td, th, button, .tag, a")]
        .filter(visible).filter((el) => [...el.childNodes].some((n) => n.nodeType === 3 && n.textContent.trim()));
    const faintest = texts.length ? texts.reduce((a, b) => contrast(a) <= contrast(b) ? a : b) : null;
    return {
        width: innerWidth,
        theme: document.documentElement.getAttribute("data-theme") || localStorage.getItem("copymylife-theme") || "",
        overflow: document.documentElement.scrollWidth - innerWidth,
        inside: outside.length === 0,
        outside: outside.slice(0, 3).map((el) => el.className || el.tagName).join(", "),
        measured: texts.length,
        minContrast: faintest ? Math.round(contrast(faintest) * 100) / 100 : 0,
        faintest: faintest ? (faintest.className || faintest.tagName) + " " + faintest.textContent.trim().slice(0, 20) : ""
    };
})()`;

function startChrome(profile) {
    return new Promise((resolve, reject) => {
        const chrome = spawn(chromePath, [
            "--headless=new", "--remote-debugging-port=0", `--user-data-dir=${profile}`,
            "--no-first-run", "--disable-extensions", "--disable-gpu",
            "--host-resolver-rules=MAP * ~NOTFOUND, EXCLUDE 127.0.0.1"
        ], {stdio: ["ignore", "ignore", "pipe"]});
        let output = "";
        chrome.stderr.on("data", (chunk) => {
            output += chunk;
            const found = output.match(/DevTools listening on (ws:\/\/\S+)/);
            if (found) resolve({chrome, endpoint: found[1]});
        });
        chrome.on("error", reject);
        setTimeout(() => reject(new Error("Chrome did not start")), 30000);
    });
}

function connect(endpoint) {
    return new Promise((resolve, reject) => {
        const socket = new WebSocket(endpoint);
        const pending = new Map();
        let nextId = 1;
        const send = (method, params = {}, sessionId) => new Promise((ok, fail) => {
            const id = nextId++;
            pending.set(id, {ok, fail});
            socket.send(JSON.stringify({id, method, params, sessionId}));
        });
        socket.onerror = reject;
        socket.onmessage = (event) => {
            const message = JSON.parse(event.data);
            if (message.method === "Page.javascriptDialogOpening") {
                dialogs.push(message.params.message);
                send("Page.handleJavaScriptDialog", {accept: true}, message.sessionId).catch(() => {});
                return;
            }
            if (message.method === "Runtime.exceptionThrown") {
                errors.push(message.params.exceptionDetails.exception?.description || message.params.exceptionDetails.text);
                return;
            }
            if (message.id && pending.has(message.id)) {
                const {ok, fail} = pending.get(message.id);
                pending.delete(message.id);
                message.error ? fail(new Error(message.error.message)) : ok(message.result);
            }
        };
        socket.onopen = () => resolve({send, close: () => socket.close()});
    });
}

// Opens a page with the admin token (admin pages) or a user token (the site).
async function open(client, path, {admin = false, token = null, width = 1280, theme = "original"} = {}) {
    const {targetId} = await client.send("Target.createTarget", {url: "about:blank"});
    const {sessionId} = await client.send("Target.attachToTarget", {targetId, flatten: true});
    await client.send("Page.enable", {}, sessionId);
    await client.send("Runtime.enable", {}, sessionId);
    await client.send("Emulation.setDeviceMetricsOverride", {
        width, height: width < 600 ? 844 : 900, deviceScaleFactor: width < 600 ? 2 : 1, mobile: width < 600
    }, sessionId);
    await client.send("Page.addScriptToEvaluateOnNewDocument", {
        source: (admin ? `sessionStorage.setItem("adminToken", ${JSON.stringify(fx.adminToken)});` : "")
            + (token ? `localStorage.setItem("token", ${JSON.stringify(token)});` : `localStorage.removeItem("token");`)
            + `localStorage.setItem("copymylife-theme", ${JSON.stringify(theme)});`
            + HELPERS
    }, sessionId);
    await client.send("Page.navigate", {url: fx.base + path}, sessionId);
    await pause(2500);
    return {
        evaluate: async (expression) => (await client.send("Runtime.evaluate", {
            expression, returnByValue: true, awaitPromise: true
        }, sessionId)).result.value,
        snap: async (name) => {
            if (!screenshots) return;
            const {data} = await client.send("Page.captureScreenshot", {format: "png"}, sessionId);
            writeFileSync(join(screenshots, `${name}.png`), Buffer.from(data, "base64"));
        },
        close: () => client.send("Target.closeTarget", {targetId})
    };
}

const adminJson = (method, path, body) => `(async () => {
    const response = await fetch(${JSON.stringify(path)}, {
        method: ${JSON.stringify(method)},
        headers: {Authorization: "Bearer " + ${JSON.stringify(fx.adminToken)}, "Content-Type": "application/json"},
        body: ${body ? JSON.stringify(JSON.stringify(body)) : "undefined"}
    });
    return response.status === 204 ? {} : await response.json();
})()`;

const profile = mkdtempSync(join(tmpdir(), "cml-admin-"));
const {chrome, endpoint} = await startChrome(profile);

try {
    const client = await connect(endpoint);
    const results = {};

    // Routines: edit, hide and unhide from the table.
    const routines = await open(client, "/admin-routines.html", {admin: true});
    const row = `tr[data-routine='${fx.routine}']`;
    results.routines = (await routines.evaluate(`(async () => {
        const t = window.__t;
        await t.act(${JSON.stringify(row)}, "edit");
        const select = (await t.until(".admin-edit-modal select[name=category]"));
        const categoryOptions = select ? [...select.options].map((o) => o.value) : [];
        await t.fill({title: "Edited in the browser", description: "From the admin portal", category: "Fitness"});
        const shown = await t.read("/routines/${fx.routine}");
        await t.act(${JSON.stringify(row)}, "hide");
        await t.confirm();
        const statusAfterHide = t.text(document.querySelector(${JSON.stringify(row)} + " .routine-status"));
        const publicAfterHide = await t.status("/routines/${fx.routine}");
        await t.act(${JSON.stringify(row)}, "unhide");
        await t.confirm();
        return {
            categoryOptions,
            editSaved: t.text(document.querySelector(${JSON.stringify(row)})).includes("Edited in the browser"),
            publicTitle: shown.title,
            statusAfterHide,
            publicAfterHide,
            publicAfterUnhide: await t.status("/routines/${fx.routine}")
        };
    })()`)) || {};
    await routines.snap("routines");
    await routines.close();

    // Posts: listed, edited, hidden, removed.
    const posts = await open(client, "/admin-posts.html", {admin: true});
    const postRow = `tr[data-post='${fx.post}']`;
    results.posts = (await posts.evaluate(`(async () => {
        const t = window.__t;
        const listed = !!(await t.until(${JSON.stringify(postRow)}));
        const xss = window.__xss === 1;
        await t.act(${JSON.stringify(postRow)}, "edit");
        await t.fill({text: "Fixed in the browser"});
        const textAfterEdit = (await t.read("/posts/${fx.post}", ${JSON.stringify(fx.readerToken)})).text;
        await t.act(${JSON.stringify(postRow)}, "hide");
        await t.confirm();
        const readerAfterHide = await t.status("/posts/${fx.post}", ${JSON.stringify(fx.readerToken)});
        await t.act(${JSON.stringify(postRow)}, "delete");
        await t.confirm();
        return {
            listed, xss, textAfterEdit, readerAfterHide,
            ownerAfterRemove: await t.status("/posts/${fx.post}", ${JSON.stringify(fx.ownerToken)}),
            rowGone: !document.querySelector(${JSON.stringify(postRow)})
        };
    })()`)) || {};
    await posts.snap("posts");
    await posts.close();

    // A profile: edited, hidden and shown again, then cleared.
    const person = await open(client, `/admin-profile.html?id=${fx.person}`, {admin: true});
    results.profile = (await person.evaluate(`(async () => {
        const t = window.__t;
        (await t.until("#adminEditProfileBtn")).click();
        await t.fill({name: "Edited person", profession: "Baker", bio: "Bakes bread"});
        const publicName = (await t.read("/users/${fx.person}/profile")).name;
        (await t.until("#adminHideProfileBtn")).click();
        await t.confirm();
        const publicAfterHide = await t.status("/users/${fx.person}/profile");
        (await t.until("#adminHideProfileBtn")).click();
        await t.confirm();
        const publicAfterUnhide = await t.status("/users/${fx.person}/profile");
        (await t.until("#adminRemoveDetailsBtn")).click();
        await t.confirm();
        const after = await t.read("/users/${fx.person}/profile");
        return {publicName, publicAfterHide, publicAfterUnhide, bioAfterRemove: after.bio || ""};
    })()`)) || {};
    await person.snap("profile");
    await person.close();

    // Categories: created, renamed, one in use refused, then removed.
    const categories = await open(client, "/admin-categories.html", {admin: true});
    results.categories = (await categories.evaluate(`(async () => {
        const t = window.__t;
        const names = () => [...document.querySelectorAll("#categoriesTable .category-name")].map((el) => t.text(el));
        t.set(await t.until("#categoryName"), ${JSON.stringify(fx.category)});
        document.querySelector("#categoryForm [type=submit]").click();
        await t.wait(1200);
        const created = names().includes(${JSON.stringify(fx.category)});
        const id = [...document.querySelectorAll("#categoriesTable tr[data-category]")]
            .find((tr) => t.text(tr.querySelector(".category-name")) === ${JSON.stringify(fx.category)})?.dataset.category;
        await t.act("tr[data-category='" + id + "']", "rename");
        await t.fill({name: ${JSON.stringify(fx.category + " renamed")}});
        const renamed = names().includes(${JSON.stringify(fx.category + " renamed")});
        await t.act("tr[data-category='${fx.busyCategory}']", "delete");
        await t.confirm();
        const inUseMessage = t.text(document.getElementById("notice"));
        await t.act("tr[data-category='" + id + "']", "delete");
        await t.confirm();
        return {created, renamed, inUseMessage, removed: !names().includes(${JSON.stringify(fx.category + " renamed")})};
    })()`)) || {};
    await categories.snap("categories");
    await categories.close();

    // The public pages follow: a new category on Explore and in the form, hidden content marked for its owner.
    const setup = await open(client, "/admin-dashboard.html", {admin: true});
    await setup.evaluate(adminJson("POST", "/admin-api/categories", {name: fx.chipCategory}));
    await setup.evaluate(`(async () => {
        const form = new FormData();
        form.append("title", "Chip routine");
        form.append("description", "Filed under the new category");
        form.append("category", ${JSON.stringify(fx.chipCategory)});
        form.append("durationMinutes", "20");
        form.append("isPublic", "true");
        await fetch("/routines", {method: "POST", headers: {Authorization: "Bearer " + ${JSON.stringify(fx.ownerToken)}}, body: form});
        return true;
    })()`);
    await setup.evaluate(adminJson("PUT", `/admin-api/routines/${fx.hiddenRoutine}/hide`));
    await setup.evaluate(adminJson("PUT", `/admin-api/posts/${fx.hiddenPost}/hide`));
    await setup.close();

    results.public = {};
    const chip = fx.chipCategory.toLowerCase();
    const explore = await open(client, "/Explore.html");
    Object.assign(results.public, (await explore.evaluate(`(async () => {
        const t = window.__t;
        const button = await t.until('.chip[data-filter=${JSON.stringify(chip)}]');
        if (!button) return {exploreHasNewChip: false};
        button.click();
        await t.wait(1500);
        return {
            exploreHasNewChip: true,
            newChipShowsItsRoutine: t.text(document.getElementById("viewAllGrid")).includes("Chip routine")
        };
    })()`)) || {});
    await explore.snap("explore-new-chip");
    await explore.close();

    const form = await open(client, "/profile.html", {token: fx.ownerToken});
    results.public.createFormOffersNew = await form.evaluate(`(async () => {
        await window.__t.wait(1000);
        return [...document.querySelectorAll("#routineCategory option")].some((o) => o.value === ${JSON.stringify(fx.chipCategory)});
    })()`);
    await form.close();

    const hiddenRoutine = await open(client, `/routine.html?id=${fx.hiddenRoutine}`, {token: fx.ownerToken});
    results.public.ownerBadge = await hiddenRoutine.evaluate(`window.__t.visible(document.getElementById("routineHiddenBadge"))
        ? window.__t.text(document.getElementById("routineHiddenBadge")) : ""`);
    await hiddenRoutine.snap("owner-hidden-routine");
    await hiddenRoutine.close();

    const ownPosts = await open(client, "/profile.html#posts", {token: fx.ownerToken});
    results.public.ownerPostBadge = await ownPosts.evaluate(`(async () => {
        const badge = await window.__t.until(".post-hidden-badge");
        return badge ? window.__t.text(badge) : "";
    })()`);
    await ownPosts.snap("owner-hidden-post");
    await ownPosts.close();

    results.layouts = {};
    for (const page of ["routines", "posts", "categories"]) {
        for (const width of [360, 390, 1280, 1366, 1920]) {
            for (const theme of ["original", "autumn"]) {
                const view = await open(client, `/admin-${page}.html`, {admin: true, width, theme});
                await view.evaluate(`window.__t.until("#pageContent table, #pageContent .empty")`);
                results.layouts[`${page}-${theme}${width}`] = await view.evaluate(LAYOUT);
                await view.snap(`${page}-${theme}-${width}`);
                await view.close();
            }
        }
    }

    results.dialogs = dialogs;
    results.errors = errors;
    writeFileSync(resultsPath, JSON.stringify(results, null, 1));
    client.close();
} finally {
    chrome.kill();
    await pause(500);
    try {
        rmSync(profile, {recursive: true, force: true});
    } catch (error) {
        // Chrome can hold the profile briefly on Windows; it is only a temp folder.
    }
}
