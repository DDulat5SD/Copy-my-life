// Uses the weekly calendar on profile.html in headless Chrome the way a user would.
// Started by WeeklyCalendarRegressionTest; talks only to 127.0.0.1.
//
// Usage: node weekly-calendar-check.mjs <chrome> <fixtures.json> <results.json>
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
// Optional: set CALENDAR_SCREENSHOTS to a folder to keep a picture of each state for a visual check.
const screenshots = process.env.CALENDAR_SCREENSHOTS;

const dayOf = (offset) => {
    const date = new Date(fx.monday + "T12:00:00");
    date.setDate(date.getDate() + offset);
    return date.toISOString().slice(0, 10);
};

const STATE = `(() => {
    const visible = (el) => !!el && !el.hidden && el.offsetParent !== null;
    const text = (el) => (el?.textContent || "").trim().replace(/\\s+/g, " ");
    const blocks = [...document.querySelectorAll("#calendarEventsArea .calendar-event:not(.calendar-event--continued)")];
    const counts = {};
    blocks.forEach((block) => {
        const title = text(block.querySelector(".calendar-event-title"));
        counts[title] = (counts[title] || 0) + 1;
    });
    const startOf = (block) => text(block.querySelector(".calendar-event-time")).split(" – ")[0];
    const morning = blocks.filter((block) => text(block.querySelector(".calendar-event-title")) === "Morning run");
    const message = document.getElementById("calendarMessage");
    return {
        dayHeaders: document.querySelectorAll(".calendar-grid .calendar-day").length,
        blocks: counts,
        continuations: document.querySelectorAll("#calendarEventsArea .calendar-event--continued").length,
        dayHeading: text(document.getElementById("calendarDayHeading")),
        dayTitles: [...document.querySelectorAll("#calendarDayPanel .calendar-day-item__title")].map(text),
        routineLinks: document.querySelectorAll('#calendarDayPanel a[href*="routine.html?id="]').length,
        dayEmpty: visible(document.getElementById("calendarDayEmpty")) ? text(document.getElementById("calendarDayEmpty")) : "",
        message: visible(message) ? text(message) : "",
        wednesdayTimes: morning.filter((block) => block.dataset.day === ${JSON.stringify(dayOf(2))}).map(startOf),
        timesAt7: morning.filter((block) => startOf(block) === "7:00 AM").length,
        reloaded: !window.__sameDocument,
        scopeVisible: visible(document.getElementById("calendarEditScopeGroup")),
        modalVisible: visible(document.getElementById("calendarDeleteModal")?.querySelector(".calendar-modal-card")),
        choices: [...document.querySelectorAll('[name="calendarDeleteScope"]')]
            .filter((input) => visible(input.closest("label"))).map((input) => input.value)
    };
})()`;

const run = (body) => `(async () => {
    ${body}
    await new Promise((done) => setTimeout(done, 1000));
    return true;
})()`;

