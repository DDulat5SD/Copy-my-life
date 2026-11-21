// Another user's profile in headless Chrome: Public Routines, Public To-do Lists and Public
// Posts in that order, View list and View post on the same page, and nothing private, in both
// designs and from a 360 px phone to a 1920 px desktop.
// Started by UserProfilePublicContentRegressionTest; talks only to 127.0.0.1.
//
// Usage: node user-profile-content-check.mjs <chrome> <fixtures.json> <results.json>
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
// Optional: set USER_PROFILE_SCREENSHOTS to a folder to keep a picture of each state for a visual check.
const screenshots = process.env.USER_PROFILE_SCREENSHOTS;

const HELPERS = `
    window.__p = {
        wait: (ms) => new Promise((done) => setTimeout(done, ms)),
        text: (el) => (el?.textContent || "").trim().replace(/\\s+/g, " "),
        visible: (el) => !!el && el.offsetParent !== null && getComputedStyle(el).display !== "none",
        click: async (selector, ms = 900) => {
            const el = document.querySelector(selector);
            if (el) el.click();
            await window.__p.wait(ms);
            return !!el;
        },
        requests: (pattern, method) => window.__requests.filter((r) => pattern.test(r.url) && r.method === method).length,
        parse(value) {
            const found = value.match(/rgba?\\(([^)]+)\\)/);
            if (!found) return null;
            const parts = found[1].split(/[\\s,\\/]+/).filter(Boolean).map(Number);
            return {r: parts[0], g: parts[1], b: parts[2], a: parts.length > 3 ? parts[3] : 1};
        },
        contrast(el) {
            const channel = (v) => { v /= 255; return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4); };
            const lum = (c) => 0.2126 * channel(c.r) + 0.7152 * channel(c.g) + 0.0722 * channel(c.b);
            let back = {r: 255, g: 255, b: 255};
            for (let node = el; node; node = node.parentElement) {
                const color = this.parse(getComputedStyle(node).backgroundColor);
                if (color && color.a > 0.5) { back = color; break; }
            }
            const a = lum(this.parse(getComputedStyle(el).color));
            const b = lum(back);
            return Math.round((Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05) * 100) / 100;
        }
    };
    true`;


