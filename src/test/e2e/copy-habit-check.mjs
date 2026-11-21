// US-18: opens someone's public routine in headless Chrome and copies one activity into one of the
// user's own routines: cancels, gets a refusal, saves with a double click and opens the target; shows
// the empty state to someone without routines; then checks the page and the form in both designs
// from a 360 px phone to a 1920 px desktop.
// Started by CopySingleHabitRegressionTest; talks only to 127.0.0.1.
//
// Usage: node copy-habit-check.mjs <chrome> <fixtures.json> <results.json>
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
// Optional: set COPY_HABIT_SCREENSHOTS to a folder to keep a picture of each layout.
const screenshots = process.env.COPY_HABIT_SCREENSHOTS;

const HELPERS = `
    // Tabs share localStorage, and another tab may change the token later; keep this tab's own.
    window.__c = {
        token: localStorage.getItem("token"),
        wait: (ms) => new Promise((done) => setTimeout(done, ms)),
        text: (el) => (el?.textContent || "").trim().replace(/\\s+/g, " "),
        visible: (el) => !!el && !el.hidden && el.offsetParent !== null,
        buttons: () => [...document.querySelectorAll("#habitsList [data-copy-habit]")],
        api: async (path) => (await fetch(path, {headers: {Authorization: "Bearer " + window.__c.token}})).json(),
        // How many activities a routine has, or why they could not be counted.
        count: async (routine) => {
            const response = await fetch("/routines/" + routine + "/routine-habits",
                {headers: {Authorization: "Bearer " + window.__c.token}});
            const body = await response.json();
            return Array.isArray(body) ? body.length : "status " + response.status + " " + JSON.stringify(body).slice(0, 120);
        },
        choose: (value) => {
            const select = document.getElementById("copyHabitTarget");
            select.value = String(value);
            select.dispatchEvent(new Event("change", {bubbles: true}));
        },
        // Opens the form for one activity and waits for the user's routines to load.
        open: async (step) => {
            document.querySelector("#habitsList [data-copy-habit='" + step + "']").click();
            for (let i = 0; i < 40; i++) {
                await window.__c.wait(100);
                const select = document.getElementById("copyHabitTarget");
                if (!select.disabled || !document.getElementById("copyHabitEmpty").hidden
                    || !document.getElementById("copyHabitError").hidden) {
                    break;
                }
            }
        }
    };
`;

