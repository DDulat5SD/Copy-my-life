// Manages routine habits (pause, resume, reorder, remove) in headless Chrome the way a user would.
// Started by HabitManagementRegressionTest; talks only to 127.0.0.1.
//
// Usage: node habit-management-check.mjs <chrome> <fixtures.json> <results.json>
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
// Optional: set HABITS_SCREENSHOTS to a folder to keep a picture of each state for a visual check.
const screenshots = process.env.HABITS_SCREENSHOTS;

const STATE = `(() => {
    const visible = (el) => !!el && !el.hidden && el.offsetParent !== null;
    const text = (el) => (el?.textContent || "").trim().replace(/\\s+/g, " ");
    const items = [...document.querySelectorAll("#routineHabitsEditor .routine-habit-edit-item")];
    const details = [...document.querySelectorAll("#habitsList li.habit")];
    const message = document.getElementById("editHabitsMessage");
    return {
        modalOpen: document.getElementById("editHabitsModal")?.classList.contains("show") || false,
        titles: items.map((item) => text(item.querySelector(".routine-habit-edit-info strong"))),
        pauseLabels: items.map((item) => text(item.querySelector("[data-action=pause], [data-action=resume]"))),
        badges: items.map((item) => text(item.querySelector(".routine-habit-paused-badge"))),
        confirmVisible: [...document.querySelectorAll(".routine-habit-confirm")].some(visible),
        confirmText: text(document.querySelector(".routine-habit-confirm span")),
        message: visible(message) ? text(message) : "",
        detailTitles: details.map((li) => text(li.querySelector(".habit__title"))),
        detailBadges: details.map((li) => text(li.querySelector(".habit__paused"))),
        habitsCount: text(document.getElementById("habitsCount"))
    };
})()`;

// A user can only press what they can see.
const press = (row, action) => `(async () => {
    const item = document.querySelectorAll("#routineHabitsEditor .routine-habit-edit-item")[${row}];
    const button = item && item.querySelector('[data-action="${action}"]');
    if (!button || button.disabled || button.offsetParent === null) {
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
    const controls = [...document.querySelectorAll(
        "#routineHabitsEditor button, .routine-habit-paused-badge, .habit__paused")]
        .filter(visible)
        .filter((el) => el.tagName !== "BUTTON" || !el.disabled);
    const buttons = controls.filter((el) => el.tagName === "BUTTON");
    return {
        width: innerWidth,
        theme: document.documentElement.getAttribute("data-theme") || "",
        rendered: document.getElementById("editHabitsModal").classList.contains("show") && buttons.length > 0,
        overflow: document.documentElement.scrollWidth - innerWidth,
        rightmostControl: Math.round(Math.max(0, ...controls.map((el) => el.getBoundingClientRect().right))),
        smallestTarget: Math.round(Math.min(...buttons.map((el) => el.getBoundingClientRect().height))),
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
    await client.send("Page.navigate", {url: fx.base + path}, sessionId);
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
            const {data} = await client.send("Page.captureScreenshot", {format: "png"}, sessionId);
            writeFileSync(join(screenshots, `${name}.png`), Buffer.from(data, "base64"));
        },
        close: () => client.send("Target.closeTarget", {targetId})
    };
}

const routinePage = (id) => `/routine.html?id=${id}`;

const profile = mkdtempSync(join(tmpdir(), "cml-habits-"));
const {chrome, endpoint} = await startChrome(profile);

try {
    const client = await connect(endpoint);
    const results = {};

    const main = await open(client, {token: fx.mainToken, path: routinePage(fx.mainRoutine)});
    await main.evaluate(pressId("editHabitsBtn"));
    results.editorOpen = await main.evaluate(STATE);
    await main.evaluate(press(1, "pause"));
    results.afterPause = await main.evaluate(STATE);
    await main.snap("editor-paused");
    await main.reload();
    results.pausedAfterReload = await main.evaluate(STATE);
    await main.snap("details-paused");
    await main.evaluate(pressId("editHabitsBtn"));
    await main.evaluate(press(1, "resume"));
    results.afterResume = await main.evaluate(STATE);
    await main.evaluate(press(0, "down"));
    results.afterMoveDown = await main.evaluate(STATE);
    await main.reload();
    results.orderAfterReload = await main.evaluate(STATE);
    await main.evaluate(pressId("editHabitsBtn"));
    await main.evaluate(press(1, "remove"));
    results.removeAsked = await main.evaluate(STATE);
    await main.snap("editor-remove-confirm");
    await main.evaluate(press(1, "cancel-remove"));
    results.removeCancelled = await main.evaluate(STATE);
    await main.evaluate(press(1, "remove"));
    await main.evaluate(press(1, "confirm-remove"));
    results.afterRemove = await main.evaluate(STATE);
    await main.close();

    const history = await open(client, {token: fx.historyToken, path: routinePage(fx.historyRoutine)});
    await history.evaluate(pressId("editHabitsBtn"));
    await history.evaluate(press(0, "remove"));
    await history.evaluate(press(0, "confirm-remove"));
    results.removeWithHistory = await history.evaluate(STATE);
    await history.snap("editor-removed-with-history");
    await history.reload();
    results.removeWithHistoryReload = await history.evaluate(STATE);
    await history.close();

    const habits = await open(client, {token: fx.halloweenToken, path: "/profile.html"});
    await habits.evaluate(pressId("myHabitsNav"));
    await pause(1200);
    results.myHabits = await habits.evaluate(`(() => ({
        badges: [...document.querySelectorAll("#habitsGrid .habit-paused-badge")].map((el) => el.textContent.trim())
    }))()`);
    await habits.snap("my-habits");
    await habits.close();

    if (fx.builtInRoutine) {
        const builtIn = await open(client, {token: fx.viewerToken, path: routinePage(fx.builtInRoutine)});
        results.builtInHalloween = await builtIn.evaluate(`(() => {
            const edit = document.getElementById("editHabitsBtn");
            return {
                loaded: document.querySelectorAll("#habitsList li.habit").length > 0,
                halloweenTheme: document.body.classList.contains("halloween-detail-page"),
                editHabitsVisible: !!edit && !edit.hidden && edit.offsetParent !== null,
                pausedBadges: document.querySelectorAll(".habit__paused, .routine-habit-paused-badge").length
            };
        })()`);
        await builtIn.close();
    } else {
        results.builtInHalloween = {loaded: false};
    }

    const layouts = {
        originalPhone: {token: fx.mainToken, path: routinePage(fx.mainRoutine), width: 390, theme: "original"},
        autumnPhone: {token: fx.halloweenToken, path: routinePage(fx.halloweenRoutine), width: 390, theme: "autumn"},
        halloweenPhone: {token: fx.halloweenToken, path: routinePage(fx.halloweenRoutine), width: 390, theme: "original"},
        autumnDesktop: {token: fx.halloweenToken, path: routinePage(fx.halloweenRoutine), width: 1280, theme: "autumn"}
    };
    for (const [name, options] of Object.entries(layouts)) {
        const view = await open(client, options);
        await view.evaluate(pressId("editHabitsBtn"));
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