function startChrome(profile) {
    return new Promise((resolve, reject) => {
        const chrome = spawn(chromePath, [
            "--headless=new",
            "--remote-debugging-port=0",
            `--user-data-dir=${profile}`,
            "--no-first-run",
            "--disable-extensions",
            "--disable-gpu",
            "--host-resolver-rules=MAP * ~NOTFOUND, EXCLUDE 127.0.0.1"
        ], {stdio: ["ignore", "ignore", "pipe"]});

        let output = "";
        chrome.stderr.on("data", (chunk) => {
            output += chunk;
            const found = output.match(/DevTools listening on (ws:\/\/\S+)/);
            if (found) {
                resolve({chrome, endpoint: found[1]});
            }
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
            // A blocking alert() would freeze the page; record it and dismiss it.
            if (message.method === "Page.javascriptDialogOpening") {
                dialogs.push(message.params.message);
                send("Page.handleJavaScriptDialog", {accept: true}, message.sessionId);
                return;
            }
            if (message.method === "Runtime.exceptionThrown") {
                errors.push(message.params.exceptionDetails.exception?.description
                    || message.params.exceptionDetails.text);
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

// token null opens the page as a visitor. The token is set once per tab, so signing out sticks.
async function open(client, {path, token = null, width = 1280, theme = "original", wait = 2200}) {
    const {targetId} = await client.send("Target.createTarget", {url: "about:blank"});
    const {sessionId} = await client.send("Target.attachToTarget", {targetId, flatten: true});

    await client.send("Page.enable", {}, sessionId);
    await client.send("Runtime.enable", {}, sessionId);
    await client.send("Emulation.setDeviceMetricsOverride", {
        width, height: width < 600 ? 844 : 900, deviceScaleFactor: width < 600 ? 2 : 1, mobile: width < 600
    }, sessionId);
    await client.send("Page.addScriptToEvaluateOnNewDocument", {
        source: `try {
                     if (!sessionStorage.getItem("checkStarted")) {
                         sessionStorage.setItem("checkStarted", "1");
                         ${token ? `localStorage.setItem("token", ${JSON.stringify(token)});` : `localStorage.removeItem("token");`}
                     }
                     localStorage.setItem("copymylife-theme", ${JSON.stringify(theme)});
                 } catch (error) {
                     // about:blank has no storage; the real pages do.
                 }
                 window.__requests = [];
                 const realFetch = window.fetch;
                 window.fetch = function (url, options) {
                     window.__requests.push({url: String(url), method: (options && options.method) || "GET"});
                     return realFetch.apply(this, arguments);
                 };`
    }, sessionId);

    const evaluate = async (expression) => {
        let reply;
        try {
            reply = await client.send("Runtime.evaluate", {expression, returnByValue: true, awaitPromise: true}, sessionId);
        } catch (error) {
            // The page left while it was being read.
            if (/navigated or closed|context was destroyed/.test(error.message)) {
                await pause(800);
                return null;
            }
            throw error;
        }
        // A page without the expected parts is reported, not fatal, so every check still runs.
        if (reply.exceptionDetails) {
            return {scriptError: reply.exceptionDetails.exception?.description || reply.exceptionDetails.text};
        }
        return reply.result.value;
    };
    const helpers = () => evaluate(HELPERS);
    const go = async (to, ms = wait) => {
        await client.send("Page.navigate", {url: `${fx.base}/${to}`}, sessionId);
        await pause(ms);
        await helpers();
    };
    await go(path);

    return {
        evaluate,
        go,
        helpers,
        reload: async (ms = wait) => {
            await client.send("Page.reload", {}, sessionId);
            await pause(ms);
            await helpers();
        },
        snap: async (name) => {
            if (!screenshots) {
                return;
            }
            const {data} = await client.send("Page.captureScreenshot", {format: "png", captureBeyondViewport: true}, sessionId);
            writeFileSync(join(screenshots, `${name}.png`), Buffer.from(data, "base64"));
        },
        close: () => client.send("Target.closeTarget", {targetId})
    };
}

const LAYOUT = `(() => {
    const p = window.__p;
    const top = (el) => el ? Math.round(el.getBoundingClientRect().top + scrollY) : -1;
    const cards = (grid) => [...document.querySelectorAll(grid + " .public-routine-card")];
    return {
        standaloneLinks: document.querySelectorAll('.user-profile-page a[href*="todo-lists.html"], .user-profile-page a[href*="posts.html"]').length,
        oldLinks: !!(document.getElementById("publicTodoListsLink") || document.getElementById("publicPostsLink")),
        headings: [...document.querySelectorAll(".public-routines-section .public-routines-header h2")].map((el) => p.text(el)),
        subtitles: [...document.querySelectorAll(".public-routines-section .public-routines-header p")].map((el) => p.text(el)),
        tops: [top(document.querySelector(".user-profile-card")), top(document.getElementById("publicRoutinesGrid")),
            top(document.getElementById("publicTodoSection")), top(document.getElementById("publicPostsSection"))],
        routines: cards("#publicRoutinesGrid").map((card) => p.text(card.querySelector(".public-routine-title"))),
        lists: cards("#publicTodoGrid").map((card) => ({
            title: p.text(card.querySelector(".public-routine-title")),
            count: p.text(card.querySelector(".public-routine-duration")),
            done: p.text(card.querySelector(".public-routine-description")),
            badge: p.text(card.querySelector(".public-routine-category")),
            viewButton: !!card.querySelector('.public-content-view[data-view="list"]')
        })),
        listTitles: cards("#publicTodoGrid").map((card) => p.text(card.querySelector(".public-routine-title"))),
        posts: cards("#publicPostsGrid").map((card) => {
            const img = card.querySelector("img[data-post-photo]");
            const excerpt = card.querySelector(".public-post-excerpt");
            return {
                hasPhoto: !!img,
                photoFromSafeEndpoint: !!img && img.src.startsWith("blob:"),
                excerptClamped: excerpt.scrollHeight > excerpt.clientHeight + 1,
                date: p.text(card.querySelector(".public-routine-duration")),
                likes: p.text(card.querySelector(".public-post-likes")),
                comments: p.text(card.querySelector(".public-post-comments")),
                viewButton: !!card.querySelector('.public-content-view[data-view="post"]')
            };
        }),
        pageText: document.body.innerText,
        uploadPath: document.body.innerHTML.includes("/uploads/"),
        xss: window.__xss === 1,
        injectedImages: document.querySelectorAll(".public-routine-title img").length
    };
})()`;

const SCREEN = `(() => {
    const p = window.__p;
    const de = document.documentElement;
    const tops = [".user-profile-card", "#publicRoutinesGrid", "#publicTodoSection", "#publicPostsSection"]
        .map((selector) => document.querySelector(selector).getBoundingClientRect().top);
    const texts = [...document.querySelectorAll(".public-routines-section :is(h2, .public-routines-header p, .public-routine-title, "
        + ".public-routine-description, .public-routine-link, .public-routine-category, .public-routine-duration, "
        + ".public-routine-rating, .public-routines-empty)")].filter((el) => p.visible(el) && p.text(el));
    return {
        scrollWidth: de.scrollWidth,
        clientWidth: de.clientWidth,
        ordered: tops.every((value, index) => index === 0 || value > tops[index - 1]),
        cardsOutside: [...document.querySelectorAll(".public-routine-card")].filter((card) => {
            const r = card.getBoundingClientRect();
            return r.left < -1 || r.right > de.clientWidth + 1;
        }).length,
        minContrast: texts.length ? Math.min(...texts.map((el) => p.contrast(el))) : 0
    };
})()`;

const profile = mkdtempSync(join(tmpdir(), "cml-user-profile-content-"));
const {chrome, endpoint} = await startChrome(profile);

try {
    const client = await connect(endpoint);
    const results = {};

    const guest = await open(client, {path: `user-profile.html?id=${fx.ownerId}`, wait: 2000});
    results.guest = await guest.evaluate(`({
        path: location.pathname,
        todo: document.getElementById("publicTodoGrid")?.textContent.trim() || "",
        posts: document.getElementById("publicPostsGrid")?.textContent.trim() || ""
    })`);
    await guest.close();

    const page = await open(client, {path: `user-profile.html?id=${fx.ownerId}`, token: fx.viewerToken, wait: 3200});
    results.layout = await page.evaluate(LAYOUT);
    await page.snap("layout");

    results.viewList = await page.evaluate(`(async () => {
        const p = window.__p;
        const button = [...document.querySelectorAll('.public-content-view[data-view="list"]')]
            .find((el) => el.dataset.title === "Morning plan");
        button.focus();
        button.click();
        await p.wait(1500);
        const detail = document.getElementById("publicDetail");
        const result = {
            open: !detail.hidden,
            title: p.text(document.getElementById("publicDetailTitle")),
            tasks: [...detail.querySelectorAll(".todo-task__text")].map((el) => p.text(el)),
            done: detail.querySelectorAll(".todo-task--done").length,
            ownerControls: detail.querySelectorAll('[data-action="delete"], [data-action="rename"], [data-action="delete-task"], .add-task-form').length,
            samePage: location.pathname.endsWith("/user-profile.html"),
            privateTextInDetail: /Hidden task|Secret plan/.test(detail.innerText)
        };
        document.dispatchEvent(new KeyboardEvent("keydown", {key: "Escape", bubbles: true}));
        await p.wait(500);
        result.closedByEscape = detail.hidden;
        result.focusAfterClose = p.text(document.activeElement);
        return result;
    })()`);
    await page.snap("view-list");

    results.viewPost = await page.evaluate(`(async () => {
        const p = window.__p;
        const card = () => [...document.querySelectorAll("#publicPostsGrid .public-post-card")].find((el) => el.querySelector("img"));
        const button = card().querySelector(".public-content-view");
        button.focus();
        button.click();
        await p.wait(2200);
        const detail = document.getElementById("publicDetail");
        const photo = detail.querySelector("img[data-photo]");
        const result = {
            open: !detail.hidden,
            text: p.text(detail.querySelector(".post__text")),
            photo: !!photo && photo.src.startsWith("blob:"),
            commentsShown: detail.querySelectorAll(".post-comment").length,
            ownerControls: detail.querySelectorAll('[data-action="edit"], [data-action="delete"], [data-action="toggle-visibility"]').length,
            samePage: location.pathname.endsWith("/user-profile.html")
        };
        detail.querySelector('[data-social="like"]').click();
        await p.wait(1300);
        result.likesAfterLike = p.text(detail.querySelector(".post-like__count"));
        const field = detail.querySelector(".post-comment-form [name=comment]");
        field.value = "Great walk";
        field.dispatchEvent(new Event("input", {bubbles: true}));
        detail.querySelector('.post-comment-form [type="submit"]').click();
        await p.wait(1300);
        result.commentsAfterComment = detail.querySelectorAll(".post-comment").length;
        document.getElementById("publicDetailClose").click();
        await p.wait(1500);
        result.cardLikesAfterClose = p.text(card().querySelector(".public-post-likes"));
        result.cardCommentsAfterClose = p.text(card().querySelector(".public-post-comments"));
        result.focusAfterClose = p.text(document.activeElement);
        return result;
    })()`);
    await page.snap("view-post");

    await page.reload(3200);
    results.afterReload = await page.evaluate(`(() => {
        const card = [...document.querySelectorAll("#publicPostsGrid .public-post-card")].find((el) => el.querySelector("img"));
        return {
            likes: window.__p.text(card.querySelector(".public-post-likes")),
            comments: window.__p.text(card.querySelector(".public-post-comments"))
        };
    })()`);

    await page.go(`user-profile.html?id=${fx.emptyId}`, 3000);
    results.empty = await page.evaluate(`(() => {
        const box = (id) => document.querySelector("#" + id + " .public-routines-empty");
        return {
            lists: window.__p.text(box("publicTodoGrid")),
            posts: window.__p.text(box("publicPostsGrid")),
            listsHeight: Math.round(box("publicTodoGrid").getBoundingClientRect().height),
            postsHeight: Math.round(box("publicPostsGrid").getBoundingClientRect().height)
        };
    })()`);
    await page.close();

    results.layouts = {};
    for (const theme of ["original", "autumn"]) {
        for (const width of [360, 390, 1280, 1366, 1536, 1920]) {
            const view = await open(client, {path: `user-profile.html?id=${fx.ownerId}`, token: fx.viewerToken, width, theme, wait: 3200});
            results.layouts[`${theme}_${width}`] = await view.evaluate(SCREEN);
            await view.snap(`${theme}-${width}`);
            await view.close();
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