const STATE = `(async () => {
    const c = window.__c;
    const error = document.getElementById("copyHabitError");
    return {
        modalOpen: document.getElementById("copyHabitModal").classList.contains("show"),
        error: c.visible(error) ? c.text(error) : "",
        chosen: document.getElementById("copyHabitTarget").value
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
    const modal = document.querySelector("#copyHabitModal .routine-edit-modal");
    const parts = [...modal.querySelectorAll("*")].filter(visible);
    const outside = parts.filter((el) => {
        const box = el.getBoundingClientRect();
        return box.width > 0 && (box.left < -1 || box.right > innerWidth + 1);
    });
    const inView = (el) => {
        const box = el.getBoundingClientRect();
        return visible(el) && box.left >= 0 && box.right <= innerWidth;
    };
    const buttons = [...document.querySelectorAll("#habitsList [data-copy-habit]")];
    const texts = [...modal.querySelectorAll(
        "h2, .adapt-copy-close, .routine-habits-subtitle, .copy-habit-title, dt, dd, .copy-habit-notes, label,"
        + " #copyHabitConfirm, #copyHabitCancel")]
        .concat(buttons.map((button) => button.querySelector("span")))
        .filter(visible).filter((el) => el.childNodes.length && [...el.childNodes].some((n) => n.nodeType === 3 && n.textContent.trim()));
    const faintest = texts.length ? texts.reduce((a, b) => contrast(a) <= contrast(b) ? a : b) : null;
    return {
        width: innerWidth,
        theme: document.documentElement.getAttribute("data-theme") || "",
        overflow: document.documentElement.scrollWidth - innerWidth,
        buttons: buttons.length,
        buttonsInside: buttons.length > 0 && buttons.every(inView),
        inside: outside.length === 0,
        outside: outside.slice(0, 3).map((el) => el.className || el.tagName).join(", "),
        actionsVisible: inView(document.getElementById("copyHabitConfirm")) && inView(document.getElementById("copyHabitCancel")),
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

    return {
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
}

const profile = mkdtempSync(join(tmpdir(), "cml-copyhabit-"));
const {chrome, endpoint} = await startChrome(profile);

try {
    const client = await connect(endpoint);
    const results = {};
    const counts = `(async () => ({browser: await window.__c.count(${fx.browserTarget}), busy: await window.__c.count(${fx.busyTarget})}))()`;

    // Who sees Copy habit; Copy routine and Adapt & copy stay next to it.
    const copier = await open(client, {token: fx.copierToken});
    results.buttons = await copier.evaluate(`({
        copierButtons: window.__c.buttons().length,
        copyRoutineVisible: window.__c.visible(document.getElementById("copyRoutineBtn")),
        adaptVisible: window.__c.visible(document.getElementById("adaptCopyBtn"))
    })`);
    const owner = await open(client, {token: fx.authorToken});
    results.buttons.ownerButtons = await owner.evaluate(`window.__c.buttons().length`);
    await owner.close();
    const guest = await open(client);
    await guest.evaluate(`window.__c.buttons()[0].click(); true`);
    await pause(300);
    results.buttons.guestNotice = await guest.evaluate(`window.__c.text(document.getElementById("copyNotice"))`);
    results.buttons.guestModalOpen = await guest.evaluate(`document.getElementById("copyHabitModal").classList.contains("show")`);
    await guest.close();

    // The form shows the habit and the copier's own routines; a tricky title stays text.
    await copier.evaluate(`window.__c.open(${fx.tricky})`);
    const trickyTitle = await copier.evaluate(`window.__c.text(document.getElementById("copyHabitTitle"))`);
    await copier.evaluate(`document.getElementById("copyHabitCancel").click(); true`);
    await copier.evaluate(`window.__c.open(${fx.exercise})`);
    // An empty result when the page has no such form (as before US-18), so the run still ends.
    results.form = (await copier.evaluate(`(() => {
        const c = window.__c;
        const select = document.getElementById("copyHabitTarget");
        return {
            title: c.text(document.getElementById("copyHabitTitle")),
            duration: c.text(document.getElementById("copyHabitDuration")),
            time: c.text(document.getElementById("copyHabitTime")),
            days: c.text(document.getElementById("copyHabitDays")),
            notes: c.text(document.getElementById("copyHabitNotes")),
            options: [...select.options].map((option) => option.textContent),
            confirmEnabled: !document.getElementById("copyHabitConfirm").disabled,
            xss: window.__xss === 1
        };
    })()`)) || {};
    results.form.trickyTitle = trickyTitle;
    await copier.snap("form");

    // Cancel adds nothing.
    const before = await copier.evaluate(counts);
    await copier.evaluate(`(async () => {
        window.__c.choose(${fx.browserTarget});
        document.getElementById("copyHabitCancel").click();
        await window.__c.wait(800);
        return true;
    })()`);
    results.cancel = {...await copier.evaluate(STATE), before: before.browser, after: (await copier.evaluate(counts)).browser};

    // A refusal only the server can tell: the busy routine already has something at that time.
    await copier.evaluate(`window.__c.open(${fx.exercise})`);
    await copier.evaluate(`(async () => {
        window.__c.choose(${fx.busyTarget});
        document.getElementById("copyHabitConfirm").click();
        await window.__c.wait(1500);
        return true;
    })()`);
    results.invalid = {...await copier.evaluate(STATE), before: before.busy, after: (await copier.evaluate(counts)).busy};
    await copier.snap("invalid");

    // A valid copy, confirmed with a double click, then opened.
    const sourceBefore = await copier.evaluate(`(async () => JSON.stringify(await window.__c.api("/routines/${fx.source}/routine-habits")))()`);
    await copier.evaluate(`(async () => {
        window.__c.choose(${fx.browserTarget});
        const confirm = document.getElementById("copyHabitConfirm");
        confirm.click();
        confirm.click();
        await window.__c.wait(2000);
        return true;
    })()`);
    const saved = (await copier.evaluate(`(async () => {
        const c = window.__c;
        const link = document.querySelector("#copyNotice a[href^='routine.html?id=']");
        return {
            modalOpen: document.getElementById("copyHabitModal").classList.contains("show"),
            notice: c.text(document.getElementById("copyNotice")),
            openHref: link ? link.getAttribute("href") : "",
            sourceAfter: JSON.stringify(await c.api("/routines/${fx.source}/routine-habits"))
        };
    })()`)) || {};
    results.save = {...saved, sourceBefore, before: before.browser, after: (await copier.evaluate(counts)).browser};
    await copier.snap("saved");
    await copier.close();
    if (saved.openHref) {
        const target = await open(client, {token: fx.copierToken, routine: saved.openHref.split("=")[1]});
        results.save.targetTitles = await target.evaluate(
            `[...document.querySelectorAll("#habitsList .habit__title")].map((el) => window.__c.text(el))`);
        results.save.targetCopyButtons = await target.evaluate(`window.__c.buttons().length`);
        await target.snap("target");
        await target.close();
    }

    // Someone without a routine of their own is sent to create one.
    const newcomer = await open(client, {token: fx.emptyToken});
    await newcomer.evaluate(`window.__c.open(${fx.exercise})`);
    results.empty = await newcomer.evaluate(`(() => {
        const c = window.__c;
        const empty = document.getElementById("copyHabitEmpty");
        return {
            emptyVisible: c.visible(empty),
            createHref: empty.querySelector("a")?.getAttribute("href") || "",
            selectVisible: c.visible(document.getElementById("copyHabitTarget")),
            confirmEnabled: !document.getElementById("copyHabitConfirm").disabled
        };
    })()`);
    await newcomer.snap("empty");
    await newcomer.close();

    results.layouts = {};
    for (const width of [360, 390, 1280, 1366, 1920]) {
        for (const theme of ["original", "autumn"]) {
            const view = await open(client, {token: fx.copierToken, width, theme});
            await view.evaluate(`document.getElementById("habitsList").scrollIntoView(); true`);
            await view.snap(`${theme}-${width}-list`);
            await view.evaluate(`window.__c.open(${fx.exercise})`);
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
