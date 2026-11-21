// US-12: drives the profile's Creator Statistics section in headless Chrome. A creator opens it from
// the menu and from its address and sees their followers and the copies of each public routine; a
// member copies one of those routines with Copy and with Adapt & copy on the routine page, and the
// numbers go up; a new member sees zeros; loading, error and retry are shown; a hostile routine title
// stays text; every other profile section still opens; the section is measured in both designs from
// a 360 px phone to a 1920 px desktop. Started by CreatorStatisticsRegressionTest; talks only to
// 127.0.0.1.
//
// Usage: node creator-stats-check.mjs <chrome> <fixtures.json> <results.json>
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
// Optional: set CREATOR_STATS_SCREENSHOTS to a folder to keep a picture of each page.
const screenshots = process.env.CREATOR_STATS_SCREENSHOTS;

const HELPERS = `
    window.__t = {
        wait: (ms) => new Promise((done) => setTimeout(done, ms)),
        text: (el) => (el?.textContent || "").trim().replace(/\\s+/g, " "),
        visible: (el) => !!el && !el.hidden && el.offsetParent !== null,
        until: async (test, ms = 10000) => {
            for (let waited = 0; waited < ms; waited += 100) {
                const found = typeof test === "string" ? document.querySelector(test) : test();
                if (found) return found;
                await window.__t.wait(100);
            }
            return null;
        },
        // The statistics request can be slowed down or made to fail.
        statsDelay: 0,
        statsFail: false,
        // Waits until the section shows its numbers or its error.
        ready: () => window.__t.until(() => {
            const root = document.getElementById("creatorStatsApp");
            const body = root && root.querySelector(".cs-body");
            const error = root && root.querySelector(".cs-error");
            return (body && !body.hidden) || (error && !error.hidden) ? root : null;
        })
    };
    const __fetch = window.fetch.bind(window);
    window.fetch = async (url, options = {}) => {
        if (String(url) === "/users/me/creator-stats") {
            if (window.__t.statsDelay) await window.__t.wait(window.__t.statsDelay);
            if (window.__t.statsFail) return new Response("{}", {status: 500, headers: {"Content-Type": "application/json"}});
        }
        return __fetch(url, options);
    };
`;

// What the section shows.
const READ = `(() => {
    const root = document.getElementById("creatorStatsApp");
    const section = document.getElementById("creatorStatsContent");
    const items = [...root.querySelectorAll(".cs-routine")];
    const error = root.querySelector(".cs-error");
    return {
        sectionShown: getComputedStyle(section).display !== "none",
        hash: location.hash,
        overview: [...root.querySelectorAll(".cs-overview .cs-stat__value")].map((el) => el.textContent.trim()),
        hints: [...root.querySelectorAll(".cs-stat__hint")].map((el) => el.textContent.trim()),
        copies: Object.fromEntries(items.map((li) => [li.dataset.routineId, Number(li.querySelector(".cs-routine__copies").dataset.copies)])),
        order: items.map((li) => Number(li.dataset.routineId)),
        emptyRoutines: !!root.querySelector('[data-empty="routines"]'),
        noCopiesNote: !!root.querySelector('[data-empty="copies"]'),
        errorShown: !!error && !error.hidden
    };
})()`;

