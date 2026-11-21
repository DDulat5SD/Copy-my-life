// Opens each page in headless Chrome with hostile data already stored and reports whether
// any of it ran. Started by StoredXssBrowserRegressionTest; talks only to 127.0.0.1.
//
// Usage: node xss-browser-check.mjs <chrome> <fixtures.json> <results.json>
import {spawn} from "node:child_process";
import {mkdtempSync, readFileSync, rmSync, writeFileSync} from "node:fs";
import {tmpdir} from "node:os";
import {join} from "node:path";

const [chromePath, fixturesPath, resultsPath] = process.argv.slice(2);
const fx = JSON.parse(readFileSync(fixturesPath, "utf8"));

if (!/^http:\/\/127\.0\.0\.1:\d+$/.test(fx.base)) {
    throw new Error("Refusing to run against a non-local target");
}

const clickAll = (selector) => `
    for (const el of document.querySelectorAll(${JSON.stringify(selector)})) {
        el.click();
        await wait(350);
    }`;

const viewer = {token: fx.viewerToken};
const admin = {adminToken: fx.adminToken};

const pages = [
    {name: "user-profile (hostile)", url: `/user-profile.html?id=${fx.attackerId}`, ...viewer,
        expectText: ["<img src=x onerror=__mark('title-html')>"]},
    {name: "user-profile (normal)", url: `/user-profile.html?id=${fx.normalId}`, ...viewer,
        expectText: ['Morning "plan" & <b>focus</b>', "Plain bio & <i>text</i>"], expectImage: ["https://x.invalid/ok.png"]},
    {name: "profile: calendar", url: "/profile.html", ...viewer,
        expectText: ["<img src=x onerror=__mark('own-title-html')>"]},
    {name: "profile: routines tabs", url: "/profile.html", ...viewer, action: `
        document.getElementById("myRoutinesNav").click();
        await wait(300);
        ${clickAll("[data-tab]")}`},
    {name: "profile: habits", url: "/profile.html", ...viewer, action: `
        document.getElementById("myHabitsNav").click();`,
        expectText: ["<img src=x onerror=__mark('own-habit-html')>"]},
    {name: "profile: progress", url: "/profile.html", ...viewer, action: `
        document.getElementById("progressNav").click();`},
    {name: "profile: friends tabs", url: "/profile.html", ...viewer, action: `
        document.getElementById("friendsNav").click();
        await wait(400);
        ${clickAll("[data-friends-tab]")}`},
    {name: "profile: friend search", url: "/profile.html", ...viewer, action: `
        document.getElementById("friendsNav").click();
        await wait(300);
        const input = document.getElementById("friendsSearchInput");
        input.value = "Evil";
        input.dispatchEvent(new Event("input", {bubbles: true}));
        input.dispatchEvent(new KeyboardEvent("keydown", {key: "Enter", bubbles: true}));`},
    {name: "routine details + comments", url: `/routine.html?id=${fx.hostileRoutineId}`, ...viewer, action: `
        document.querySelector('[data-action="reply"]')?.click();`,
        expectText: ["<img src=x onerror=__mark('comment-html')>"]},
    {name: "routine details (own)", url: `/routine.html?id=${fx.ownRoutineId}`, ...viewer},
    {name: "routine details (js image)", url: `/routine.html?id=${fx.jsImageRoutineId}`, ...viewer},
    {name: "routine details (normal)", url: `/routine.html?id=${fx.normalRoutineId}`, ...viewer,
        expectText: ['Morning "plan" & <b>focus</b>', "Normal comment"]},
    {name: "routine run", url: `/routine-run.html?id=${fx.ownRoutineId}`, ...viewer},
    {name: "explore", url: "/Explore.html", ...viewer, action: `
        [...document.querySelectorAll("a,button")].find((el) => /view all/i.test(el.textContent))?.click();`,
        expectText: ['Morning "plan" & <b>focus</b>'], expectImage: ["https://x.invalid/ok.png"]},
    {name: "top routines", url: "/top-routines.html", ...viewer},
    {name: "main", url: "/main.html", ...viewer},
    {name: "admin users", url: "/admin-users.html", ...admin},
    {name: "admin routines", url: "/admin-routines.html", ...admin, action: `
        document.querySelector('[data-action="manage"]')?.click();`},
    {name: "admin habits", url: "/admin-habits.html", ...admin},
    {name: "admin comments", url: "/admin-comments.html", ...admin},
    {name: "admin reports", url: "/admin-reports.html", ...admin},
    {name: "admin profile", url: `/admin-profile.html?id=${fx.attackerId}`, ...admin},
    {name: "admin routine view", url: `/admin-routine-view.html?id=${fx.hostileRoutineId}&userId=${fx.attackerId}`, ...admin},
    {name: "admin habit view", url: `/admin-habit-view.html?id=${fx.hostileHabitId}&userId=${fx.attackerId}`, ...admin}
];

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
        const listeners = new Set();
        let nextId = 1;

        socket.onerror = reject;
        socket.onmessage = (event) => {
            const message = JSON.parse(event.data);
            if (message.id && pending.has(message.id)) {
                const {ok, fail} = pending.get(message.id);
                pending.delete(message.id);
                message.error ? fail(new Error(message.error.message)) : ok(message.result);
            } else {
                listeners.forEach((listener) => listener(message));
            }
        };
        socket.onopen = () => resolve({
            send: (method, params = {}, sessionId) => new Promise((ok, fail) => {
                const id = nextId++;
                pending.set(id, {ok, fail});
                socket.send(JSON.stringify({id, method, params, sessionId}));
            }),
            listen: (listener) => listeners.add(listener),
            close: () => socket.close()
        });
    });
}

