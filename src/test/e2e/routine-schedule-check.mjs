// Plans a routine (times, repeat days, notes) and reads its timeline in headless Chrome.
// Started by RoutineScheduleRegressionTest; talks only to 127.0.0.1.
//
// Usage: node routine-schedule-check.mjs <chrome> <fixtures.json> <results.json>
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
// Optional: set SCHEDULE_SCREENSHOTS to a folder to keep a picture of each state for a visual check.
const screenshots = process.env.SCHEDULE_SCREENSHOTS;

const TIMELINE = `(() => {
    const visible = (el) => !!el && !el.hidden && el.offsetParent !== null;
    const text = (el) => (el?.textContent || "").trim().replace(/\\s+/g, " ");
    const items = [...document.querySelectorAll("#habitsList li.habit")].filter(visible);
    const tabs = document.getElementById("scheduleViews");
    return {
        titles: items.map((li) => text(li.querySelector(".habit__title"))),
        whens: items.map((li) => text(li.querySelector(".habit__when"))),
        days: items.map((li) => text(li.querySelector(".habit__days"))),
        notes: items.map((li) => text(li.querySelector(".habit__notes"))),
        tabsVisible: visible(tabs),
        timeRange: text(document.getElementById("routineTimeRange")),
        emptyText: text(document.querySelector("#habitsList li.empty"))
    };
})()`;

const WEEK = `(() => {
    const text = (el) => (el?.textContent || "").trim().replace(/\\s+/g, " ");
    const days = {};
    document.querySelectorAll("#weekPlan .week-day").forEach((column) => {
        days[column.dataset.day] = [...column.querySelectorAll(".week-item__title")].map(text);
    });
    return {days, anyDay: text(document.getElementById("weekAnyDay")), visible: !document.getElementById("weekPlan").hidden};
})()`;

const EDITOR = `(() => {
    const visible = (el) => !!el && !el.hidden && el.offsetParent !== null;
    const message = document.getElementById("editHabitsMessage");
    return {
        message: visible(message) ? message.textContent.trim() : "",
        formOpen: [...document.querySelectorAll(".routine-habit-schedule")].some(visible)
    };
})()`;

const click = (selector) => `(async () => {
    const el = document.querySelector(${JSON.stringify(selector)});
    if (!el || el.disabled || el.offsetParent === null) {
        return false;
    }
    el.click();
    await new Promise((done) => setTimeout(done, 900));
    return true;
})()`;

// Opens the schedule form of the editor row whose title matches, then fills and saves it.
const scheduleRow = (title, {start, days, notes}) => `(async () => {
    // The editor draws its rows again on every change, so look the row up each time.
    const findRow = () => [...document.querySelectorAll("#routineHabitsEditor .routine-habit-edit-item")]
        .find((item) => item.querySelector(".routine-habit-edit-info strong")?.textContent.trim() === ${JSON.stringify(title)});
    if (!findRow()) {
        return "no row";
    }
    findRow().querySelector('[data-action="schedule"]').click();
    await new Promise((done) => setTimeout(done, 300));
    const form = findRow()?.querySelector(".routine-habit-schedule");
    if (!form) {
        return "no form";
    }
    const set = (input, value) => {
        input.value = value;
        input.dispatchEvent(new Event("input", {bubbles: true}));
        input.dispatchEvent(new Event("change", {bubbles: true}));
    };
    set(form.querySelector('[name="startTime"]'), ${JSON.stringify(start)});
    form.querySelectorAll('[name="repeatDay"]').forEach((box) => {
        box.checked = ${JSON.stringify(days)}.includes(box.value);
    });
    if (${JSON.stringify(notes)} !== null) {
        set(form.querySelector('[name="notes"]'), ${JSON.stringify(notes)});
    }
    form.querySelector('[data-action="save-schedule"]').click();
    await new Promise((done) => setTimeout(done, 1000));
    return "saved";
})()`;

