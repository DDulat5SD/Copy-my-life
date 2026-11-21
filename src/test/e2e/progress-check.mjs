// US-21: opens the profile's Progress section in headless Chrome and reads the completion
// percentage, the streaks and the days of the week next to what /progress answers, for each
// period, for a user with records and one without, and the layout in both designs from a
// 360 px phone to a 1920 px desktop. US-07: the status card, also for a user at the last level.
// Nothing is written.
// Started by ProgressAndStreaksRegressionTest; talks only to 127.0.0.1.
//
// Usage: node progress-check.mjs <chrome> <fixtures.json> <results.json>
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
// Optional: set PROGRESS_SCREENSHOTS to a folder to keep a picture of each layout.
const screenshots = process.env.PROGRESS_SCREENSHOTS;

// Every /progress request the page makes, with its headers, kept in the page.
const RECORD = `(() => {
    const original = window.fetch.bind(window);
    window.__progress = [];
    window.fetch = (input, init) => {
        const url = typeof input === "string" ? input : input.url;
        if (url.startsWith("/progress")) {
            window.__progress.push({url, headers: (init && init.headers) || {}});
        }
        return original(input, init);
    };
})();`;

// What the Progress section shows, and what the server answers for the page's last request.
const READ = `(async () => {
    const visible = (el) => !!el && !el.hidden && el.offsetParent !== null;
    const text = (el) => (el?.textContent || "").trim().replace(/\\s+/g, " ");
    const all = (selector) => [...document.querySelectorAll(selector)].filter(visible).map(text);
    const last = window.__progress[window.__progress.length - 1];
    const api = last ? await (await fetch(last.url, {headers: last.headers})).json() : null;
    return {
        api,
        request: last ? JSON.stringify(last) : "",
        completion: text(document.getElementById("progressCompletionValue")),
        label: text(document.getElementById("progressCompletionLabel")),
        streak: text(document.getElementById("progressStreakValue")),
        streakDetail: text(document.getElementById("progressStreakDetail")),
        weekValue: text(document.getElementById("progressWeekValue")),
        emptyVisible: visible(document.getElementById("progressEmpty")),
        emptyText: text(document.getElementById("progressEmpty")),
        dayNames: all("#progressWeek .progress-day-name"),
        dayCounts: all("#progressWeek .progress-day-count"),
        streakNames: all("#progressStreakList .progress-streak-name"),
        habitPercents: all("#habitProgressList .progress-stat-percent"),
        statusVisible: visible(document.getElementById("progressStatus")),
        statusLevel: text(document.getElementById("progressStatusLevel")),
        statusName: text(document.getElementById("progressStatusName")),
        statusCount: text(document.getElementById("progressStatusCount")),
        statusFill: document.getElementById("progressStatusFill")?.style.width || "",
        statusNext: text(document.getElementById("progressStatusNext")),
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
    const section = document.getElementById("progressContent");
    const boxes = [...section.querySelectorAll("*")].filter(visible);
    const outside = boxes.filter((el) => {
        const box = el.getBoundingClientRect();
        return box.width > 0 && (box.left < -1 || box.right > innerWidth + 1);
    });
    const texts = [...section.querySelectorAll(
        ".progress-summary-label, .progress-summary-value, .progress-summary-detail, .progress-day-name,"
        + " .progress-day-date, .progress-day-count, .progress-streak-name, .progress-streak-days, .progress-week-note,"
        + " .progress-panel-header h2, .progress-panel-header p, .progress-stat-percent, .progress-stat-count,"
        + " .progress-status-level, .progress-status-title h2, .progress-status-count strong, .progress-status-count span,"
        + " .progress-status-next")]
        .filter(visible).filter((el) => el.textContent.trim());
    const faintest = texts.length ? texts.reduce((a, b) => contrast(a) <= contrast(b) ? a : b) : null;
    const days = [...section.querySelectorAll("#progressWeek .progress-day")].filter(visible);
    return {
        width: innerWidth,
        theme: document.documentElement.getAttribute("data-theme") || "",
        overflow: document.documentElement.scrollWidth - innerWidth,
        inside: outside.length === 0,
        outside: outside.slice(0, 3).map((el) => el.className || el.tagName).join(", "),
        days: days.length,
        daysOneRow: new Set(days.map((el) => Math.round(el.getBoundingClientRect().top))).size === 1,
        measured: texts.length,
        minContrast: faintest ? Math.round(contrast(faintest) * 100) / 100 : 0,
        faintest: faintest ? (faintest.className || faintest.tagName) + " " + faintest.textContent.trim().slice(0, 20) : ""
    };
})()`;

