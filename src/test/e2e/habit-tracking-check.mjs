// Tracks a routine on routine-run.html in headless Chrome the way a user would.
// Started by DailyHabitTrackingRegressionTest; talks only to 127.0.0.1.
//
// Usage: node habit-tracking-check.mjs <chrome> <fixtures.json> <results.json>
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
    const visible = (el) => !!el && !el.hidden && el.offsetParent !== null;
    const text = (id) => (document.getElementById(id)?.textContent || "").trim().replace(/\\s+/g, " ");
    const rows = [...document.querySelectorAll("#runHabits > li")].map((li) => {
        const buttons = [...li.querySelectorAll(".run-habit__action button")].filter(visible);
        return {
            status: li.dataset.status || "",
            badge: (li.querySelector(".run-status-badge")?.textContent || "").trim(),
            buttons: buttons.map((b) => b.textContent.trim().replace(/\\s+/g, " ")),
            enabled: buttons.some((b) => !b.disabled)
        };
    });
    const message = document.getElementById("runMessage");
    return {
        title: text("progressTitle"),
        count: text("progressCount"),
        summary: text("progressSummary"),
        bar: document.getElementById("progressBar")?.style.width || "",
        stopVisible: visible(document.getElementById("stopRoutineBtn")),
        confirmVisible: visible(document.getElementById("stopConfirm")),
        message: visible(message) ? message.textContent.trim() : "",
        rows,
        rowStatus: rows[0]?.status || "",
        enabledButtons: [...document.querySelectorAll("#runHabits button")].filter((b) => visible(b) && !b.disabled).length
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

const pressId = (id) => `(async () => {
    const button = document.getElementById(${JSON.stringify(id)});
    if (!button || button.hidden || button.disabled || button.offsetParent === null) {
        return false;
    }
    button.click();
    await new Promise((done) => setTimeout(done, 900));
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
    const controls = [...document.querySelectorAll("#runHabits button, #stopRoutineBtn, .run-status-badge")]
        .filter(visible)
        .filter((el) => el.tagName !== "BUTTON" || !el.disabled);
    const buttons = controls.filter((el) => el.tagName === "BUTTON");
    return {
        width: innerWidth,
        theme: document.documentElement.getAttribute("data-theme") || "",
        rendered: document.querySelectorAll("#runHabits > li").length > 0 && controls.length > 0,
        overflow: document.documentElement.scrollWidth - innerWidth,
        rightmostButton: Math.round(Math.max(0, ...buttons.map((b) => b.getBoundingClientRect().right))),
        smallestTarget: Math.round(Math.min(...buttons.map((b) => b.getBoundingClientRect().height))),
        minContrast: Math.round(Math.min(...controls.map(contrast)) * 100) / 100
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

async function open(client, {token, routine, width = 1280, theme = "original"}) {
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
    await client.send("Page.navigate", {url: `${fx.base}/routine-run.html?id=${routine}`}, sessionId);
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

const profile = mkdtempSync(join(tmpdir(), "cml-tracking-"));
const {chrome, endpoint} = await startChrome(profile);

try {
    const client = await connect(endpoint);
    const results = {};

    const main = await open(client, {token: fx.mainToken, routine: fx.mainRoutine});
    results.initial = await main.evaluate(STATE);
    await main.evaluate(press(0, "Complete"));
    results.afterComplete = await main.evaluate(STATE);
    await main.evaluate(press(1, "Postpone"));
    results.afterPostpone = await main.evaluate(STATE);
    await main.evaluate(press(2, "Skip"));
    results.afterSkip = await main.evaluate(STATE);
    await main.snap("desktop-original-mixed");
    await main.reload();
    results.afterReload = await main.evaluate(STATE);
    await main.evaluate(press(1, "Resume"));
    results.afterResume = await main.evaluate(STATE);
    await main.evaluate(pressId("stopRoutineBtn"));
    results.stopConfirm = await main.evaluate(STATE);
    await main.snap("desktop-original-stop-confirm");
    await main.evaluate(pressId("stopCancelBtn"));
    results.stopCancelled = await main.evaluate(STATE);
    await main.evaluate(pressId("stopRoutineBtn"));
    await main.evaluate(pressId("stopConfirmBtn"));
    results.afterStop = await main.evaluate(STATE);
    await main.snap("desktop-original-stopped");
    await main.reload();
    results.stoppedReload = await main.evaluate(STATE);
    await main.close();

    const failing = await open(client, {token: fx.failingToken, routine: fx.failingRoutine});
    await failing.evaluate(`window.__realFetch = window.fetch;
        window.fetch = () => Promise.reject(new TypeError("Failed to fetch"));`);
    await failing.evaluate(press(0, "Start"));
    results.failedAction = await failing.evaluate(STATE);
    await failing.evaluate(`window.fetch = () => Promise.resolve(new Response(
        JSON.stringify({message: "Start the routine first"}),
        {status: 400, headers: {"Content-Type": "application/json"}}));`);
    await failing.evaluate(press(0, "Skip"));
    results.refusedAction = await failing.evaluate(STATE);
    await failing.snap("desktop-original-error");
    await failing.evaluate(`window.fetch = window.__realFetch;`);
    await failing.close();

    const finished = await open(client, {token: fx.finishedToken, routine: fx.finishedRoutine});
    results.finishedRoutine = await finished.evaluate(STATE);
    await finished.close();

    const layouts = {
        originalPhone: {token: fx.failingToken, routine: fx.failingRoutine, width: 390, theme: "original"},
        autumnPhone: {token: fx.failingToken, routine: fx.failingRoutine, width: 390, theme: "autumn"},
        halloweenPhone: {token: fx.halloweenToken, routine: fx.halloweenRoutine, width: 390, theme: "original"},
        autumnDesktop: {token: fx.failingToken, routine: fx.failingRoutine, width: 1280, theme: "autumn"},
        halloweenDesktop: {token: fx.halloweenToken, routine: fx.halloweenRoutine, width: 1280, theme: "autumn"}
    };
    for (const [name, options] of Object.entries(layouts)) {
        const view = await open(client, options);
        results[name] = await view.evaluate(LAYOUT);
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
