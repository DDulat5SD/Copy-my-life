// US-19: opens someone's public routine in headless Chrome, adapts its activities before copying,
// cancels, sends invalid values, saves with a double click and opens the copy; then checks the form
// in both designs from a 360 px phone to a 1920 px desktop.
// Started by AdaptCopiedRoutineRegressionTest; talks only to 127.0.0.1.
//
// Usage: node adapt-copy-check.mjs <chrome> <fixtures.json> <results.json>
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
// Optional: set ADAPT_SCREENSHOTS to a folder to keep a picture of each layout.
const screenshots = process.env.ADAPT_SCREENSHOTS;

const HELPERS = `
    // Tabs share localStorage, and another tab may change the token later; keep this tab's own.
    window.__a = {
        token: localStorage.getItem("token"),
        wait: (ms) => new Promise((done) => setTimeout(done, ms)),
        text: (el) => (el?.textContent || "").trim().replace(/\\s+/g, " "),
        visible: (el) => !!el && !el.hidden && el.offsetParent !== null,
        rows: () => [...document.querySelectorAll("#adaptCopyList .adapt-activity")],
        api: async (path) => (await fetch(path, {headers: {Authorization: "Bearer " + window.__a.token}})).json(),
        // The copier's routines, or why they could not be counted.
        count: async () => {
            const response = await fetch("/routines", {headers: {Authorization: "Bearer " + window.__a.token}});
            const body = await response.json();
            return Array.isArray(body) ? body.length : "status " + response.status + " " + JSON.stringify(body).slice(0, 120);
        },
        set: (input, value) => {
            input.value = value;
            input.dispatchEvent(new Event("input", {bubbles: true}));
            input.dispatchEvent(new Event("change", {bubbles: true}));
        },
        open: async () => {
            document.getElementById("adaptCopyBtn").click();
            await window.__a.wait(400);
        }
    };
`;

const FORM = `(() => {
    const a = window.__a;
    return {
        titles: a.rows().map((row) => a.text(row.querySelector(".adapt-activity-title"))),
        times: a.rows().map((row) => row.querySelector("input[name=startTime]").value),
        durations: a.rows().map((row) => row.querySelector("input[name=durationMinutes]").value),
        days: a.rows().map((row) => [...row.querySelectorAll("input[type=checkbox]:checked")].map((box) => box.value).join(",")),
        frequencies: a.rows().map((row) => a.text(row.querySelector(".adapt-frequency"))),
        total: a.text(document.getElementById("adaptCopyTotal")),
        xss: window.__xss === 1
    };
})()`;

