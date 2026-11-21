// Uses todo-lists.html and posts.html in headless Chrome as an owner and as a visitor.
// Started by PrivacySettingsRegressionTest; talks only to 127.0.0.1.
//
// Usage: node privacy-content-check.mjs <chrome> <fixtures.json> <results.json>
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

const TODO_STATE = `(() => {
    const text = (el) => (el?.textContent || "").trim().replace(/\\s+/g, " ");
    const visible = (el) => !!el && el.offsetParent !== null && getComputedStyle(el).display !== "none";
    const cards = [...document.querySelectorAll("#todoLists .todo-list")];
    return {
        titles: cards.map((card) => text(card.querySelector(".todo-list__title"))),
        badges: cards.map((card) => text(card.querySelector(".visibility-badge"))),
        tasks: [...document.querySelectorAll("#todoLists .todo-task__text")].map(text),
        checked: [...document.querySelectorAll("#todoLists .todo-task input[type=checkbox]")].map((box) => box.checked),
        checkboxesDisabled: [...document.querySelectorAll("#todoLists .todo-task input[type=checkbox]")].every((box) => box.disabled),
        controls: [...document.querySelectorAll("#todoLists [data-action], #newListForm")].filter(visible).length,
        confirmVisible: [...document.querySelectorAll("#todoLists .confirm")].some(visible),
        empty: visible(document.getElementById("todoEmpty")) ? text(document.getElementById("todoEmpty")) : "",
        injectedImages: document.querySelectorAll("#todoLists img").length
    };
})()`;

const POST_STATE = `(() => {
    const text = (el) => (el?.textContent || "").trim().replace(/\\s+/g, " ");
    const visible = (el) => !!el && el.offsetParent !== null && getComputedStyle(el).display !== "none";
    const cards = [...document.querySelectorAll("#posts .post")];
    return {
        texts: cards.map((card) => text(card.querySelector(".post__text"))),
        badges: cards.map((card) => text(card.querySelector(".visibility-badge"))),
        images: document.querySelectorAll("#posts .post__image img").length,
        imagesLoaded: [...document.querySelectorAll("#posts .post__image img")].filter((img) => img.complete && img.naturalWidth > 0).length,
        imageSources: [...document.querySelectorAll("#posts .post__image img")].map((img) => img.src),
        hint: text(document.querySelector("#newPostForm .mc-hint")),
        controls: [...document.querySelectorAll("#posts [data-action], #newPostForm")].filter(visible).length,
        confirmVisible: [...document.querySelectorAll("#posts .confirm")].some(visible),
        empty: visible(document.getElementById("postsEmpty")) ? text(document.getElementById("postsEmpty")) : "",
        injectedImages: document.querySelectorAll("#posts .post__text img").length
    };
})()`;

// Presses the first visible, enabled control with this data-action inside the given card.
const act = (container, card, action) => `(async () => {
    const cards = document.querySelectorAll(${JSON.stringify(container)});
    const button = cards[${card}] && [...cards[${card}].querySelectorAll('[data-action="${action}"]')]
        .find((el) => !el.disabled && el.offsetParent !== null);
    if (!button) {
        return false;
    }
    button.click();
    ${wait(900)}
    return true;
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

async function open(client, {token, path, width = 1280, theme = "original"}) {
    const {targetId} = await client.send("Target.createTarget", {url: "about:blank"});
    const {sessionId} = await client.send("Target.attachToTarget", {targetId, flatten: true});

    await client.send("Page.enable", {}, sessionId);
    await client.send("Runtime.enable", {}, sessionId);
    await client.send("Emulation.setDeviceMetricsOverride", {
        width, height: width < 600 ? 844 : 900, deviceScaleFactor: width < 600 ? 2 : 1, mobile: width < 600
    }, sessionId);
    await client.send("Page.addScriptToEvaluateOnNewDocument", {
        source: `localStorage.setItem("token", ${JSON.stringify(token)});
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

const profile = mkdtempSync(join(tmpdir(), "cml-privacy-"));
const {chrome, endpoint} = await startChrome(profile);

