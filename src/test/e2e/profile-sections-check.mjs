// The profile's Followers tab and its To-do Lists and Posts sections, section addresses with
// Back, Forward and Refresh, and the site header on todo-lists.html and posts.html, in
// headless Chrome, in both designs and from a 360 px phone to a 1920 px desktop.
// Started by ProfileSectionsRegressionTest; talks only to 127.0.0.1.
//
// Usage: node profile-sections-check.mjs <chrome> <fixtures.json> <results.json>
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
// Optional: set PROFILE_SCREENSHOTS to a folder to keep a picture of each state for a visual check.
const screenshots = process.env.PROFILE_SCREENSHOTS;

const SECTIONS = ["calendarContent", "accountContent", "habitsContent", "progressContent", "friendsContent",
    "todoListsContent", "postsContent", "themeSettingsContent"];

const HELPERS = `
    window.__p = {
        wait: (ms) => new Promise((done) => setTimeout(done, ms)),
        text: (el) => (el?.textContent || "").trim().replace(/\\s+/g, " "),
        visible: (el) => !!el && el.offsetParent !== null && getComputedStyle(el).display !== "none",
        sections: () => ${JSON.stringify(SECTIONS)}.filter((id) => {
            const el = document.getElementById(id);
            return el && getComputedStyle(el).display !== "none";
        }),
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

const FRIENDS = `({
    tabs: [...document.querySelectorAll(".friends-tab")].map((tab) => window.__p.text(tab)),
    active: window.__p.text(document.querySelector(".friends-tab.active")),
    path: location.pathname,
    hash: location.hash,
    friendsVisible: window.__p.visible(document.getElementById("friendsContent")),
    names: [...document.querySelectorAll("#friendsList .friend-card-name")].map((el) => window.__p.text(el)),
    usernames: [...document.querySelectorAll("#friendsList .friend-card-username")].map((el) => window.__p.text(el)),
    links: [...document.querySelectorAll("#friendsList a.friend-view-btn")].map((a) => a.getAttribute("href")),
    avatars: document.querySelectorAll("#friendsList .friend-card-avatar img").length,
    followersCount: window.__p.text(document.getElementById("followersCount")),
    followingCount: window.__p.text(document.getElementById("followingCount")),
    button: window.__p.text(document.querySelector("#friendsList .follower-follow-btn")),
    empty: window.__p.text(document.querySelector("#friendsList .followers-empty"))
})`;

const SECTION = (content) => `({
    path: location.pathname,
    hash: location.hash,
    active: document.querySelector(".profile-nav-btn.active")?.id || "",
    sidebar: window.__p.visible(document.querySelector(".profile-navigation")),
    card: window.__p.visible(document.querySelector(".profile-card")),
    visibleSections: window.__p.sections(),
    heading: window.__p.text(document.querySelector("#${content} .friends-page-header h1")),
    iframes: document.querySelectorAll("iframe").length
})`;

const profile = mkdtempSync(join(tmpdir(), "cml-profile-sections-"));
const {chrome, endpoint} = await startChrome(profile);

try {
    const client = await connect(endpoint);
    const results = {};

    // ---- Followers ----
    const friends = await open(client, {path: "profile.html", token: fx.ownerToken, wait: 2800});
    await friends.evaluate(`window.__p.click("#friendsNav", 1200)`);
    await friends.evaluate(`window.__p.click('[data-friends-tab="followers"]', 1500)`);
    results.followersTab = {...await friends.evaluate(FRIENDS), fanUsername: fx.fanUsername};
    await friends.snap("followers");
    await friends.evaluate(`window.__p.click('[data-friends-tab="following"]', 1500)`);
    results.followingTab = await friends.evaluate(FRIENDS);

    await friends.evaluate(`window.__p.click('[data-friends-tab="followers"]', 1500)`);
    const before = await friends.evaluate(FRIENDS);
    await friends.evaluate(`window.__p.click(".follower-follow-btn", 1800)`);
    const after = await friends.evaluate(FRIENDS);
    await friends.reload(3200);
    const reloaded = await friends.evaluate(FRIENDS);
    await friends.evaluate(`window.__p.click(".follower-follow-btn", 1800)`);
    const undone = await friends.evaluate(FRIENDS);
    results.followBack = {
        followingBefore: Number(before.followingCount),
        followingAfter: Number(after.followingCount),
        buttonAfter: after.button,
        buttonAfterReload: reloaded.button,
        reloadedHash: reloaded.hash,
        followingAfterUnfollow: Number(undone.followingCount)
    };
    await friends.close();

    const lonely = await open(client, {path: "profile.html#followers", token: fx.lonelyToken, wait: 3200});
    const empty = await lonely.evaluate(FRIENDS);
    results.followersEmpty = {text: empty.empty, followersCount: empty.followersCount};
    await lonely.close();

    // ---- To-do Lists inside the profile ----
    const todo = await open(client, {path: "profile.html", token: fx.ownerToken, wait: 2800});
    await todo.evaluate(`window.__p.click("#todoListsNav", 1500)`);
    results.todoSection = await todo.evaluate(SECTION("todoListsContent"));
    await todo.snap("todo-section");
    // Reopening the section must not add a second set of handlers.
    for (const nav of ["#calendarNav", "#todoListsNav", "#postsNav", "#todoListsNav"]) {
        await todo.evaluate(`window.__p.click(${JSON.stringify(nav)}, 900)`);
    }
    results.todoCrud = await todo.evaluate(`(async () => {
        const p = window.__p;
        const root = document.getElementById("profileTodoApp");
        const part = (name) => root.querySelector('[data-part="' + name + '"]');
        window.__requests.length = 0;
        part("newListForm").querySelector('[type="submit"]').click();
        await p.wait(300);
        const emptyTitle = p.text(part("newListForm").querySelector(".mc-field-error"));
        part("newListTitle").value = "Weekend errands";
        part("newListForm").querySelector('[type="submit"]').click();
        await p.wait(1200);
        const id = root.querySelector(".todo-list").dataset.id;
        const card = () => root.querySelector('.todo-list[data-id="' + id + '"]');
        const created = p.text(card().querySelector(".todo-list__title"));
        const createRequests = p.requests(/\\/todo-lists$/, "POST");
        card().querySelector(".add-task-form input").value = "Buy milk";
        card().querySelector('.add-task-form [type="submit"]').click();
        await p.wait(1000);
        card().querySelector('[data-action="toggle-task"]').click();
        await p.wait(1000);
        const taskDone = !!card().querySelector(".todo-task--done");
        card().querySelector('[data-action="toggle-visibility"]').click();
        await p.wait(1000);
        const badge = p.text(card().querySelector(".visibility-badge"));
        card().querySelector('[data-action="rename"]').click();
        await p.wait(300);
        card().querySelector(".rename-form input").value = "Weekend plan";
        card().querySelector('.rename-form [type="submit"]').click();
        await p.wait(1000);
        sessionStorage.setItem("todoId", id);
        return {emptyTitle, created, createRequests, taskDone, badge, renamed: p.text(card().querySelector(".todo-list__title"))};
    })()`);
    await todo.reload(3200);
    Object.assign(results.todoCrud, await todo.evaluate(`(async () => {
        const p = window.__p;
        const root = document.getElementById("profileTodoApp");
        const id = sessionStorage.getItem("todoId");
        const card = () => root.querySelector('.todo-list[data-id="' + id + '"]');
        const afterReload = p.text(card()?.querySelector(".todo-list__title"));
        card().querySelector('[data-action="delete-task"]').click();
        await p.wait(1000);
        const tasksAfterRemove = card().querySelectorAll(".todo-task").length;
        card().querySelector('[data-action="delete"]').click();
        await p.wait(300);
        card().querySelector('[data-action="confirm-delete"]').click();
        await p.wait(1000);
        return {afterReload, tasksAfterRemove, listsAfterDelete: root.querySelectorAll('.todo-list[data-id="' + id + '"]').length};
    })()`));
    await todo.close();

    // ---- Posts inside the profile ----
    const posts = await open(client, {path: "profile.html", token: fx.ownerToken, wait: 2800});
    await posts.evaluate(`window.__p.click("#postsNav", 1800)`);
    results.postsSection = await posts.evaluate(SECTION("postsContent"));
    await posts.snap("posts-section");
    for (const nav of ["#friendsNav", "#postsNav", "#todoListsNav", "#postsNav"]) {
        await posts.evaluate(`window.__p.click(${JSON.stringify(nav)}, 900)`);
    }
    results.postsCrud = await posts.evaluate(`(async () => {
        const p = window.__p;
        const root = document.getElementById("profilePostsApp");
        const part = (name) => root.querySelector('[data-part="' + name + '"]');
        const photo = root.querySelector("img[data-photo]");
        const photoFromSafeEndpoint = !!photo && photo.src.startsWith("blob:");
        part("newPostText").value = "Morning run done";
        part("newPostForm").querySelector('[type="submit"]').click();
        await p.wait(1300);
        const id = root.querySelector(".post").dataset.id;
        const card = () => root.querySelector('.post[data-id="' + id + '"]');
        const created = p.text(card().querySelector(".post__text"));
        card().querySelector('[data-action="edit"]').click();
        await p.wait(300);
        card().querySelector('.edit-form [name="text"]').value = "Morning run, then breakfast";
        card().querySelector('[data-action="save"]').click();
        await p.wait(1300);
        const edited = p.text(card().querySelector(".post__text"));
        window.__requests.length = 0;
        card().querySelector('[data-social="like"]').click();
        await p.wait(1300);
        const likeRequests = p.requests(/\\/posts\\/\\d+\\/like$/, "POST");
        const likes = p.text(card().querySelector(".post-like__count"));
        card().querySelector('[data-social="toggle-comments"]').click();
        await p.wait(1200);
        const field = card().querySelector(".post-comment-form [name=comment]");
        field.value = "Nice!";
        field.dispatchEvent(new Event("input", {bubbles: true}));
        card().querySelector('.post-comment-form [type="submit"]').click();
        await p.wait(1300);
        const comments = card().querySelectorAll(".post-comment").length;
        card().querySelector('[data-social="delete-comment"]').click();
        await p.wait(300);
        card().querySelector('[data-social="confirm-delete-comment"]').click();
        await p.wait(1300);
        const commentsAfterDelete = card().querySelectorAll(".post-comment").length;
        sessionStorage.setItem("postId", id);
        return {photoFromSafeEndpoint, uploadPathInPage: document.body.innerHTML.includes("/uploads/"),
            created, edited, likeRequests, likes, comments, commentsAfterDelete};
    })()`);
    await posts.reload(3500);
    Object.assign(results.postsCrud, await posts.evaluate(`(async () => {
        const p = window.__p;
        const root = document.getElementById("profilePostsApp");
        const id = sessionStorage.getItem("postId");
        const card = () => root.querySelector('.post[data-id="' + id + '"]');
        const likesAfterReload = p.text(card()?.querySelector(".post-like__count"));
        card().querySelector('[data-action="delete"]').click();
        await p.wait(300);
        card().querySelector('[data-action="confirm-delete"]').click();
        await p.wait(1200);
        return {likesAfterReload, postsAfterDelete: root.querySelectorAll('.post[data-id="' + id + '"]').length};
    })()`));
    await posts.close();

    // ---- Back, Forward, Refresh ----
    const history = await open(client, {path: "profile.html", token: fx.ownerToken, wait: 2800});
    for (const nav of ["#friendsNav", "#todoListsNav", "#postsNav"]) {
        await history.evaluate(`window.__p.click(${JSON.stringify(nav)}, 1000)`);
    }
    const step = (code) => history.evaluate(`(async () => { ${code}; await window.__p.wait(1300); return window.__p.sections()[0] || ""; })()`);
    results.history = {
        afterBack: await step("history.back()"),
        afterForward: await step("history.forward()")
    };
    await step("history.back()");
    results.history.afterSecondBack = await step("history.back()");
    await step("history.forward()");
    await step("history.forward()");
    await history.reload(3200);
    results.history.afterReload = (await history.evaluate(`window.__p.sections()`))[0] || "";
    await history.close();

    const deep = await open(client, {path: "main.html", token: fx.ownerToken, wait: 1500});
    results.deepLinks = {};
    for (const hash of ["calendar", "my-routines", "create-routine", "habits", "progress", "friends", "followers",
        "todo-lists", "posts", "settings"]) {
        await deep.go("main.html", 1000);
        await deep.go("profile.html#" + hash, 3000);
        results.deepLinks[hash] = await deep.evaluate(`({
            visible: window.__p.sections(),
            modal: document.getElementById("addRoutineModal").classList.contains("show"),
            followersTab: !!document.querySelector('.friends-tab.active[data-friends-tab="followers"]')
        })`);
    }
    await deep.close();

    // ---- every section, one at a time ----
    const all = await open(client, {path: "profile.html", token: fx.ownerToken, wait: 2800});
    results.allSections = {sections: {}};
    for (const nav of ["calendarNav", "myRoutinesNav", "myHabitsNav", "progressNav", "friendsNav", "todoListsNav",
        "postsNav", "settingsNav"]) {
        await all.evaluate(`window.__p.click("#${nav}", 1300)`);
        results.allSections.sections[nav] = await all.evaluate(`({
            visible: window.__p.sections(),
            active: document.getElementById("${nav}").classList.contains("active"),
            path: location.pathname
        })`);
    }
    results.allSections.duplicateIds = await all.evaluate(`(() => {
        const ids = [...document.querySelectorAll("[id]")].map((el) => el.id);
        return [...new Set(ids.filter((id, index) => ids.indexOf(id) !== index))];
    })()`);
    await all.close();

    // ---- links from other pages ----
    const links = await open(client, {path: "main.html", token: fx.ownerToken, wait: 1800});
    results.links = await links.evaluate(`({
        footerTodo: [...document.querySelectorAll(".site-footer a")].find((a) => a.textContent.trim() === "To-do Lists")?.getAttribute("href") || "",
        footerPosts: [...document.querySelectorAll(".site-footer a")].find((a) => a.textContent.trim() === "Posts")?.getAttribute("href") || ""
    })`);
    await links.evaluate(`[...document.querySelectorAll(".site-footer a")].find((a) => a.textContent.trim() === "To-do Lists").click(); true`);
    await pause(3200);
    await links.helpers();
    results.links.footerTodoOpens = (await links.evaluate(`window.__p.sections()`))[0] || "";
    await links.close();

    const visitor = await open(client, {path: `user-profile.html?id=${fx.ownerId}`, token: fx.strangerToken, wait: 2500});
    Object.assign(results.links, await visitor.evaluate(`({
        publicTodo: document.getElementById("publicTodoListsLink")?.href || "",
        publicPosts: document.getElementById("publicPostsLink")?.href || ""
    })`));
    await visitor.go(`todo-lists.html?user=${fx.ownerId}`, 2500);
    results.strangerViews = {lists: await visitor.evaluate(`[...document.querySelectorAll("#todoLists .todo-list__title")].map((el) => window.__p.text(el))`)};
    await visitor.go(`posts.html?user=${fx.ownerId}`, 2500);
    results.strangerViews.posts = await visitor.evaluate(`[...document.querySelectorAll("#posts .post__text")].map((el) => window.__p.text(el))`);
    await visitor.close();

    // ---- todo-lists.html and posts.html ----
    for (const [name, path, items] of [["standaloneTodo", "todo-lists.html", "#todoLists .todo-list"],
        ["standalonePosts", "posts.html", "#posts .post"]]) {
        const page = await open(client, {path, token: fx.ownerToken, wait: 2800});
        results[name] = await page.evaluate(`(async () => {
            const p = window.__p;
            const links = [...document.querySelectorAll("header .nav-link")].map((a) => p.text(a));
            const menu = [...document.querySelectorAll("#userDropdown .dropdown-item")]
                .map((item) => p.text(item) + "|" + (item.getAttribute("href") || ""));
            document.getElementById("userTrigger")?.click();
            await p.wait(300);
            return {
                links, menu,
                user: p.text(document.getElementById("userName")),
                menuOpens: document.getElementById("userDropdown")?.classList.contains("show") === true,
                logo: document.querySelector("header .logo-icon img")?.getAttribute("src") || "",
                items: document.querySelectorAll(${JSON.stringify(items)}).length,
                overflow: document.documentElement.scrollWidth - document.documentElement.clientWidth
            };
        })()`);
        await page.snap(name);
        if (name === "standaloneTodo") {
            await page.evaluate(`[...document.querySelectorAll("#userDropdown .dropdown-item")].find((a) => a.textContent.trim() === "Settings").click(); true`);
            await pause(3500);
            await page.helpers();
            results.settingsLink = {
                opened: (await page.evaluate(`window.__p.sections()`))[0] === "themeSettingsContent" ? "profileSettings" : "",
                path: await page.evaluate(`location.pathname + location.hash`)
            };
        }
        await page.close();
    }

    const guest = await open(client, {path: "login.html", wait: 1500});
    results.headerGuest = await guest.evaluate(`(async () => {
        document.body.insertAdjacentHTML("afterbegin", '<header class="header" data-site-header></header>');
        const script = document.createElement("script");
        script.src = "site-header.js";
        document.body.appendChild(script);
        await window.__p.wait(800);
        return {buttons: [...document.querySelectorAll("#authArea a")].map((a) => window.__p.text(a) + "|" + a.getAttribute("href"))};
    })()`);
    await guest.close();

    // ---- designs and screens ----
    results.layouts = {};
    for (const theme of ["original", "autumn"]) {
        for (const width of [360, 390, 1280, 1366, 1536, 1920]) {
            for (const [hash, content] of [["todo-lists", "todoListsContent"], ["posts", "postsContent"], ["followers", "friendsContent"]]) {
                const view = await open(client, {path: "profile.html#" + hash, token: fx.ownerToken, width, theme, wait: 3200});
                results.layouts[`${theme}_${width}_${hash}`] = await view.evaluate(`(() => {
                    const p = window.__p;
                    const section = document.getElementById("${content}");
                    const texts = [...section.querySelectorAll("h1, .friends-page-header p, h2, .mc-btn, .friend-card-name, .post__text, .todo-task__text, .friends-tab")]
                        .filter((el) => p.visible(el) && p.text(el));
                    return {
                        scrollWidth: document.documentElement.scrollWidth,
                        clientWidth: document.documentElement.clientWidth,
                        contentVisible: p.visible(section),
                        minContrast: texts.length ? Math.min(...texts.map((el) => p.contrast(el))) : 0
                    };
                })()`);
                if (width === 390 || width === 1366) {
                    await view.evaluate(`document.getElementById("${content}").scrollIntoView({block: "start"}); true`);
                    await view.snap(`${theme}-${width}-${hash}`);
                }
                await view.close();
            }
        }
    }

    // Last, because signing out ends this session for good.
    const leaving = await open(client, {path: "posts.html", token: fx.lonelyToken, wait: 2500});
    await leaving.evaluate(`(async () => {
        document.getElementById("userTrigger").click();
        await window.__p.wait(300);
        document.getElementById("logoutBtn").click();
        return true;
    })()`);
    await pause(2500);
    await leaving.helpers();
    results.signedOutFromMenu = {path: await leaving.evaluate(`location.pathname`)};
    await leaving.close();

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