const pause = (ms) => new Promise((done) => setTimeout(done, ms));

const SCAN = `(() => {
    const hits = [];
    for (const el of document.querySelectorAll("*")) {
        for (const attr of el.attributes) {
            if (/^on/i.test(attr.name) && /__mark/.test(attr.value)) {
                hits.push(el.tagName + "[" + attr.name + "]");
            }
            if (/^(src|href|action|formaction)$/i.test(attr.name) && /^\\s*(javascript|vbscript|data:text|data:image\\/svg)/i.test(attr.value)) {
                hits.push(el.tagName + "[" + attr.name + "] dangerous URL");
            }
        }
    }
    return {
        marks: window.__marks || [],
        hits,
        text: document.body ? document.body.innerText : "",
        images: [...document.images].map((img) => img.getAttribute("src"))
    };
})()`;

async function runPage(client, page) {
    const {targetId} = await client.send("Target.createTarget", {url: "about:blank"});
    const {sessionId} = await client.send("Target.attachToTarget", {targetId, flatten: true});
    const exceptions = [];

    client.listen((message) => {
        if (message.sessionId !== sessionId) {
            return;
        }
        if (message.method === "Runtime.exceptionThrown") {
            const detail = message.params.exceptionDetails;
            exceptions.push(detail.exception?.description?.split("\n")[0] || detail.text);
        }
        if (message.method === "Page.javascriptDialogOpening") {
            client.send("Page.handleJavaScriptDialog", {accept: true}, sessionId).catch(() => {});
        }
    });

    await client.send("Page.enable", {}, sessionId);
    await client.send("Runtime.enable", {}, sessionId);
    await client.send("Page.addScriptToEvaluateOnNewDocument", {source: `
        window.__marks = [];
        window.__mark = (id) => window.__marks.push(id);
        ${page.token ? `localStorage.setItem("token", ${JSON.stringify(page.token)});` : ""}
        ${page.adminToken ? `sessionStorage.setItem("adminToken", ${JSON.stringify(page.adminToken)});` : ""}
    `}, sessionId);
    await client.send("Page.navigate", {url: fx.base + page.url}, sessionId);
    await pause(1800);

    if (page.action) {
        await client.send("Runtime.evaluate", {
            expression: `(async () => { const wait = (ms) => new Promise((done) => setTimeout(done, ms)); ${page.action} })()`,
            awaitPromise: true
        }, sessionId);
        await pause(1200);
    }

    const {result} = await client.send("Runtime.evaluate", {expression: SCAN, returnByValue: true}, sessionId);
    const scan = result.value;
    await client.send("Target.closeTarget", {targetId});

    return {
        name: page.name,
        marks: scan.marks,
        hits: [...new Set(scan.hits)],
        missingText: (page.expectText || []).filter((text) => !scan.text.includes(text)),
        missingImages: (page.expectImage || []).filter((src) => !scan.images.includes(src)),
        exceptions: [...new Set(exceptions)]
    };
}

const profile = mkdtempSync(join(tmpdir(), "cml-xss-"));
const {chrome, endpoint} = await startChrome(profile);

try {
    const client = await connect(endpoint);
    const results = [];
    for (const page of pages) {
        results.push(await runPage(client, page));
    }
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
