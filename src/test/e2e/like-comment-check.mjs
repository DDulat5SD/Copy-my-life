// Likes and comments (US-15) in headless Chrome: on posts.html as a fan, the post's owner and
// someone without a session, and on routine.html as a fan and a visitor.
// Started by LikeAndCommentRegressionTest; talks only to 127.0.0.1.
//
// Usage: node like-comment-check.mjs <chrome> <fixtures.json> <results.json>
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
// Optional: set TRACKING_SCREENSHOTS to a folder to keep a picture of each state for a visual check.
const screenshots = process.env.TRACKING_SCREENSHOTS;
const wait = (ms) => `await new Promise((done) => setTimeout(done, ${ms}));`;

const POST_STATE = `(() => {
    const text = (el) => (el?.textContent || "").trim().replace(/\\s+/g, " ");
    const visible = (el) => !!el && el.offsetParent !== null && getComputedStyle(el).display !== "none";
    const card = document.querySelector("#posts .post");
    const like = card?.querySelector("[data-social=like]");
    const error = card?.querySelector(".post-comment-error");
    return {
        likeCount: Number(text(like?.querySelector(".post-like__count"))),
        pressed: like?.getAttribute("aria-pressed") === "true",
        label: text(like?.querySelector(".post-like__label")),
        count: Number(text(card?.querySelector(".post-comments__count"))),
        comments: [...(card?.querySelectorAll(".post-comment") || [])].map((item) => ({
            author: text(item.querySelector(".post-comment__author")),
            text: text(item.querySelector(".post-comment__text"))
        })),
        empty: visible(card?.querySelector(".post-comments__empty")) ? text(card.querySelector(".post-comments__empty")) : "",
        error: visible(error) ? text(error) : "",
        draft: card?.querySelector(".post-comment-form [name=comment]")?.value ?? null,
        confirmVisible: [...(card?.querySelectorAll(".post-comments .confirm") || [])].some(visible),
        deleteButtons: card ? card.querySelectorAll("[data-social=delete-comment]").length : -1,
        injectedImages: document.querySelectorAll("#posts .post-comments img").length,
        message: text(document.getElementById("pageMessage"))
    };
})()`;

// Presses the first visible, enabled control with this data-social in the first post.
const press = (social) => `(async () => {
    const card = document.querySelector("#posts .post");
    const button = card && [...card.querySelectorAll('[data-social="${social}"]')]
        .find((el) => !el.disabled && el.offsetParent !== null);
    if (!button) {
        return false;
    }
    button.click();
    ${wait(900)}
    return true;
})()`;

const writeComment = (value) => `(async () => {
    const field = document.querySelector("#posts .post .post-comment-form [name=comment]");
    field.value = ${JSON.stringify(value)};
    field.dispatchEvent(new Event("input", {bubbles: true}));
    field.closest("form").requestSubmit();
    ${wait(1000)}
})()`;

const ROUTINE_STATE = `(() => {
    const text = (el) => (el?.textContent || "").trim().replace(/\\s+/g, " ");
    return {
        likes: text(document.getElementById("likesCount")),
        active: document.getElementById("likeBtn").classList.contains("is-active"),
        notice: document.getElementById("copyNotice").hidden ? "" : text(document.getElementById("copyNotice")),
        commentFormHidden: document.getElementById("commentForm").hidden,
        comments: [...document.querySelectorAll("#commentList .comment")].map((item) => ({
            author: text(item.querySelector(".comment__author")),
            text: text(item.querySelector(".comment__text"))
        }))
    };
})()`;