try {
    const client = await connect(endpoint);
    const results = {ids: {owner: fx.ownerId}};

    // ---- a new user's to-do lists ----
    const todo = await open(client, {token: fx.newcomerToken, path: "todo-lists.html"});
    results.todoEmpty = await todo.evaluate(TODO_STATE);
    await todo.evaluate(`(async () => {
        document.getElementById("newListTitle").value = "Errands";
        document.getElementById("newListForm").requestSubmit();
        ${wait(1000)}
    })()`);
    results.todoCreated = await todo.evaluate(TODO_STATE);
    await todo.evaluate(`(async () => {
        const form = document.querySelector("#todoLists .add-task-form");
        form.querySelector("input[name=text]").value = "Buy stamps";
        form.requestSubmit();
        ${wait(1000)}
    })()`);
    await todo.evaluate(`(async () => {
        document.querySelector("#todoLists .todo-task input[type=checkbox]").click();
        ${wait(1000)}
    })()`);
    await todo.evaluate(act("#todoLists .todo-list", 0, "toggle-visibility"));
    await todo.reload();
    results.todoReloaded = await todo.evaluate(TODO_STATE);
    await todo.snap("todo-owner");
    await todo.evaluate(act("#todoLists .todo-list", 0, "delete"));
    results.todoDeleteAsked = await todo.evaluate(TODO_STATE);
    await todo.evaluate(act("#todoLists .todo-list", 0, "confirm-delete"));
    results.todoDeleted = await todo.evaluate(TODO_STATE);
    await todo.close();

    // ---- a new user's posts ----
    const posts = await open(client, {token: fx.newcomerToken, path: "posts.html"});
    results.postsEmpty = await posts.evaluate(POST_STATE);
    await posts.evaluate(`(async () => {
        document.getElementById("newPostText").value = "Coffee first";
        document.getElementById("newPostForm").requestSubmit();
        ${wait(1200)}
    })()`);
    results.postCreated = await posts.evaluate(POST_STATE);
    await posts.evaluate(act("#posts .post", 0, "edit"));
    await posts.evaluate(`(async () => {
        const editor = document.querySelector("#posts .post textarea[name=text]");
        editor.value = "Coffee first, then work";
        document.querySelector("#posts .post [data-action=save]").click();
        ${wait(1200)}
    })()`);
    await posts.evaluate(act("#posts .post", 0, "toggle-visibility"));
    await posts.reload();
    results.postReloaded = await posts.evaluate(POST_STATE);
    await posts.snap("posts-owner");
    await posts.evaluate(act("#posts .post", 0, "delete"));
    results.postDeleteAsked = await posts.evaluate(POST_STATE);
    await posts.evaluate(act("#posts .post", 0, "confirm-delete"));
    results.postDeleted = await posts.evaluate(POST_STATE);
    await posts.close();

    // ---- a visitor sees only public content, read-only ----
    const strangerLists = await open(client, {token: fx.strangerToken, path: `todo-lists.html?user=${fx.ownerId}`});
    results.strangerLists = await strangerLists.evaluate(TODO_STATE);
    await strangerLists.snap("todo-visitor");
    await strangerLists.close();

    const strangerPosts = await open(client, {token: fx.strangerToken, path: `posts.html?user=${fx.ownerId}`});
    results.strangerPosts = await strangerPosts.evaluate(POST_STATE);
    results.xss = await strangerPosts.evaluate("window.__xss === 1");
    await strangerPosts.snap("posts-visitor");
    await strangerPosts.close();

    // ---- the ways in ----
    const own = await open(client, {token: fx.ownerToken, path: "profile.html"});
    results.profileLinks = await own.evaluate(`({
        todo: document.getElementById("todoListsNav")?.href || "",
        posts: document.getElementById("postsNav")?.href || ""
    })`);
    await own.close();

    const other = await open(client, {token: fx.strangerToken, path: `user-profile.html?id=${fx.ownerId}`});
    results.userProfileLinks = await other.evaluate(`({
        todo: document.getElementById("publicTodoListsLink")?.href || "",
        posts: document.getElementById("publicPostsLink")?.href || ""
    })`);
    await other.close();

    // ---- designs and a 390 px phone ----
    const todoText = "#todoLists .todo-list__title, #todoLists .todo-task__text, #todoLists .visibility-badge, #todoLists button, .page-head h1, .page-head p";
    const postText = "#posts .post__text, #posts .visibility-badge, #posts button, #posts .post__meta, .page-head h1, .page-head p";
    const layouts = {
        todoOriginalPhone: {token: fx.ownerToken, path: "todo-lists.html", width: 390, theme: "original", check: todoText},
        todoAutumnPhone: {token: fx.ownerToken, path: "todo-lists.html", width: 390, theme: "autumn", check: todoText},
        todoAutumnDesktop: {token: fx.ownerToken, path: "todo-lists.html", width: 1280, theme: "autumn", check: todoText},
        postsOriginalPhone: {token: fx.ownerToken, path: "posts.html", width: 390, theme: "original", check: postText},
        postsAutumnPhone: {token: fx.ownerToken, path: "posts.html", width: 390, theme: "autumn", check: postText},
        postsAutumnDesktop: {token: fx.ownerToken, path: "posts.html", width: 1280, theme: "autumn", check: postText}
    };
    for (const [name, options] of Object.entries(layouts)) {
        const view = await open(client, options);
        results[name] = await view.evaluate(LAYOUT(options.check));
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
