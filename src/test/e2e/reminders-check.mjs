// US-22: in headless Chrome, a due reminder shows on a signed-in page (390 px, Autumn), can be
// dismissed and does not come back after a reload; Profile -> Settings shows the reminder setting
// and turns it off; the settings card fits in both designs at 390 and 1280 px.
// Started by RoutineRemindersRegressionTest; talks only to 127.0.0.1.
//
// Usage: node reminders-check.mjs <chrome> <fixtures.json> <results.json>
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

// Horizontal scroll, and whether every visible part of the selected element is on screen.
const fits = (selector) => `(() => {
    const root = document.querySelector(${JSON.stringify(selector)});
    const parts = root ? [root, ...root.querySelectorAll("*")].filter((el) => el.offsetParent !== null || el === root) : [];
    const outside = parts.filter((el) => {
        const box = el.getBoundingClientRect();
        return box.width > 0 && (box.left < -1 || box.right > innerWidth + 1);
    });
    return {
        found: !!root,
        overflow: document.documentElement.scrollWidth - innerWidth,
        inside: !!root && outside.length === 0,
        outside: outside.slice(0, 3).map((el) => el.className || el.tagName).join(", ")
    };
})()`;

const TOAST = `(() => ({
    titles: [...document.querySelectorAll(".cml-reminder-title")].map((el) => el.textContent),
    when: document.querySelector(".cml-reminder-when")?.textContent || "",
    xss: window.__xss === 1,
    // Why nothing showed, if it did not.
    state: document.visibilityState + " module:" + (typeof window.CopyMyLifeReminders)
        + " token:" + !!localStorage.getItem("token") + " page:" + location.pathname
}))()`;

const COUNT = `document.querySelectorAll(".cml-reminder").length`;

const SETTINGS = `(() => {
    const root = document.getElementById("reminderSettings");
    return {
        visible: !!root && root.offsetParent !== null,
        checked: !!root?.querySelector("[data-reminder-enabled]")?.checked,
        lead: root?.querySelector("[data-reminder-lead]")?.value || "",
        status: root?.querySelector("[data-reminder-status]")?.textContent || ""
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

async function open(client, {token, page, width = 1280, theme = "original"}) {
    const {targetId} = await client.send("Target.createTarget", {url: "about:blank"});
    const {sessionId} = await client.send("Target.attachToTarget", {targetId, flatten: true});

    await client.send("Page.enable", {}, sessionId);
    await client.send("Runtime.enable", {}, sessionId);
    await client.send("Emulation.setTimezoneOverride", {timezoneId: fx.zone}, sessionId);
    await client.send("Emulation.setDeviceMetricsOverride", {
        width, height: width < 600 ? 844 : 900, deviceScaleFactor: width < 600 ? 2 : 1, mobile: width < 600
    }, sessionId);
    await client.send("Page.addScriptToEvaluateOnNewDocument", {
        source: `localStorage.setItem("token", ${JSON.stringify(token)});`
            + `localStorage.setItem("copymylife-theme", ${JSON.stringify(theme)});`
    }, sessionId);

    const view = {
        go: async () => {
            await client.send("Page.navigate", {url: fx.base + "/" + page}, sessionId);
            await pause(3000);
        },
        evaluate: async (expression) => (await client.send("Runtime.evaluate", {
            expression, returnByValue: true, awaitPromise: true
        }, sessionId)).result.value,
        click: async (selector) => {
            await view.evaluate(`document.querySelector(${JSON.stringify(selector)}).click()`);
            await pause(1500);
        },
        close: () => client.send("Target.closeTarget", {targetId})
    };
    await view.go();
    return view;
}

const profile = mkdtempSync(join(tmpdir(), "cml-reminders-"));
const {chrome, endpoint} = await startChrome(profile);

try {
    const client = await connect(endpoint);
    const results = {};

    const toast = await open(client, {token: fx.toastToken, page: "main.html", width: 390, theme: "autumn"});
    results.toast = await toast.evaluate(TOAST);
    results.layouts = {toast390: await toast.evaluate(fits("#cmlReminders"))};
    await toast.click(".cml-reminder-dismiss");
    results.toast.afterDismiss = await toast.evaluate(COUNT);
    await toast.go();
    results.toast.afterReload = await toast.evaluate(COUNT);
    await toast.close();

    const settings = await open(client, {token: fx.settingsToken, page: "profile.html"});
    await settings.click("#settingsNav");
    results.settings = await settings.evaluate(SETTINGS);
    await settings.click("#reminderSettings [data-reminder-enabled]");
    const after = await settings.evaluate(SETTINGS);
    results.settings.savedOff = after.status;
    results.settings.checkedAfter = after.checked;
    await settings.close();

    for (const [name, width, theme] of [["original390", 390, "original"], ["autumn1280", 1280, "autumn"], ["original1280", 1280, "original"]]) {
        const view = await open(client, {token: fx.settingsToken, page: "profile.html", width, theme});
        await view.click("#settingsNav");
        results.layouts[name] = await view.evaluate(fits("#reminderSettings"));
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