const click = (selector) => `(async () => {
    document.querySelector(${JSON.stringify(selector)}).click();
    await new Promise((done) => setTimeout(done, 1200));
    return true;
})()`;

const shown = (id) => `(() => { const el = document.getElementById(${JSON.stringify(id)}); return !!el && el.offsetParent !== null; })()`;

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
            if (message.id && pending.has(message.id)) {
                const {ok, fail} = pending.get(message.id);
                pending.delete(message.id);
                message.error ? fail(new Error(message.error.message)) : ok(message.result);
            }
        };
        socket.onopen = () => resolve({send, close: () => socket.close()});
    });
}

async function open(client, {token, width = 1280, theme = "original"}) {
    const {targetId} = await client.send("Target.createTarget", {url: "about:blank"});
    const {sessionId} = await client.send("Target.attachToTarget", {targetId, flatten: true});

    await client.send("Page.enable", {}, sessionId);
    await client.send("Runtime.enable", {}, sessionId);
    // The user's own zone, so that the page's "today" is the fixtures' today.
    await client.send("Emulation.setTimezoneOverride", {timezoneId: fx.zone}, sessionId);
    await client.send("Emulation.setDeviceMetricsOverride", {
        width, height: width < 600 ? 844 : 900, deviceScaleFactor: width < 600 ? 2 : 1, mobile: width < 600
    }, sessionId);
    await client.send("Page.addScriptToEvaluateOnNewDocument", {
        source: `localStorage.setItem("token", ${JSON.stringify(token)});`
            + `localStorage.setItem("copymylife-theme", ${JSON.stringify(theme)});`
            + RECORD
    }, sessionId);
    await client.send("Page.navigate", {url: fx.base + "/profile.html#progress"}, sessionId);
    await pause(3000);

    const view = {
        evaluate: async (expression) => (await client.send("Runtime.evaluate", {
            expression, returnByValue: true, awaitPromise: true
        }, sessionId)).result.value,
        read: () => view.evaluate(READ),
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

const profile = mkdtempSync(join(tmpdir(), "cml-progress-"));
const {chrome, endpoint} = await startChrome(profile);

try {
    const client = await connect(endpoint);
    const results = {};

    const owner = await open(client, {token: fx.ownerToken});
    results.owner = await owner.read();
    await owner.snap("owner");

    results.periods = {};
    for (const period of ["week", "month", "year", "day"]) {
        await owner.evaluate(click(`.progress-period-btn[data-period="${period}"]`));
        results.periods[period] = await owner.read();
    }

    results.sections = {};
    await owner.evaluate(click("#calendarNav"));
    results.sections.calendarShown = await owner.evaluate(shown("calendarContent"));
    results.sections.progressShownWithCalendar = await owner.evaluate(shown("progressContent"));
    await owner.evaluate(click("#progressNav"));
    results.sections.progressBack = await owner.evaluate(shown("progressContent"));
    await owner.evaluate(click("#myHabitsNav"));
    results.sections.habitsShown = await owner.evaluate(shown("habitsContent"));
    await owner.close();

    const empty = await open(client, {token: fx.newcomerToken});
    results.empty = await empty.read();
    await empty.snap("empty");
    await empty.close();

    const master = await open(client, {token: fx.masterToken, width: 390, theme: "autumn"});
    results.master = await master.read();
    await master.snap("master");
    await master.close();

    results.layouts = {};
    for (const width of [360, 390, 1280, 1366, 1920]) {
        for (const theme of ["original", "autumn"]) {
            const view = await open(client, {token: fx.ownerToken, width, theme});
            results.layouts[`${theme}${width}`] = await view.evaluate(LAYOUT);
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
