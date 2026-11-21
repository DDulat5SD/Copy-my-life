// US-30: opens Top Routines as a visitor, chooses the period, the sort, the category and the
// time of day, and compares what is listed with what the server ranks; reads the address, the
// empty state, a late answer, the rating and time shown on a card, and the layout in each design.
// Answers that must be empty, slow or made up are given inside the browser only; nothing is written.
// Started by RoutineLeaderboardRegressionTest; talks only to 127.0.0.1.
//
// Usage: node routine-leaderboard-check.mjs <chrome> <fixtures.json> <results.json>
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
// Per session: [{match, status, body, hold}] answered inside the browser instead of the server.
const answers = new Map();
// Answers held back until release(sessionId).
const held = new Map();
// Optional: set LEADERBOARD_SCREENSHOTS to a folder to keep a picture of each layout.
const screenshots = process.env.LEADERBOARD_SCREENSHOTS;

// Every /top-routines request the page makes, kept in the page.
const RECORD = `(() => {
    const original = window.fetch.bind(window);
    window.__requests = [];
    window.fetch = (input, init) => {
        const url = typeof input === "string" ? input : input.url;
        if (url.includes("/top-routines?")) {
            window.__requests.push(url);
        }
        return original(input, init);
    };
})();`;

// What the page shows and asks for, and what the server ranks for the same question.
const READ = `(async () => {
    const value = (id) => document.getElementById(id)?.value || "";
    const last = window.__requests[window.__requests.length - 1] || "";
    const cards = [...document.querySelectorAll("#rankingList .ranking-card")].map((card) => Number(card.dataset.routineId));
    const server = last ? (await (await fetch(last)).json()).map((routine) => routine.id) : [];
    const empty = document.querySelector("#rankingList .ranking-empty");
    const hint = document.querySelector("#rankingList .ranking-empty-hint");
    const reset = document.querySelector("#rankingList .ranking-reset");
    return {
        cards,
        server,
        request: last,
        search: location.search,
        period: document.querySelector(".period-btn.active")?.dataset.period || "",
        sort: document.querySelector(".ranking-filter.active")?.dataset.filter || "",
        category: value("categoryFilter"),
        time: value("timeFilter"),
        message: empty ? empty.textContent.trim().replace(/\\s+/g, " ") : "",
        hint: hint ? hint.textContent.trim().replace(/\\s+/g, " ") : "",
        resetVisible: !!reset && reset.offsetParent !== null,
        xss: window.__xss === 1
    };
})()`;

const CONTROLS = `(() => {
    const words = (el) => el.textContent.trim().replace(/^[^A-Za-z]+/, "").replace(/\\s+/g, " ");
    return {
        categories: [...document.querySelectorAll("#categoryFilter option")].map(words),
        times: [...document.querySelectorAll("#timeFilter option")].map(words),
        categoryLabel: document.getElementById("categoryFilter")?.getAttribute("aria-label") || "",
        timeLabel: document.getElementById("timeFilter")?.getAttribute("aria-label") || "",
        periods: [...document.querySelectorAll(".period-btn")].map(words),
        sorts: [...document.querySelectorAll(".ranking-filter")].map(words)
    };
})()`;

const CARDS = `(() => {
    const cards = [...document.querySelectorAll("#rankingList .ranking-card")];
    const rating = (card) => card.querySelectorAll(".ranking-stat")[2];
    const words = (el) => (el?.textContent || "").trim().replace(/\\s+/g, " ");
    return {
        unratedValue: words(rating(cards[0])?.querySelector("strong")).replace(/^☆\\s*/, ""),
        unratedLabel: words(rating(cards[0])?.querySelector("span")),
        ratedValue: words(rating(cards[1])?.querySelector("strong")).replace(/^☆\\s*/, ""),
        time: words(cards[1]?.querySelector(".ranking-time")),
        untimed: words(cards[0]?.querySelector(".ranking-time")),
        trickyShown: words(cards[0]?.querySelector("h2")).includes("<img"),
        initial: words(cards[0]?.querySelector(".ranking-image-fallback")),
        xss: window.__xss === 1
    };
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
        const body = parse(getComputedStyle(document.body).backgroundColor);
        return body && body.a > 0.5 ? body : {r: 255, g: 255, b: 255, a: 1};
    };
    const contrast = (el) => {
        const a = luminance(parse(getComputedStyle(el).color));
        const b = luminance(background(el));
        return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
    };
    const visible = (el) => !!el && !el.hidden && el.offsetParent !== null;
    const inside = (el) => {
        const box = el.getBoundingClientRect();
        return box.left >= -1 && box.right <= innerWidth + 1;
    };
    const controls = [...document.querySelectorAll(".period-btn, .ranking-filter, #categoryFilter, #timeFilter")].filter(visible);
    const row = [...document.querySelectorAll(".ranking-filter, #categoryFilter, #timeFilter")].filter(visible);
    const top = (el) => Math.round(el.getBoundingClientRect().top);
    const texts = [...document.querySelectorAll(
        ".period-btn, .ranking-filter, #categoryFilter, #timeFilter, .ranking-time-note, .ranking-card h2,"
        + " .ranking-author, .ranking-text, .ranking-time, .ranking-stat strong, .ranking-stat span, .ranking-stat small,"
        + " .ranking-note")]
        .filter(visible).filter((el) => el.textContent.trim());
    const faintest = texts.length ? texts.reduce((a, b) => contrast(a) <= contrast(b) ? a : b) : null;
    return {
        width: innerWidth,
        theme: document.documentElement.getAttribute("data-theme") || "",
        category: document.getElementById("categoryFilter").value,
        overflow: document.documentElement.scrollWidth - innerWidth,
        controlsInside: controls.length > 0 && controls.every(inside),
        filtersOneRow: new Set(row.map(top)).size === 1,
        selectsSideBySide: !!document.getElementById("timeFilter")
            && top(document.getElementById("categoryFilter")) === top(document.getElementById("timeFilter")),
        cards: document.querySelectorAll("#rankingList .ranking-card").length,
        measured: texts.length,
        minContrast: faintest ? Math.round(contrast(faintest) * 100) / 100 : 0,
        faintest: faintest ? (faintest.id || faintest.className || faintest.tagName) + " " + faintest.textContent.trim().slice(0, 20) : ""
    };
})()`;