// Presses Edit or Delete on the block of one routine on one day.
const onBlock = (title, day, action) => run(`
    const block = [...document.querySelectorAll("#calendarEventsArea .calendar-event:not(.calendar-event--continued)")]
        .find((el) => el.dataset.day === ${JSON.stringify(day)}
            && el.querySelector(".calendar-event-title").textContent.trim() === ${JSON.stringify(title)});
    block.querySelector(${JSON.stringify(action === "edit" ? ".calendar-edit-event" : ".calendar-delete-event")}).click();
`);

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
    const modalOpen = document.querySelector(".calendar-modal.show");
    const scope = modalOpen ? ".calendar-modal.show " : "";
    const texts = [...document.querySelectorAll(modalOpen
        ? scope + "h2, " + scope + "label, " + scope + "button, " + scope + "p"
        : ".calendar-day span, .calendar-day strong, #calendarDayPanel h3, #calendarDayPanel .calendar-day-item__title, " +
          "#calendarDayPanel .calendar-day-item__time, #calendarDayPanel button, #calendarDayPanel a, #calendarDayEmpty, #calendarMessage")]
        .filter(visible);
    const buttons = [...document.querySelectorAll(modalOpen
        ? scope + "button, " + scope + "input[type=radio]"
        : ".calendar-week-navigation button, .calendar-grid .calendar-day, #calendarDayPanel button, #calendarDayPanel a")]
        .filter(visible)
        .map((el) => el.type === "radio" ? el.closest("label") : el);
    // The seven-day grid scrolls sideways inside its board on a phone; the board itself must fit.
    const board = document.querySelector(".calendar-board");
    const all = [...texts, ...buttons].filter((el) => !el.closest(".calendar-board"));
    if (visible(board)) {
        all.push(board);
    }
    return {
        width: innerWidth,
        theme: document.documentElement.getAttribute("data-theme") || "",
        rendered: texts.length > 0 && buttons.length > 0,
        overflow: document.documentElement.scrollWidth - innerWidth,
        rightmost: Math.round(Math.max(0, ...all.map((el) => el.getBoundingClientRect().right))),
        smallestTarget: Math.round(Math.min(...buttons.map((el) => el.getBoundingClientRect().height))),
        minContrast: Math.round(Math.min(...texts.map(contrast)) * 100) / 100
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

async function open(client, {token, width = 1280, theme = "original"}) {
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

    const page = {
        evaluate: async (expression) => (await client.send("Runtime.evaluate", {
            expression, returnByValue: true, awaitPromise: true
        }, sessionId)).result.value,
        // Opens the calendar section and marks the document, so a full reload can be noticed.
        showCalendar: async () => {
            await page.evaluate(run(`document.getElementById("calendarNav").click(); window.__sameDocument = true;`));
            await pause(800);
        },
        load: async () => {
            await client.send("Page.navigate", {url: fx.base + "/profile.html"}, sessionId);
            await pause(2000);
            await page.showCalendar();
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
    await page.load();
    return page;
}

const profile = mkdtempSync(join(tmpdir(), "cml-calendar-"));
const {chrome, endpoint} = await startChrome(profile);

try {
    const client = await connect(endpoint);
    const results = {};

    const owner = await open(client, {token: fx.token});
    results.initial = await owner.evaluate(STATE);
    await owner.snap("week");

    await owner.evaluate(run(`document.querySelector('.calendar-grid .calendar-day[data-day-index="1"]').click();`));
    results.tuesday = await owner.evaluate(STATE);
    await owner.snap("tuesday");

    await owner.evaluate(run(`
        const select = document.getElementById("calendarRoutineSelect");
        select.value = [...select.options].find((option) => option.textContent === "Evening read").value;
        document.getElementById("calendarDay").value = ${JSON.stringify(dayOf(3))};
        document.getElementById("calendarTime").value = "19:00";
        document.getElementById("calendarRepeat").value = "none";
        document.getElementById("addToCalendarBtn").click();
    `));
    results.afterAdd = await owner.evaluate(STATE);

    await owner.evaluate(onBlock("Morning run", dayOf(2), "edit"));
    results.editScope = await owner.evaluate(STATE);
    await owner.snap("edit-modal");
    await owner.evaluate(run(`
        document.querySelector('[name="calendarEditScope"][value="occurrence"]').checked = true;
        document.querySelector('[name="calendarEditScope"][value="occurrence"]').dispatchEvent(new Event("change", {bubbles: true}));
        document.getElementById("calendarEditTime").value = "09:15";
        document.getElementById("calendarEditSave").click();
    `));
    results.afterEditOne = await owner.evaluate(STATE);
    await owner.load();
    results.editAfterReload = await owner.evaluate(STATE);

    await owner.evaluate(onBlock("Morning run", dayOf(4), "delete"));
    results.deleteAsked = await owner.evaluate(STATE);
    await owner.snap("delete-modal");
    await owner.evaluate(run(`document.getElementById("calendarDeleteCancel").click();`));
    results.deleteCancelled = await owner.evaluate(STATE);

    await owner.evaluate(onBlock("Morning run", dayOf(4), "delete"));
    await owner.evaluate(run(`
        document.querySelector('[name="calendarDeleteScope"][value="occurrence"]').checked = true;
        document.getElementById("calendarDeleteConfirm").click();
    `));
    results.afterDeleteOne = await owner.evaluate(STATE);

    await owner.evaluate(onBlock("Morning run", dayOf(0), "delete"));
    await owner.evaluate(run(`
        document.querySelector('[name="calendarDeleteScope"][value="series"]').checked = true;
        document.getElementById("calendarDeleteConfirm").click();
    `));
    results.afterDeleteSeries = await owner.evaluate(STATE);
    await owner.load();
    results.deleteAfterReload = await owner.evaluate(STATE);
    await owner.close();

    const empty = await open(client, {token: fx.emptyToken});
    results.emptyWeek = await empty.evaluate(STATE);
    await empty.close();

    const layouts = {
        originalPhone: {token: fx.token, width: 390, theme: "original"},
        autumnPhone: {token: fx.token, width: 390, theme: "autumn"},
        autumnDesktop: {token: fx.token, width: 1280, theme: "autumn"},
        modalPhone: {token: fx.token, width: 390, theme: "original", modal: true}
    };
    for (const [name, options] of Object.entries(layouts)) {
        const view = await open(client, options);
        if (options.modal) {
            await view.evaluate(run(`document.querySelector('.calendar-grid .calendar-day[data-day-index="1"]').click();`));
            await view.evaluate(run(`document.querySelector("#calendarDayPanel [data-action=delete]").click();`));
        }
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