const fillAndSubmit = (fields, button) => `(async () => {
    for (const [id, value] of Object.entries(${JSON.stringify(fields)})) {
        const input = document.getElementById(id);
        if (input.type === "checkbox") {
            input.checked = value;
        } else {
            input.value = value;
        }
        input.dispatchEvent(new Event("input", {bubbles: true}));
    }
    document.getElementById(${JSON.stringify(button)}).click();
    await new Promise((done) => setTimeout(done, 1000));
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
    const texts = [...document.querySelectorAll(
        ".habit__when, .habit__days, .habit__notes, .week-item__title, .week-item__time, .week-day__name, " +
        "#scheduleViews button, .routine-habit-schedule label, .routine-habit-schedule button, .day-chip span")]
        .filter(visible);
    const buttons = [...document.querySelectorAll(
        "#scheduleViews button, .routine-habit-schedule button, .day-chip, #routineHabitsEditor .routine-habit-edit-actions button")]
        .filter(visible).filter((el) => !el.disabled);
    const all = [...texts, ...buttons];
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
            const {data} = await client.send("Page.captureScreenshot", {format: "png", captureBeyondViewport: true}, sessionId);
            writeFileSync(join(screenshots, `${name}.png`), Buffer.from(data, "base64"));
        },
        close: () => client.send("Target.closeTarget", {targetId})
    };
}

const routinePage = (id) => `/routine.html?id=${id}`;

const profile = mkdtempSync(join(tmpdir(), "cml-schedule-"));
const {chrome, endpoint} = await startChrome(profile);

try {
    const client = await connect(endpoint);
    const results = {};

    const owner = await open(client, {token: fx.ownerToken, path: routinePage(fx.routine)});
    results.todayCode = await owner.evaluate(`["SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT"][new Date().getDay()]`);
    results.timeline = await owner.evaluate(TIMELINE);
    await owner.snap("timeline");
    await owner.evaluate(click('#scheduleViews [data-view="week"]'));
    results.week = await owner.evaluate(WEEK);
    await owner.snap("week");
    await owner.evaluate(click('#scheduleViews [data-view="today"]'));
    results.today = await owner.evaluate(TIMELINE);
    await owner.evaluate(click('#scheduleViews [data-view="timeline"]'));

    await owner.evaluate(click("#editHabitsBtn"));
    await owner.evaluate(scheduleRow("Read", {start: "07:00", days: ["MON", "TUE"], notes: "Chapter 3"}));
    await owner.snap("editor-saved");
    results.afterSchedule = await owner.evaluate(TIMELINE);
    await owner.reload();
    results.scheduleAfterReload = await owner.evaluate(TIMELINE);

    await owner.evaluate(click("#editHabitsBtn"));
    await owner.evaluate(scheduleRow("Read", {start: "06:40", days: ["MON"], notes: null}));
    results.overlap = await owner.evaluate(EDITOR);
    await owner.snap("editor-overlap");
    await owner.reload();
    results.overlapAfterReload = await owner.evaluate(TIMELINE);

    await owner.evaluate(click("#editHabitsBtn"));
    await owner.evaluate(`(async () => {
        document.getElementById("newActivityTitle").value = "Journal";
        document.getElementById("newActivityDuration").value = "20";
        document.getElementById("newActivityStart").value = "22:00";
        document.querySelectorAll('[name="newActivityDay"]').forEach((box) => { box.checked = box.value === "SUN"; });
        document.getElementById("newActivityAdd").click();
        await new Promise((done) => setTimeout(done, 1200));
    })()`);
    results.afterNewActivity = await owner.evaluate(TIMELINE);
    await owner.evaluate(click("#closeEditHabits"));

    await owner.evaluate(click("#editRoutineBtn"));
    await owner.evaluate(fillAndSubmit({editRoutineDuration: "10"}, "saveEditRoutine"));
    results.editTooShort = await owner.evaluate(`({message: (document.getElementById("editRoutineError")?.textContent || "").trim()})`);
    await owner.snap("edit-routine-error");
    await owner.close();

    const create = await open(client, {token: fx.ownerToken, path: "/profile.html"});
    await create.evaluate(click("#myRoutinesNav"));
    await create.evaluate(click("#addRoutineBtn"));
    await create.evaluate(fillAndSubmit({routineTitle: "Too long", routineDuration: "2000"}, "createRoutineBtn"));
    results.createInvalid = await create.evaluate(`({message: (document.getElementById("routineFormError")?.textContent || "").trim()})`);
    await create.snap("create-error");
    await create.evaluate(fillAndSubmit({routineTitle: "   ", routineDuration: "30"}, "createRoutineBtn"));
    results.createBlank = await create.evaluate(`({message: (document.getElementById("routineFormError")?.textContent || "").trim()})`);
    await create.close();

    const empty = await open(client, {token: fx.emptyToken, path: routinePage(fx.emptyRoutine)});
    results.empty = await empty.evaluate(TIMELINE);
    await empty.close();

    if (fx.builtInRoutine) {
        const builtIn = await open(client, {token: fx.viewerToken, path: routinePage(fx.builtInRoutine)});
        results.builtInHalloween = await builtIn.evaluate(`(() => ({
            loaded: document.querySelectorAll("#habitsList li.habit").length > 0,
            halloweenTheme: document.body.classList.contains("halloween-detail-page"),
            tabsVisible: !document.getElementById("scheduleViews").hidden,
            whens: document.querySelectorAll(".habit__when").length,
            timeRange: (document.getElementById("routineTimeRange")?.textContent || "").trim()
        }))()`);
        await builtIn.close();
    } else {
        results.builtInHalloween = {loaded: false};
    }

    const layouts = {
        originalPhone: {token: fx.ownerToken, path: routinePage(fx.routine), width: 390, theme: "original"},
        autumnPhone: {token: fx.ownerToken, path: routinePage(fx.routine), width: 390, theme: "autumn"},
        halloweenPhone: {token: fx.halloweenToken, path: routinePage(fx.halloweenRoutine), width: 390, theme: "original"},
        weekPhone: {token: fx.ownerToken, path: routinePage(fx.routine), width: 390, theme: "autumn", week: true},
        editorPhone: {token: fx.ownerToken, path: routinePage(fx.routine), width: 390, theme: "original", editor: true},
        autumnDesktop: {token: fx.halloweenToken, path: routinePage(fx.halloweenRoutine), width: 1280, theme: "autumn", week: true}
    };
    for (const [name, options] of Object.entries(layouts)) {
        const view = await open(client, options);
        if (options.week) {
            await view.evaluate(click('#scheduleViews [data-view="week"]'));
        }
        if (options.editor) {
            await view.evaluate(click("#editHabitsBtn"));
            await view.evaluate(click('#routineHabitsEditor [data-action="schedule"]'));
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
