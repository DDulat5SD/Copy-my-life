// Opens Explore as a visitor and reads what is listed, and what is said when nothing is.
// The empty and failed cases answer /routines/built-in and /routines/public inside the
// browser only; no routine is removed from the database.
// Started by ExplorePublicRoutinesRegressionTest; talks only to 127.0.0.1.
//
// Usage: node explore-empty-check.mjs <chrome> <fixtures.json> <results.json>
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
// Answers for the routine lists, by session; a session without one gets the real server.
const answers = new Map();
// Optional: set EXPLORE_SCREENSHOTS to a folder to keep a picture of each state for a visual check.
const screenshots = process.env.EXPLORE_SCREENSHOTS;

const EXPLORE = `(() => {
    const visible = (el) => !!el && !el.hidden && el.offsetParent !== null;
    const text = (el) => (el?.textContent || "").trim().replace(/\\s+/g, " ");
    const empty = document.getElementById("exploreEmpty");
    const cards = [...document.querySelectorAll("#routineResults .featured, #routineResults .card, #routineResults .mini-card")]
        .filter(visible);
    return {
        emptyVisible: visible(empty),
        emptyTitle: visible(empty) ? text(empty.querySelector(".explore-empty-title")) : "",
        emptyText: visible(empty) ? text(empty.querySelector(".explore-empty-text")) : "",
        emptyRole: empty?.getAttribute("role") || "",
        emptyLive: empty?.getAttribute("aria-live") || "",
        cards: cards.length,
        names: cards.map((card) => text(card.querySelector(".featured-name, .card-name, .mini-name"))),
        routineLinks: cards.filter((card) => card.querySelector('a[href^="routine.html?id="]')).length,
        viewAllButton: visible(document.getElementById("viewAllBtn")),
        viewAllCards: [...document.querySelectorAll("#viewAllGrid .view-all-card")].filter(visible)
            .map((card) => text(card.querySelector(".view-all-name"))),
        viewAllEmpty: [...document.querySelectorAll("#viewAllGrid .view-all-empty")].some(visible),
        halloween: document.body.classList.contains("halloween-mode")
    };
})()`;

const search = (query) => `(async () => {
    const input = document.getElementById("routineSearch");
    input.value = ${JSON.stringify(query)};
    input.dispatchEvent(new Event("input", {bubbles: true}));
    await new Promise((done) => setTimeout(done, 400));
    return true;
})()`;

