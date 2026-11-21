// Uses the Following tab on profile.html in headless Chrome the way a user would.
// Started by FollowingListRegressionTest; talks only to 127.0.0.1.
//
// Usage: node following-list-check.mjs <chrome> <fixtures.json> <results.json>
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

const STATE = `(() => {
    const text = (el) => (el?.textContent || "").trim().replace(/\\s+/g, " ");
    const cards = [...document.querySelectorAll("#friendsList .friend-card")].map((card) => ({
        name: text(card.querySelector(".friend-card-name")),
        username: text(card.querySelector(".friend-card-username")),
        href: card.querySelector("a.friend-view-btn")?.href || "",
        hasImage: !!card.querySelector(".friend-card-avatar img")
    }));
    return {
        cards,
        countText: text(document.getElementById("friendsCountText")),
        followingCount: text(document.getElementById("followingCount")),
        emptyText: text(document.querySelector("#friendsList .friends-empty")),
        injectedImages: document.querySelectorAll("#friendsList .friend-card-name img").length,
        xss: window.__xss === 1
    };
})()`;

// Opens the Friends section, then its Following tab.
const OPEN_FOLLOWING = `(async () => {
    document.getElementById("friendsNav").click();
    await new Promise((done) => setTimeout(done, 900));
    document.querySelector('[data-friends-tab="following"]')?.click();
    await new Promise((done) => setTimeout(done, 1200));
})()`;

const unfollow = (row) => `(async () => {
    const card = document.querySelectorAll("#friendsList .friend-card")[${row}];
    const button = card && card.querySelector(".following-unfollow-btn");
    if (!button || button.disabled || button.offsetParent === null) {
        return false;
    }
    button.click();
    await new Promise((done) => setTimeout(done, 1200));
    return true;
})()`;

const LAYOUT = `(() => {
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
    const visible = (el) => !!el && !el.hidden && el.offsetParent !== null;
    const texts = [...document.querySelectorAll(
        "#friendsList .friend-card-name, #friendsList .friend-card-username, #friendsList .friend-view-btn, #friendsList .following-unfollow-btn"
    )].filter(visible);
    const targets = [...document.querySelectorAll("#friendsList a, #friendsList button")].filter(visible);
    const worst = texts.reduce((low, el) => (!low || contrast(el) < contrast(low) ? el : low), null);
    return {
        lowestContrast: worst ? worst.className + " " + getComputedStyle(worst).color : "",
        width: innerWidth,
        theme: document.documentElement.getAttribute("data-theme") || "",
        rendered: document.querySelectorAll("#friendsList .friend-card").length > 0 && texts.length > 0,
        overflow: document.documentElement.scrollWidth - innerWidth,
        rightmost: Math.round(Math.max(0, ...targets.map((el) => el.getBoundingClientRect().right))),
        smallestTarget: Math.round(Math.min(...targets.map((el) => el.getBoundingClientRect().height))),
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

async function open(client, {token, width = 1280, theme = "original"}) {
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
    await client.send("Page.navigate", {url: `${fx.base}/profile.html`}, sessionId);
    await pause(2000);

    return {
        evaluate: async (expression) => (await client.send("Runtime.evaluate", {
            expression, returnByValue: true, awaitPromise: true
        }, sessionId)).result.value,
        reload: async () => {
            await client.send("Page.reload", {}, sessionId);
            await pause(2000);
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

const profile = mkdtempSync(join(tmpdir(), "cml-following-"));
const {chrome, endpoint} = await startChrome(profile);

try {
    const client = await connect(endpoint);
    const results = {ids: {ada: fx.adaId, bea: fx.beaId}};

    // The layouts run first, while the fan still follows both creators.
    const layouts = {
        originalPhone: {token: fx.fanToken, width: 390, theme: "original"},
        autumnPhone: {token: fx.fanToken, width: 390, theme: "autumn"},
        autumnDesktop: {token: fx.fanToken, width: 1280, theme: "autumn"}
    };
    for (const [name, options] of Object.entries(layouts)) {
        const view = await open(client, options);
        await view.evaluate(OPEN_FOLLOWING);
        results[name] = await view.evaluate(LAYOUT);
        await view.snap(name);
        await view.close();
    }

    const fan = await open(client, {token: fx.fanToken});
    await fan.evaluate(OPEN_FOLLOWING);
    results.initial = await fan.evaluate(STATE);
    await fan.snap("following-original-desktop");
    await fan.evaluate(unfollow(0));
    results.afterUnfollow = await fan.evaluate(STATE);
    await fan.reload();
    await fan.evaluate(OPEN_FOLLOWING);
    results.afterReload = await fan.evaluate(STATE);
    await fan.close();

    const lonely = await open(client, {token: fx.lonelyToken});
    await lonely.evaluate(OPEN_FOLLOWING);
    results.empty = await lonely.evaluate(STATE);
    await lonely.snap("following-empty");
    await lonely.close();

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
