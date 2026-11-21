// Checks today's run on routine-run.html and a day with nothing planned on routine.html,
// in headless Chrome. Started by TodaysRoutineRunRegressionTest; talks only to 127.0.0.1.
//
// Usage: node todays-run-check.mjs <chrome> <fixtures.json> <results.json>
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

const RUN_STATE = `(() => {
    const text = (el) => (el?.textContent || "").trim().replace(/\\s+/g, " ");
    const rows = [...document.querySelectorAll("#runHabits > li")].map((li) => ({
        status: li.dataset.status || "",
        title: text(li.querySelector(".run-habit__title")),
        time: text(li.querySelector(".run-habit__time"))
    }));
    return {
        titles: rows.map((row) => row.title),
        rows,
        count: text(document.getElementById("progressCount")),
        summary: text(document.getElementById("progressSummary")),
        habitsCount: text(document.getElementById("habitsCount"))
    };
})()`;

const START_STATE = `(() => {
    const button = document.getElementById("startBtn");
    const hint = document.getElementById("startHint");
    return {
        startVisible: !!button && !button.hidden && button.offsetParent !== null,
        startDisabled: !!button && button.disabled,
        startText: (document.getElementById("startBtnText")?.textContent || "").trim(),
        hint: hint && !hint.hidden ? hint.textContent.trim() : ""
    };
})()`;

// A user can only press what they can see.
const press = (row, label) => `(async () => {
    const li = document.querySelectorAll("#runHabits > li")[${row}];
    const button = li && [...li.querySelectorAll("button")].find((b) =>
        b.textContent.trim().startsWith(${JSON.stringify(label)}) && !b.disabled && b.offsetParent !== null);
    if (!button) {
        return false;
    }
    button.click();
    await new Promise((done) => setTimeout(done, 900));
    return true;
})()`;

// What must stay readable: each row's title and time on the run page, the hint on the routine page.
const layout = (selector) => `(() => {
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
    const checked = [...document.querySelectorAll(${JSON.stringify(selector)})].filter(visible);
    const buttons = [...document.querySelectorAll("button")].filter(visible);
    return {
        width: innerWidth,
        theme: document.documentElement.getAttribute("data-theme") || "",
        rendered: checked.length > 0,
        overflow: document.documentElement.scrollWidth - innerWidth,
        rightmost: Math.round(Math.max(0, ...[...checked, ...buttons].map((el) => el.getBoundingClientRect().right))),
        minContrast: Math.round(Math.min(...checked.map(contrast)) * 100) / 100
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

async function open(client, {token, page, width = 1280, theme = "original"}) {
    const {targetId} = await client.send("Target.createTarget", {url: "about:blank"});
    const {sessionId} = await client.send("Target.attachToTarget", {targetId, flatten: true});

    await client.send("Page.enable", {}, sessionId);
    await client.send("Runtime.enable", {}, sessionId);
    // "Today" is the browser's day; keep it the same day the test planned for.
    await client.send("Emulation.setTimezoneOverride", {timezoneId: fx.zone}, sessionId);
    await client.send("Emulation.setDeviceMetricsOverride", {
        width, height: width < 600 ? 844 : 900, deviceScaleFactor: width < 600 ? 2 : 1, mobile: width < 600
    }, sessionId);
    await client.send("Page.addScriptToEvaluateOnNewDocument", {
        source: `localStorage.setItem("token", ${JSON.stringify(token)});
                 localStorage.setItem("copymylife-theme", ${JSON.stringify(theme)});`
    }, sessionId);
    await client.send("Page.navigate", {url: `${fx.base}/${page}`}, sessionId);
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

const profile = mkdtempSync(join(tmpdir(), "cml-today-"));
const {chrome, endpoint} = await startChrome(profile);

try {
    const client = await connect(endpoint);
    const results = {};
    const runPage = `routine-run.html?id=${fx.todayRoutine}`;
    const routinePage = `routine.html?id=${fx.nothingRoutine}`;

    const today = await open(client, {token: fx.todayToken, page: runPage});
    results.initial = await today.evaluate(RUN_STATE);
    await today.evaluate(press(0, "Skip"));
    await today.evaluate(press(1, "Postpone"));
    await today.snap("today-original-desktop");
    await today.reload();
    results.afterReload = await today.evaluate(RUN_STATE);
    await today.close();

    const nothing = await open(client, {token: fx.nothingToken, page: routinePage});
    results.nothingPlanned = {...await nothing.evaluate(START_STATE), dialogs: dialogs.splice(0)};
    await nothing.snap("nothing-original-desktop");
    await nothing.close();

    // An empty or fully paused routine keeps Start; pressing it shows the server's reason.
    for (const name of ["emptyRoutine", "pausedRoutine"]) {
        const prefix = name.replace("Routine", "");
        const view = await open(client, {token: fx[`${prefix}Token`], page: `routine.html?id=${fx[name]}`});
        const before = await view.evaluate(START_STATE);
        await view.evaluate(`(async () => {
            document.getElementById("startBtn").click();
            await new Promise((done) => setTimeout(done, 1200));
        })()`);
        const leftPage = await view.evaluate(`!location.pathname.endsWith("/routine.html")`);
        results[name] = {...before, dialogs: dialogs.splice(0), leftPage};
        await view.close();
    }

    const rows = "#runHabits .run-habit__title, #runHabits .run-habit__time";
    const layouts = {
        originalPhone: {token: fx.todayToken, page: runPage, width: 390, theme: "original", check: rows},
        autumnPhone: {token: fx.todayToken, page: runPage, width: 390, theme: "autumn", check: rows},
        autumnDesktop: {token: fx.todayToken, page: runPage, width: 1280, theme: "autumn", check: rows},
        nothingOriginalPhone: {token: fx.nothingToken, page: routinePage, width: 390, theme: "original", check: "#startHint"},
        nothingAutumnPhone: {token: fx.nothingToken, page: routinePage, width: 390, theme: "autumn", check: "#startHint"}
    };
    for (const [name, options] of Object.entries(layouts)) {
        const view = await open(client, options);
        results[name] = await view.evaluate(layout(options.check));
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