const LAYOUT = (selector) => `(() => {
    const parse = (value) => {
        const found = value.match(/rgba?\\(([^)]+)\\)/);
        if (!found) {
            return null;
        }
        const parts = found[1].split(/[\\s,\\/]+/).filter(Boolean).map(Number);
        return {r: parts[0], g: parts[1], b: parts[2], a: parts.length > 3 ? parts[3] : 1};
    };
    const channel = (v) => {
        v /= 255;
        return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    };
    const luminance = (c) => 0.2126 * channel(c.r) + 0.7152 * channel(c.g) + 0.0722 * channel(c.b);
    const background = (el) => {
        for (let node = el; node; node = node.parentElement) {
            const color = parse(getComputedStyle(node).backgroundColor);
            if (color && color.a > 0.5) {
                return color;
            }
        }
        return {r: 255, g: 255, b: 255, a: 1};
    };
    const contrast = (el) => {
        const a = luminance(parse(getComputedStyle(el).color));
        const b = luminance(background(el));
        return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
    };
    const visible = (el) => !!el && el.offsetParent !== null && getComputedStyle(el).display !== "none";
    const texts = [...document.querySelectorAll(${JSON.stringify(selector)})].filter(visible);
    const targets = [...document.querySelectorAll("main button, main input[type=checkbox] + span, main a")]
        .filter(visible).filter((el) => !el.disabled);
    const worst = texts.reduce((low, el) => (!low || contrast(el) < contrast(low) ? el : low), null);
    return {
        lowestContrast: worst ? worst.className + " " + getComputedStyle(worst).color : "",
        width: innerWidth,
        theme: document.documentElement.getAttribute("data-theme") || "",
        rendered: texts.length > 0,
        overflow: document.documentElement.scrollWidth - innerWidth,
        rightmost: Math.round(Math.max(0, ...targets.map((el) => el.getBoundingClientRect().right))),
        smallestTarget: Math.round(Math.min(...targets.filter((el) => el.tagName === "BUTTON").map((el) => el.getBoundingClientRect().height))),
        minContrast: Math.round(Math.min(...texts.map(contrast)) * 100) / 100
    };
})()`;

const LOCATION = `({path: location.pathname, search: location.search})`;

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

        socket.onerror = reject;
        socket.onmessage = (event) => {
            const message = JSON.parse(event.data);
            // A blocking alert() would freeze the page; record it and dismiss it.
            if (message.method === "Page.javascriptDialogOpening") {
                dialogs.push(message.params.message);
                socket.send(JSON.stringify({
                    id: nextId++, method: "Page.handleJavaScriptDialog",
                    params: {accept: true}, sessionId: message.sessionId
                }));
                return;
            }
            if (message.id && pending.has(message.id)) {
                const {ok, fail} = pending.get(message.id);
                pending.delete(message.id);
                message.error ? fail(new Error(message.error.message)) : ok(message.result);
            }
        };
        socket.onopen = () => resolve({
            send: (method, params = {}, sessionId) => new Promise((ok, fail) => {
                const id = nextId++;
                pending.set(id, {ok, fail});
                socket.send(JSON.stringify({id, method, params, sessionId}));
            }),
            close: () => socket.close()
        });
    });
}