const STATE = `(async () => {
    const a = window.__a;
    const error = document.getElementById("adaptCopyError");
    const highlighted = a.rows().findIndex((row) => row.classList.contains("adapt-activity--error"));
    return {
        modalOpen: document.getElementById("adaptCopyModal").classList.contains("show"),
        error: a.visible(error) ? a.text(error) : "",
        highlighted: highlighted + 1,
        keptTime: a.rows()[0]?.querySelector("input[name=startTime]").value || "",
        routinesAfter: await a.count()
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
        return {r: 255, g: 255, b: 255, a: 1};
    };
    const contrast = (el) => {
        const a = luminance(parse(getComputedStyle(el).color));
        const b = luminance(background(el));
        return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
    };
    const visible = (el) => !!el && !el.hidden && el.offsetParent !== null;
    const modal = document.querySelector("#adaptCopyModal .routine-edit-modal");
    const parts = [...modal.querySelectorAll("*")].filter(visible);
    const outside = parts.filter((el) => {
        const box = el.getBoundingClientRect();
        return box.width > 0 && (box.left < -1 || box.right > innerWidth + 1);
    });
    const inView = (el) => {
        const box = el.getBoundingClientRect();
        return visible(el) && box.left >= 0 && box.right <= innerWidth;
    };
    const texts = [...modal.querySelectorAll(
        "h2, .adapt-copy-close, .routine-habits-subtitle, .adapt-activity-title, .adapt-frequency, label, legend,"
        + " .day-chip span, #adaptCopyTotal, #adaptCopySave, #adaptCopyCancel")]
        .filter(visible).filter((el) => el.childNodes.length && [...el.childNodes].some((n) => n.nodeType === 3 && n.textContent.trim()));
    const faintest = texts.length ? texts.reduce((a, b) => contrast(a) <= contrast(b) ? a : b) : null;
    return {
        width: innerWidth,
        theme: document.documentElement.getAttribute("data-theme") || "",
        overflow: document.documentElement.scrollWidth - innerWidth,
        inside: outside.length === 0,
        outside: outside.slice(0, 3).map((el) => el.className || el.tagName).join(", "),
        actionsVisible: inView(document.getElementById("adaptCopySave")) && inView(document.getElementById("adaptCopyCancel")),
        measured: texts.length,
        minContrast: faintest ? Math.round(contrast(faintest) * 100) / 100 : 0,
        faintest: faintest ? (faintest.id || faintest.className || faintest.tagName) + " " + faintest.textContent.trim().slice(0, 20) : ""
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

async function open(client, {token = null, width = 1280, theme = "original", routine = fx.source} = {}) {
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
    await client.send("Page.navigate", {url: `${fx.base}/routine.html?id=${routine}`}, sessionId);
    await pause(2500);

    const view = {
        evaluate: async (expression) => (await client.send("Runtime.evaluate", {
            expression, returnByValue: true, awaitPromise: true
        }, sessionId)).result.value,
        snap: async (name) => {
            if (!screenshots) {
                return;
            }
            const {data} = await client.send("Page.captureScreenshot", {format: "png"}, sessionId);
            writeFileSync(join(screenshots, `${name}.png`), Buffer.from(data, "base64"));
        },
        close: () => client.send("Target.closeTarget", {targetId})
    };
    return view;
}

const count = `window.__a.count()`;
const isShown = (id) => `window.__a.visible(document.getElementById(${JSON.stringify(id)}))`;

const profile = mkdtempSync(join(tmpdir(), "cml-adapt-"));
const {chrome, endpoint} = await startChrome(profile);

try {
    const client = await connect(endpoint);
    const results = {};

    // Who sees Adapt.
    const copier = await open(client, {token: fx.copierToken});
    results.buttons = {
        copyVisible: await copier.evaluate(isShown("copyRoutineBtn")),
        adaptVisible: await copier.evaluate(isShown("adaptCopyBtn"))
    };
    const owner = await open(client, {token: fx.authorToken});
    results.buttons.ownerAdaptVisible = await owner.evaluate(isShown("adaptCopyBtn"));
    await owner.close();
    const guest = await open(client);
    await guest.evaluate(`document.getElementById("adaptCopyBtn").click(); true`);
    await pause(300);
    results.buttons.guestNotice = await guest.evaluate(`window.__a.text(document.getElementById("copyNotice"))`);
    await guest.close();

    // The form, filled with the source's values.
    await copier.evaluate(`window.__a.open()`);
    results.form = await copier.evaluate(FORM);
    await copier.snap("form");

    // Cancel saves nothing.
    const before = await copier.evaluate(count);
    await copier.evaluate(`(async () => {
        window.__a.set(window.__a.rows()[0].querySelector("input[name=durationMinutes]"), "45");
        document.getElementById("adaptCopyCancel").click();
        await window.__a.wait(800);
        return true;
    })()`);
    results.cancel = {routinesBefore: before, ...await copier.evaluate(STATE)};

    // A value the page can tell is wrong: refused before sending, and what was typed stays.
    await copier.evaluate(`window.__a.open()`);
    await copier.evaluate(`(async () => {
        const row = window.__a.rows()[0];
        window.__a.set(row.querySelector("input[name=startTime]"), "05:45");
        window.__a.set(row.querySelector("input[name=durationMinutes]"), "0");
        document.getElementById("adaptCopySave").click();
        await window.__a.wait(800);
        return true;
    })()`);
    results.invalidLocal = {routinesBefore: before, ...await copier.evaluate(STATE)};
    await copier.snap("invalid-local");
    await copier.evaluate(`document.getElementById("adaptCopyCancel").click(); true`);

    // One only the server can tell: two activities sharing minutes.
    await copier.evaluate(`window.__a.open()`);
    await copier.evaluate(`(async () => {
        window.__a.set(window.__a.rows()[1].querySelector("input[name=startTime]"), "07:10");
        document.getElementById("adaptCopySave").click();
        await window.__a.wait(1500);
        return true;
    })()`);
    results.invalidServer = {routinesBefore: before, ...await copier.evaluate(STATE)};
    await copier.snap("invalid-server");
    await copier.evaluate(`document.getElementById("adaptCopyCancel").click(); true`);

    // A valid copy, saved with a double click, then opened.
    const sourceBefore = await copier.evaluate(`(async () => JSON.stringify(await window.__a.api("/routines/${fx.source}/routine-habits")))()`);
    await copier.evaluate(`window.__a.open()`);
    await copier.evaluate(`(async () => {
        const row = window.__a.rows()[0];
        window.__a.set(row.querySelector("input[name=startTime]"), "06:15");
        window.__a.set(row.querySelector("input[name=durationMinutes]"), "25");
        for (const box of row.querySelectorAll("input[type=checkbox]")) {
            const want = ["MON", "WED", "FRI"].includes(box.value);
            if (box.checked !== want) {
                box.click();
            }
        }
        const save = document.getElementById("adaptCopySave");
        save.click();
        save.click();
        await window.__a.wait(2000);
        return true;
    })()`);
    // An empty result when the page has no such form (as before US-19), so the run still ends.
    const saved = (await copier.evaluate(`(async () => {
        const a = window.__a;
        const link = document.querySelector("#copyNotice a[href^='routine.html?id=']");
        const id = link ? link.getAttribute("href").split("=")[1] : null;
        return {
            routinesAfter: await a.count(),
            notice: a.text(document.getElementById("copyNotice")),
            openHref: link ? link.getAttribute("href") : "",
            modalOpen: document.getElementById("adaptCopyModal").classList.contains("show"),
            copyPlan: id ? await a.api("/routines/" + id + "/routine-habits") : [],
            sourceAfter: JSON.stringify(await a.api("/routines/${fx.source}/routine-habits"))
        };
    })()`)) || {};
    results.save = {routinesBefore: before, sourceBefore, ...saved};
    await copier.snap("saved");
    await copier.close();
    if (saved.openHref) {
        const copyId = saved.openHref.split("=")[1];
        const copyPage = await open(client, {token: fx.copierToken, routine: copyId});
        results.save.copyPageTitle = await copyPage.evaluate(`window.__a.text(document.getElementById("routineTitle"))`);
        await copyPage.close();
    }

    results.layouts = {};
    for (const width of [360, 390, 1280, 1366, 1920]) {
        for (const theme of ["original", "autumn"]) {
            const view = await open(client, {token: fx.copierToken, width, theme});
            await view.evaluate(`window.__a.open()`);
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