// The empty card: its texts readable and everything on one centred card.
const LAYOUT_EMPTY = `(() => {
    const card = document.querySelector("#rankingList .ranking-empty-state");
    const box = (el) => el.getBoundingClientRect();
    const parts = [...card.querySelectorAll(".ranking-empty, .ranking-empty-hint, .ranking-reset")];
    return {
        onCard: !!card && parts.length === 3 && parts.every((el) => box(el).left >= box(card).left && box(el).right <= box(card).right),
        centred: Math.abs((box(parts[0]).left + box(parts[0]).right) / 2 - (box(card).left + box(card).right) / 2) < 3,
        background: getComputedStyle(card).backgroundColor
    };
})()`;

// Chooses filters the way a person does: a click on the period and the sort, a pick in each list.
const choose = ({period, sort, category, time} = {}) => `(async () => {
    const pick = (id, value) => {
        const select = document.getElementById(id);
        if (select && value !== undefined) {
            select.value = value;
            select.dispatchEvent(new Event("change", {bubbles: true}));
        }
    };
    ${period ? `document.querySelector('.period-btn[data-period="${period}"]').click();` : ""}
    ${sort ? `document.querySelector('.ranking-filter[data-filter="${sort}"]').click();` : ""}
    pick("categoryFilter", ${JSON.stringify(category)});
    pick("timeFilter", ${JSON.stringify(time)});
    await new Promise((done) => setTimeout(done, 900));
    return true;
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
                send("Page.handleJavaScriptDialog", {accept: true}, message.sessionId).catch(() => {});
                return;
            }
            if (message.method === "Runtime.exceptionThrown") {
                errors.push(message.params.exceptionDetails.exception?.description
                    || message.params.exceptionDetails.text);
                return;
            }
            if (message.method === "Fetch.requestPaused") {
                const {requestId, request} = message.params;
                const answer = (answers.get(message.sessionId) || []).find((rule) => request.url.includes(rule.match));
                const fulfil = () => send("Fetch.fulfillRequest", {
                    requestId,
                    responseCode: answer.status,
                    responseHeaders: [{name: "Content-Type", value: "application/json"}],
                    body: Buffer.from(answer.body).toString("base64")
                }, message.sessionId).catch(() => {});
                if (!answer) {
                    send("Fetch.continueRequest", {requestId}, message.sessionId).catch(() => {});
                } else if (answer.hold) {
                    held.set(message.sessionId, [...(held.get(message.sessionId) || []), fulfil]);
                } else {
                    fulfil();
                }
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

// rules: /top-routines answers given in the browser, by a part of the address.
async function open(client, {path = "/top-routines.html", rules = [], width = 1280, theme = "original"} = {}) {
    const {targetId} = await client.send("Target.createTarget", {url: "about:blank"});
    const {sessionId} = await client.send("Target.attachToTarget", {targetId, flatten: true});

    await client.send("Page.enable", {}, sessionId);
    await client.send("Runtime.enable", {}, sessionId);
    if (rules.length) {
        answers.set(sessionId, rules);
        await client.send("Fetch.enable", {patterns: [{urlPattern: "*/top-routines?*"}]}, sessionId);
    }
    await client.send("Emulation.setDeviceMetricsOverride", {
        width, height: width < 600 ? 844 : 900, deviceScaleFactor: width < 600 ? 2 : 1, mobile: width < 600
    }, sessionId);
    await client.send("Page.addScriptToEvaluateOnNewDocument", {
        source: `localStorage.removeItem("token");`
            + `localStorage.setItem("copymylife-theme", ${JSON.stringify(theme)});`
            + RECORD
    }, sessionId);
    await client.send("Page.navigate", {url: fx.base + path}, sessionId);
    await pause(2200);

    const view = {
        evaluate: async (expression) => (await client.send("Runtime.evaluate", {
            expression, returnByValue: true, awaitPromise: true
        }, sessionId)).result.value,
        read: () => view.evaluate(READ),
        release: async () => {
            (held.get(sessionId) || []).forEach((fulfil) => fulfil());
            held.delete(sessionId);
            await pause(800);
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
    return view;
}

// A made-up list: an unrated routine with a tricky title and no start time, then a rated one at 06:30.
const madeUp = JSON.stringify([
    {id: 910001, title: fx.tricky, description: "Made up", category: "Study", imageUrl: null, authorName: fx.tricky,
        likeCount: 2, saveCount: 0, averageRating: 0, ratingCount: 0, timeOfDay: null, startsAt: null},
    {id: 910002, title: "Rated plan", description: "Made up", category: "Morning", imageUrl: null, authorName: "Ana",
        likeCount: 1, saveCount: 1, averageRating: 4.5, ratingCount: 2, timeOfDay: "morning", startsAt: "06:30"}
]);

const profile = mkdtempSync(join(tmpdir(), "cml-leaderboard-"));
const {chrome, endpoint} = await startChrome(profile);

try {
    const client = await connect(endpoint);
    const results = {};

    const page = await open(client);
    results.controls = await page.evaluate(CONTROLS);

    // What is listed is what the server ranks, for each combination.
    results.shown = {};
    results.shown.plain = {...await page.read(), expect: "period=week&sort=overall&category=all&time=all"};
    await page.evaluate(choose({sort: "liked", time: "evening"}));
    results.shown.eveningLiked = {...await page.read(), expect: "sort=liked&category=all&time=evening"};
    await page.evaluate(choose({sort: "overall", category: "Fitness", time: "afternoon"}));
    results.shown.fitnessAfternoon = {...await page.read(), expect: "sort=overall&category=Fitness&time=afternoon"};
    await page.evaluate(choose({category: "Halloween", time: "all"}));
    results.shown.halloweenOverall = {...await page.read(), expect: "sort=overall&category=Halloween&time=all"};
    await page.evaluate(choose({period: "month", sort: "rated", category: "all", time: "morning"}));
    results.shown.morningRatedMonth = {...await page.read(), expect: "period=month&sort=rated&category=all&time=morning"};
    await page.close();

    // The choice is kept in the address, and opening that address brings it back.
    const address = await open(client);
    await address.evaluate(choose({period: "month", sort: "liked", category: "Fitness", time: "evening"}));
    results.address = {afterChoosing: (await address.read()).search};
    await address.close();
    const restored = await open(client, {path: "/top-routines.html" + results.address.afterChoosing});
    results.address.restored = await restored.read();
    await restored.close();
    const unknown = await open(client, {path: "/top-routines.html?period=year&sort=best&time=noon&category="
            + encodeURIComponent(fx.tricky)});
    results.address.unknown = await unknown.read();
    await unknown.close();

    // Nothing to list: say what was chosen and offer to start again.
    const empty = await open(client, {path: "/top-routines.html?category=Creators&time=morning"});
    results.empty = await empty.read();
    results.emptyLayout = await empty.evaluate(LAYOUT_EMPTY);
    await empty.snap("empty");
    await empty.evaluate(`document.querySelector("#rankingList .ranking-reset").click(); new Promise((done) => setTimeout(done, 900))`);
    results.empty.afterReset = await empty.read();
    await empty.close();

    const none = await open(client, {rules: [{match: "/top-routines?", status: 200, body: "[]"}]});
    results.empty.plain = await none.read();
    await none.evaluate(choose({category: "Halloween"}));
    results.empty.category = await none.read();
    await none.evaluate(choose({category: "all", time: "evening"}));
    results.empty.time = await none.read();
    await none.close();

    // An answer for an earlier choice that arrives after the latest one changes nothing.
    const race = await open(client, {rules: [{match: "time=morning", status: 200, body: madeUp, hold: true}]});
    await race.evaluate(`(() => {
        const select = document.getElementById("timeFilter");
        select.value = "morning";
        select.dispatchEvent(new Event("change", {bubbles: true}));
        select.value = "evening";
        select.dispatchEvent(new Event("change", {bubbles: true}));
        return true;
    })()`);
    await pause(900);
    await race.release();
    results.race = await race.read();
    await race.close();

    // A routine without ratings, and the start time of a timed one.
    const cards = await open(client, {rules: [{match: "/top-routines?", status: 200, body: madeUp}]});
    results.cards = await cards.evaluate(CARDS);
    await cards.snap("made-up-cards");
    await cards.close();

    results.layouts = {};
    for (const width of [360, 390, 1280, 1366, 1920]) {
        for (const [design, options] of Object.entries({
            original: {theme: "original"},
            autumn: {theme: "autumn"},
            halloween: {theme: "original", path: "/top-routines.html?category=Halloween"}
        })) {
            const view = await open(client, {...options, width});
            results.layouts[`${design}${width}`] = await view.evaluate(LAYOUT);
            await view.snap(`${design}-${width}`);
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