// Sideways scrolling, whether the numbers and the menu entry fit the screen, and the faintest text.
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
    const inside = (el) => { const box = el.getBoundingClientRect(); return box.left >= -1 && box.right <= innerWidth + 1; };
    const section = document.getElementById("creatorStatsContent");
    const texts = [...section.querySelectorAll("h1, h2, p, dt, dd, a, button, span")].filter(visible)
        .filter((el) => [...el.childNodes].some((n) => n.nodeType === 3 && n.textContent.trim()));
    let minContrast = 21;
    let faintest = "";
    texts.forEach((el) => {
        const value = contrast(el);
        if (value < minContrast) {
            minContrast = Math.round(value * 100) / 100;
            faintest = (el.className || el.tagName) + " " + el.textContent.trim().slice(0, 24);
        }
    });
    const nav = document.getElementById("creatorStatsNav");
    return {
        width: innerWidth,
        overflow: document.documentElement.scrollWidth - innerWidth,
        statsInside: [...section.querySelectorAll(".cs-stat, .cs-routine, .cs-routine__copies")].filter(visible).every(inside),
        navInside: visible(nav) && inside(nav),
        minContrast,
        faintest
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

async function open(client, path, {token = null, width = 1280, theme = "original"} = {}) {
    const {targetId} = await client.send("Target.createTarget", {url: "about:blank"});
    const {sessionId} = await client.send("Target.attachToTarget", {targetId, flatten: true});
    await client.send("Page.enable", {}, sessionId);
    await client.send("Runtime.enable", {}, sessionId);
    await client.send("Emulation.setDeviceMetricsOverride", {
        width, height: width < 600 ? 844 : 900, deviceScaleFactor: width < 600 ? 2 : 1, mobile: width < 600
    }, sessionId);
    await client.send("Page.addScriptToEvaluateOnNewDocument", {
        source: (token ? `localStorage.setItem("token", ${JSON.stringify(token)});` : `localStorage.removeItem("token");`)
            + `localStorage.setItem("copymylife-theme", ${JSON.stringify(theme)});`
            + HELPERS
    }, sessionId);
    await client.send("Page.navigate", {url: fx.base + path}, sessionId);
    await pause(2500);
    return {
        evaluate: async (expression) => {
            const answer = await client.send("Runtime.evaluate", {expression, returnByValue: true, awaitPromise: true}, sessionId);
            if (answer.exceptionDetails) {
                throw new Error(path + ": " + (answer.exceptionDetails.exception?.description || answer.exceptionDetails.text));
            }
            return answer.result.value;
        },
        snap: async (name) => {
            if (!screenshots) return;
            const {data} = await client.send("Page.captureScreenshot", {format: "png"}, sessionId);
            writeFileSync(join(screenshots, `${name}.png`), Buffer.from(data, "base64"));
        },
        close: () => client.send("Target.closeTarget", {targetId})
    };
}

const results = {creator: {}, copied: {}, empty: {}, states: {}, sections: {}, back: {}, layouts: {}};

async function creatorChecks(client) {
    const page = await open(client, "/profile.html", {token: fx.creatorToken});
    await page.evaluate(`__t.until(() => __t.visible(document.getElementById("creatorStatsNav")))`);
    const navVisible = await page.evaluate(`__t.visible(document.getElementById("creatorStatsNav"))`);
    await page.evaluate(`document.getElementById("creatorStatsNav").click()`);
    await page.evaluate(`__t.ready()`);
    const shown = await page.evaluate(READ);
    shown.navVisible = navVisible;
    shown.privateListed = String(fx.privateRoutine) in shown.copies;
    Object.assign(shown, await page.evaluate(`(() => {
        const root = document.getElementById("creatorStatsApp");
        const item = root.querySelector('.cs-routine[data-routine-id="${Number(fx.second)}"] .cs-routine__title');
        return {
            hostileAsText: !!item && item.textContent === ${JSON.stringify(fx.hostile)},
            xss: window.__xss !== undefined,
            injectedElements: root.querySelectorAll("img, script, iframe").length
        };
    })()`));
    await page.snap("creator");
    await page.close();

    const linked = await open(client, "/profile.html#creator-stats", {token: fx.creatorToken});
    await linked.evaluate(`__t.ready()`);
    shown.openedFromAddress = (await linked.evaluate(READ)).sectionShown;
    await linked.close();
    results.creator = shown;
}

async function copyChecks(client) {
    const page = await open(client, `/routine.html?id=${fx.first}`, {token: fx.copierToken});
    await page.evaluate(`__t.until(() => __t.visible(document.getElementById("copyRoutineBtn")))`);
    await page.evaluate(`document.getElementById("copyRoutineBtn").click()`);
    results.copied.standardNotice = await page.evaluate(`(async () => {
        const notice = await __t.until(() => { const n = document.getElementById("copyNotice"); return n && __t.text(n) ? n : null; });
        return __t.text(notice);
    })()`);
    await page.evaluate(`document.getElementById("adaptCopyBtn").click()`);
    await page.evaluate(`__t.until(() => document.getElementById("adaptCopyModal").classList.contains("show"))`);
    await page.evaluate(`document.getElementById("adaptCopySave").click()`);
    results.copied.adaptedNotice = await page.evaluate(`(async () => {
        const notice = await __t.until(() => { const n = document.getElementById("copyNotice"); return n && __t.text(n).includes("adapted") ? n : null; });
        return __t.text(notice);
    })()`);
    await page.snap("copied");
    await page.close();

    const stats = await open(client, "/profile.html#creator-stats", {token: fx.creatorToken});
    await stats.evaluate(`__t.ready()`);
    const after = await stats.evaluate(READ);
    results.copied.copiesAfter = after.copies;
    results.copied.totalAfter = Number(after.overview[1]);
    await stats.close();
}

async function stateChecks(client) {
    const empty = await open(client, "/profile.html#creator-stats", {token: fx.emptyToken});
    await empty.evaluate(`__t.ready()`);
    results.empty = await empty.evaluate(READ);
    await empty.snap("empty");
    await empty.close();

    const page = await open(client, "/profile.html", {token: fx.quietToken});
    await page.evaluate(`__t.until(() => __t.visible(document.getElementById("creatorStatsNav")))`);
    await page.evaluate(`__t.statsDelay = 1500; document.getElementById("creatorStatsNav").click()`);
    await pause(400);
    results.states.loadingText = await page.evaluate(`(() => {
        const status = document.querySelector("#creatorStatsApp .cs-status");
        return status && !status.hidden ? __t.text(status) : "";
    })()`);
    await page.evaluate(`__t.ready()`);
    results.states.noCopiesNote = (await page.evaluate(READ)).noCopiesNote;

    // Opening the section again reloads it; this time the server fails, then Try again works.
    await page.evaluate(`__t.statsDelay = 0; __t.statsFail = true; document.getElementById("postsNav").click()`);
    await pause(300);
    await page.evaluate(`document.getElementById("creatorStatsNav").click()`);
    results.states.errorText = await page.evaluate(`(async () => {
        const error = await __t.until(() => { const e = document.querySelector("#creatorStatsApp .cs-error"); return e && !e.hidden ? e : null; });
        return __t.text(error && error.querySelector(".cs-error__text"));
    })()`);
    await page.snap("error");
    await page.evaluate(`__t.statsFail = false; document.querySelector("#creatorStatsApp .cs-error button").click()`);
    results.states.retried = await page.evaluate(`(async () => {
        const body = await __t.until(() => { const b = document.querySelector("#creatorStatsApp .cs-body"); return b && !b.hidden ? b : null; });
        return !!body && document.querySelector("#creatorStatsApp .cs-error").hidden;
    })()`);
    await page.close();
}

const SECTIONS = {
    calendarNav: "calendarContent",
    myRoutinesNav: "accountContent",
    myHabitsNav: "habitsContent",
    progressNav: "progressContent",
    creatorStatsNav: "creatorStatsContent",
    aiAssistantNav: "aiAssistantContent",
    friendsNav: "friendsContent",
    todoListsNav: "todoListsContent",
    postsNav: "postsContent",
    settingsNav: "themeSettingsContent"
};

async function sectionChecks(client) {
    const page = await open(client, "/profile.html", {token: fx.creatorToken});
    await page.evaluate(`__t.until(() => __t.visible(document.getElementById("creatorStatsNav")))`);
    results.navOrder = await page.evaluate(`[...document.querySelectorAll(".profile-navigation .profile-nav-btn")].map((el) => el.id)`);
    for (const [nav, content] of Object.entries(SECTIONS)) {
        await page.evaluate(`document.getElementById(${JSON.stringify(nav)}).click()`);
        await pause(600);
        results.sections[nav] = await page.evaluate(`(() => {
            const ids = ${JSON.stringify(Object.values(SECTIONS))};
            const shown = ids.filter((id) => getComputedStyle(document.getElementById(id)).display !== "none");
            return {
                shown: shown.includes(${JSON.stringify(content)}),
                visibleSections: shown.length,
                visible: shown,
                active: document.getElementById(${JSON.stringify(nav)}).classList.contains("active")
            };
        })()`);
    }

    // Back and Forward follow the section addresses.
    await page.evaluate(`document.getElementById("postsNav").click()`);
    await pause(400);
    await page.evaluate(`document.getElementById("creatorStatsNav").click()`);
    await page.evaluate(`__t.ready()`);
    await page.evaluate(`history.back()`);
    await pause(800);
    results.back.statsHiddenAfterBack = await page.evaluate(
        `getComputedStyle(document.getElementById("creatorStatsContent")).display === "none" && location.hash === "#posts"`);
    await page.evaluate(`history.forward()`);
    await pause(800);
    results.back.statsShownAfterForward = await page.evaluate(
        `getComputedStyle(document.getElementById("creatorStatsContent")).display !== "none" && location.hash === "#creator-stats"`);
    await page.close();
}

async function layoutChecks(client) {
    for (const theme of ["original", "autumn"]) {
        for (const width of [360, 390, 768, 1280, 1366, 1920]) {
            const page = await open(client, "/profile.html#creator-stats", {token: fx.creatorToken, width, theme});
            await page.evaluate(`__t.ready()`);
            const view = await page.evaluate(LAYOUT);
            view.theme = await page.evaluate(`document.documentElement.getAttribute("data-theme") || ""`);
            view.themeApplied = view.theme === theme;
            await page.snap(`layout-${theme}-${width}`);
            results.layouts[`${theme}-${width}`] = view;
            await page.close();
        }

        // The empty state too: no numbers yet, and a link to My Routines.
        const empty = await open(client, "/profile.html#creator-stats", {token: fx.emptyToken, width: 390, theme});
        await empty.evaluate(`__t.ready()`);
        const view = await empty.evaluate(LAYOUT);
        view.theme = await empty.evaluate(`document.documentElement.getAttribute("data-theme") || ""`);
        view.themeApplied = view.theme === theme;
        await empty.snap(`layout-empty-${theme}`);
        results.layouts[`empty-${theme}-390`] = view;
        await empty.close();
    }
}

const profile = mkdtempSync(join(tmpdir(), "cml-stats-"));
const {chrome, endpoint} = await startChrome(profile);
try {
    const client = await connect(endpoint);
    await creatorChecks(client);
    await stateChecks(client);
    await sectionChecks(client);
    await layoutChecks(client);
    // Last: it changes the creator's numbers.
    await copyChecks(client);
    client.close();
} finally {
    chrome.kill();
    await pause(500);
    try {
        rmSync(profile, {recursive: true, force: true});
    } catch (ignored) {
        // Chrome may still hold a file for a moment; the folder is in the temp directory.
    }
}

results.errors = errors;
results.dialogs = dialogs;
writeFileSync(resultsPath, JSON.stringify(results, null, 2));