const click = (selector) => `(async () => {
    const el = document.querySelector(${JSON.stringify(selector)});
    if (!el || el.offsetParent === null) {
        return false;
    }
    el.click();
    await new Promise((done) => setTimeout(done, 600));
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
    const texts = [...document.querySelectorAll("#exploreEmpty .explore-empty-title, #exploreEmpty .explore-empty-text")]
        .filter(visible);
    const box = document.getElementById("exploreEmpty")?.getBoundingClientRect();
    return {
        width: innerWidth,
        theme: document.documentElement.getAttribute("data-theme") || "",
        halloween: document.body.classList.contains("halloween-mode"),
        rendered: texts.length === 2,
        overflow: document.documentElement.scrollWidth - innerWidth,
        left: box ? Math.round(box.left) : -1,
        rightmost: box ? Math.round(box.right) : 0,
        minContrast: texts.length ? Math.round(Math.min(...texts.map(contrast)) * 100) / 100 : 0
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
            if (message.method === "Fetch.requestPaused") {
                const answer = answers.get(message.sessionId);
                send("Fetch.fulfillRequest", {
                    requestId: message.params.requestId,
                    responseCode: answer.status,
                    responseHeaders: [{name: "Content-Type", value: "application/json"}],
                    body: Buffer.from(answer.body).toString("base64")
                }, message.sessionId);
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

// answer: null opens Explore against the real server; otherwise both routine lists get it.
async function open(client, {answer = null, width = 1280, theme = "original"} = {}) {
    const {targetId} = await client.send("Target.createTarget", {url: "about:blank"});
    const {sessionId} = await client.send("Target.attachToTarget", {targetId, flatten: true});

    await client.send("Page.enable", {}, sessionId);
    await client.send("Runtime.enable", {}, sessionId);
    if (answer) {
        answers.set(sessionId, answer);
        await client.send("Fetch.enable", {
            patterns: [{urlPattern: "*/routines/built-in*"}, {urlPattern: "*/routines/public*"}]
        }, sessionId);
    }
    await client.send("Emulation.setDeviceMetricsOverride", {
        width, height: width < 600 ? 844 : 900, deviceScaleFactor: width < 600 ? 2 : 1, mobile: width < 600
    }, sessionId);
    await client.send("Page.addScriptToEvaluateOnNewDocument", {
        source: `localStorage.removeItem("token");`
            + `localStorage.setItem("copymylife-theme", ${JSON.stringify(theme)});`
    }, sessionId);
    await client.send("Page.navigate", {url: fx.base + "/Explore.html"}, sessionId);
    await pause(2500);

    return {
        evaluate: async (expression) => (await client.send("Runtime.evaluate", {
            expression, returnByValue: true, awaitPromise: true
        }, sessionId)).result.value,
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

const NONE = {status: 200, body: "[]"};
const FAILED = {status: 500, body: "{\"message\":\"down\"}"};
const NO_MATCH = "zzqx-no-such-routine";

const profile = mkdtempSync(join(tmpdir(), "cml-explore-"));
const {chrome, endpoint} = await startChrome(profile);

try {
    const client = await connect(endpoint);
    const results = {};

    const real = await open(client);
    results.listed = await real.evaluate(EXPLORE);
    await real.evaluate(search(NO_MATCH));
    results.noMatch = await real.evaluate(EXPLORE);
    await real.snap("no-match");
    await real.evaluate(search(""));
    results.cleared = await real.evaluate(EXPLORE);

    await real.evaluate(click("#viewAllBtn"));
    results.viewAllOpen = await real.evaluate(EXPLORE);
    await real.evaluate(search(NO_MATCH));
    results.viewAllNoMatch = await real.evaluate(EXPLORE);
    await real.evaluate(search(""));
    results.viewAllCleared = await real.evaluate(EXPLORE);
    await real.close();

    // Michael Jordan is not a featured routine, only one of the routines behind View all.
    const more = await open(client);
    await more.evaluate(search("jordan"));
    results.onlyMore = await more.evaluate(EXPLORE);
    await more.evaluate(click("#viewAllBtn"));
    results.onlyMoreOpen = await more.evaluate(EXPLORE);
    await more.close();

    // David Goggins is featured under Fitness and nowhere under Study.
    const filtered = await open(client);
    await filtered.evaluate(search("goggins"));
    await filtered.evaluate(click('.chip[data-filter="study"]'));
    results.filterNoMatch = await filtered.evaluate(EXPLORE);
    await filtered.evaluate(click('.chip[data-filter="fitness"]'));
    results.filterMatch = await filtered.evaluate(EXPLORE);
    await filtered.close();

    const halloween = await open(client);
    await halloween.evaluate(click('.chip[data-filter="halloween"]'));
    await halloween.evaluate(search(NO_MATCH));
    results.halloweenNoMatch = await halloween.evaluate(EXPLORE);
    await halloween.evaluate(search(""));
    results.halloweenCleared = await halloween.evaluate(EXPLORE);
    await halloween.close();

    const none = await open(client, {answer: NONE});
    results.none = await none.evaluate(EXPLORE);
    await none.snap("none");
    await none.evaluate(click('.chip[data-filter="fitness"]'));
    results.noneFiltered = await none.evaluate(EXPLORE);
    await none.close();

    const failed = await open(client, {answer: FAILED});
    results.failed = await failed.evaluate(EXPLORE);
    await failed.snap("failed");
    await failed.close();

    const layouts = {
        originalPhone: {answer: NONE, width: 390, theme: "original"},
        autumnPhone: {answer: NONE, width: 390, theme: "autumn"},
        originalDesktop: {answer: NONE, width: 1280, theme: "original"},
        autumnDesktop: {answer: NONE, width: 1280, theme: "autumn"},
        originalSearchPhone: {width: 390, theme: "original", query: NO_MATCH},
        autumnSearchPhone: {width: 390, theme: "autumn", query: NO_MATCH},
        halloweenPhone: {width: 390, theme: "original", query: NO_MATCH, halloween: true},
        autumnHalloweenPhone: {width: 390, theme: "autumn", query: NO_MATCH, halloween: true}
    };
    for (const [name, options] of Object.entries(layouts)) {
        const view = await open(client, options);
        if (options.halloween) {
            await view.evaluate(click('.chip[data-filter="halloween"]'));
        }
        if (options.query) {
            await view.evaluate(search(options.query));
        }
        await view.evaluate(`document.getElementById("exploreEmpty")?.scrollIntoView({block: "center"}); true`);
        results[name] = await view.evaluate(LAYOUT);
        await view.snap(name);
        await view.close();
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