// token null opens the page as a visitor without a session.
async function open(client, {token, path, width = 1280, theme = "original"}) {
    const {targetId} = await client.send("Target.createTarget", {url: "about:blank"});
    const {sessionId} = await client.send("Target.attachToTarget", {targetId, flatten: true});

    await client.send("Page.enable", {}, sessionId);
    await client.send("Runtime.enable", {}, sessionId);
    await client.send("Emulation.setDeviceMetricsOverride", {
        width, height: width < 600 ? 844 : 900, deviceScaleFactor: width < 600 ? 2 : 1, mobile: width < 600
    }, sessionId);
    await client.send("Page.addScriptToEvaluateOnNewDocument", {
        source: `${token ? `localStorage.setItem("token", ${JSON.stringify(token)});` : `localStorage.removeItem("token");`}
                 localStorage.setItem("copymylife-theme", ${JSON.stringify(theme)});`
    }, sessionId);
    await client.send("Page.navigate", {url: `${fx.base}/${path}`}, sessionId);
    await pause(1800);

    return {
        evaluate: async (expression) => (await client.send("Runtime.evaluate", {
            expression, returnByValue: true, awaitPromise: true
        }, sessionId)).result.value,
        reload: async () => {
            await client.send("Page.reload", {}, sessionId);
            await pause(1800);
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

const profile = mkdtempSync(join(tmpdir(), "cml-like-comment-"));
const {chrome, endpoint} = await startChrome(profile);
const ownersPosts = `posts.html?user=${fx.ownerId}`;

try {
    const client = await connect(endpoint);
    const results = {};

    // ---- a fan likes and comments on someone's public post ----
    const fan = await open(client, {token: fx.fanToken, path: ownersPosts});
    results.postStart = await fan.evaluate(POST_STATE);
    await fan.evaluate(press("like"));
    results.postLiked = await fan.evaluate(POST_STATE);
    await fan.evaluate(press("toggle-comments"));
    results.commentsEmpty = await fan.evaluate(POST_STATE);
    await fan.evaluate(writeComment("   "));
    results.commentRefused = await fan.evaluate(POST_STATE);
    await fan.evaluate(writeComment("Nice walk!"));
    await fan.evaluate(writeComment(fx.markup));
    results.commentAdded = await fan.evaluate(POST_STATE);
    results.xss = await fan.evaluate("window.__xss === 1");
    await fan.snap("post-comments-fan");

    await fan.reload();
    results.postReloaded = await fan.evaluate(POST_STATE);
    await fan.evaluate(press("toggle-comments"));
    results.commentsReloaded = await fan.evaluate(POST_STATE);
    await fan.evaluate(press("like"));
    results.postUnliked = await fan.evaluate(POST_STATE);

    await fan.evaluate(press("delete-comment"));
    results.commentDeleteAsked = await fan.evaluate(POST_STATE);
    await fan.evaluate(press("confirm-delete-comment"));
    results.commentDeleted = await fan.evaluate(POST_STATE);
    await fan.close();

    // ---- the post's owner reads the comment but cannot remove it ----
    const owner = await open(client, {token: fx.ownerToken, path: "posts.html"});
    await owner.evaluate(press("toggle-comments"));
    results.ownerView = await owner.evaluate(POST_STATE);
    await owner.snap("post-comments-owner");
    await owner.close();

    // ---- without a session ----
    const visitor = await open(client, {token: null, path: ownersPosts});
    results.visitorPosts = await visitor.evaluate(LOCATION);
    await visitor.close();

    const leaving = await open(client, {token: fx.leavingToken, path: ownersPosts});
    await leaving.evaluate(`fetch("/auth/logout", {method: "POST", headers: {"Authorization": "Bearer " + localStorage.getItem("token")}}).then(() => true)`);
    // The page navigates away, so the click is not awaited inside it.
    await leaving.evaluate(`document.querySelector('#posts .post [data-social="like"]').click(); true`);
    await pause(2000);
    results.sessionEndedLike = await leaving.evaluate(LOCATION);
    await leaving.close();

    // ---- routines ----
    const guest = await open(client, {token: null, path: `routine.html?id=${fx.routineId}`});
    await guest.evaluate(`(async () => { document.getElementById("likeBtn").click(); ${wait(600)} })()`);
    results.routineGuest = await guest.evaluate(ROUTINE_STATE);
    await guest.snap("routine-guest");
    await guest.close();

    const routineFan = await open(client, {token: fx.fanToken, path: `routine.html?id=${fx.routineId}`});
    await routineFan.evaluate(`(async () => { document.getElementById("likeBtn").click(); ${wait(900)} })()`);
    results.routineLiked = await routineFan.evaluate(ROUTINE_STATE);
    await routineFan.evaluate(`(async () => {
        const input = document.getElementById("commentInput");
        input.value = "Routine question?";
        input.dispatchEvent(new Event("input", {bubbles: true}));
        document.getElementById("commentForm").requestSubmit();
        ${wait(1200)}
    })()`);
    await routineFan.reload();
    results.routineReloaded = await routineFan.evaluate(ROUTINE_STATE);
    await routineFan.evaluate(`(async () => { document.getElementById("likeBtn").click(); ${wait(900)} })()`);
    results.routineUnliked = await routineFan.evaluate(ROUTINE_STATE);
    await routineFan.close();

    // ---- designs and a 390 px phone, with the comments open ----
    const socialText = "#posts .post-like, #posts .post-comments-toggle, #posts .post-comment__author, "
        + "#posts .post-comment__meta, #posts .post-comment__text, #posts .post-comment-form button, #posts .post-comment__delete";
    const layouts = {
        socialOriginalPhone: {width: 390, theme: "original"},
        socialAutumnPhone: {width: 390, theme: "autumn"},
        socialAutumnDesktop: {width: 1280, theme: "autumn"},
        socialOriginalDesktop: {width: 1280, theme: "original"}
    };
    for (const [name, options] of Object.entries(layouts)) {
        const view = await open(client, {token: fx.fanToken, path: ownersPosts, ...options});
        await view.evaluate(press("toggle-comments"));
        results[name] = await view.evaluate(LAYOUT(socialText));
        await view.snap(name);
        await view.close();
    }

    results.dialogs = dialogs;
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
