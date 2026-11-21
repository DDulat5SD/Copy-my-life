// Opens routine pages without signing in and reads what a visitor sees and can do.
// Started by GuestRoutineViewRegressionTest; talks only to 127.0.0.1.
//
// Usage: node guest-routine-check.mjs <chrome> <fixtures.json> <results.json>
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
// Optional: set GUEST_SCREENSHOTS to a folder to keep a picture of each state for a visual check.
const screenshots = process.env.GUEST_SCREENSHOTS;

const PAGE = `(() => {
    const visible = (el) => !!el && !el.hidden && el.offsetParent !== null;
    const text = (el) => (el?.textContent || "").trim().replace(/\\s+/g, " ");
    const items = [...document.querySelectorAll("#habitsList li.habit")].filter(visible);
    const notice = document.getElementById("copyNotice");
    return {
        url: location.pathname + location.search,
        title: text(document.getElementById("routineTitle")),
        description: text(document.getElementById("routineDesc")),
        titles: items.map((li) => text(li.querySelector(".habit__title"))),
        whens: items.map((li) => text(li.querySelector(".habit__when"))),
        days: items.map((li) => text(li.querySelector(".habit__days"))),
        durations: items.map((li) => text(li.querySelector(".habit__dur"))),
        notes: items.map((li) => text(li.querySelector(".habit__notes"))),
        tabsVisible: visible(document.getElementById("scheduleViews")),
        timeRange: text(document.getElementById("routineTimeRange")),
        emptyText: text(document.querySelector("#habitsList li.empty")),
        signInVisible: visible(document.getElementById("guestSignIn")),
        userMenuVisible: visible(document.getElementById("userMenuToggle")),
        editVisible: visible(document.getElementById("editRoutineBtn")),
        editHabitsVisible: visible(document.getElementById("editHabitsBtn")),
        startVisible: visible(document.getElementById("startBtn")),
        copyVisible: visible(document.getElementById("copyRoutineBtn")),
        likeVisible: visible(document.getElementById("likeBtn")),
        saveVisible: visible(document.getElementById("saveBtn")),
        likes: text(document.getElementById("likesCount")),
        commentFormVisible: visible(document.getElementById("commentForm")),
        commentsText: text(document.getElementById("commentList")),
        commentLinks: [...document.querySelectorAll("#commentList a")].map((a) => a.getAttribute("href")),
        notice: visible(notice) ? text(notice) : "",
        noticeLinks: visible(notice) ? [...notice.querySelectorAll("a")].map((a) => a.getAttribute("href")) : [],
        unavailable: text(document.querySelector(".routine-unavailable h1")),
        unavailableLinks: [...document.querySelectorAll(".routine-unavailable a")].map((a) => a.getAttribute("href")),
        halloweenTheme: document.body.classList.contains("halloween-detail-page"),
        bodyText: document.body.innerText.slice(0, 20000)
    };
})()`;

const WEEK = `(() => {
    const text = (el) => (el?.textContent || "").trim().replace(/\\s+/g, " ");
    const days = {};
    document.querySelectorAll("#weekPlan .week-day").forEach((column) => {
        days[column.dataset.day] = [...column.querySelectorAll(".week-item__title")].map(text);
    });
    return {days, visible: !document.getElementById("weekPlan").hidden};
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
        "#routineTitle, .habit__title, .habit__when, .habit__days, .week-item__title, .week-day__name, " +
        "#scheduleViews button, #guestSignIn, #copyNotice span, #copyNotice a, #commentList li, #commentList a")]
        .filter(visible);
    const buttons = [...document.querySelectorAll(
        "#scheduleViews button, #guestSignIn, #copyRoutineBtn, #saveBtn, #likeBtn")]
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

// token: null opens the page as a visitor who never signed in.
async function open(client, {token = null, path, width = 1280, theme = "original"}) {
    const {targetId} = await client.send("Target.createTarget", {url: "about:blank"});
    const {sessionId} = await client.send("Target.attachToTarget", {targetId, flatten: true});

    await client.send("Page.enable", {}, sessionId);
    await client.send("Runtime.enable", {}, sessionId);
    await client.send("Emulation.setDeviceMetricsOverride", {
        width, height: width < 600 ? 844 : 900, deviceScaleFactor: width < 600 ? 2 : 1, mobile: width < 600
    }, sessionId);
    await client.send("Page.addScriptToEvaluateOnNewDocument", {
        source: (token === null
                ? `localStorage.removeItem("token");`
                : `localStorage.setItem("token", ${JSON.stringify(token)});`)
            + `localStorage.setItem("copymylife-theme", ${JSON.stringify(theme)});`
    }, sessionId);
    await client.send("Page.navigate", {url: fx.base + path}, sessionId);
    await pause(2000);

    return {
        evaluate: async (expression) => (await client.send("Runtime.evaluate", {
            expression, returnByValue: true, awaitPromise: true
        }, sessionId)).result.value,
        navigate: async (url) => {
            await client.send("Page.navigate", {url}, sessionId);
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

const profile = mkdtempSync(join(tmpdir(), "cml-guest-"));
const {chrome, endpoint} = await startChrome(profile);

try {
    const client = await connect(endpoint);
    const results = {};

    const guest = await open(client, {path: routinePage(fx.publicRoutine)});
    results.todayCode = await guest.evaluate(`["SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT"][new Date().getDay()]`);
    results.guestTimeline = await guest.evaluate(PAGE);
    await guest.snap("guest-timeline");
    await guest.evaluate(click('#scheduleViews [data-view="week"]'));
    results.guestWeek = await guest.evaluate(WEEK);
    await guest.evaluate(click('#scheduleViews [data-view="today"]'));
    results.guestToday = await guest.evaluate(PAGE);
    await guest.evaluate(click('#scheduleViews [data-view="timeline"]'));

    await guest.evaluate(click("#copyRoutineBtn"));
    results.afterCopy = await guest.evaluate(PAGE);
    await guest.snap("guest-copy");
    await guest.evaluate(click("#likeBtn"));
    results.afterLike = await guest.evaluate(PAGE);
    await guest.evaluate(click("#saveBtn"));
    results.afterSave = await guest.evaluate(PAGE);
    await guest.evaluate(click('#ratingInput [data-value="5"]'));
    results.afterRate = await guest.evaluate(PAGE);
    await guest.close();

    const empty = await open(client, {path: routinePage(fx.emptyRoutine)});
    results.guestEmpty = await empty.evaluate(PAGE);
    await empty.close();

    const hidden = await open(client, {path: routinePage(fx.privateRoutine)});
    results.guestPrivate = await hidden.evaluate(PAGE);
    await hidden.snap("guest-private");
    await hidden.close();

    const missing = await open(client, {path: routinePage(fx.missingRoutine)});
    results.guestMissing = await missing.evaluate(PAGE);
    await missing.close();

    const stale = await open(client, {token: "not-a-real-token", path: routinePage(fx.publicRoutine)});
    results.staleToken = await stale.evaluate(PAGE);
    await stale.close();

    // A visitor follows a routine card from Explore and lands on its details, not on the sign-in page.
    const explore = await open(client, {path: "/Explore.html"});
    const cardLink = await explore.evaluate(`(() => {
        const link = [...document.querySelectorAll('a[href^="routine.html?id="]')][0];
        return link ? link.href : null;
    })()`);
    results.fromExplore = {cardLink};
    if (cardLink) {
        await explore.navigate(cardLink);
        results.fromExplore = {cardLink, ...(await explore.evaluate(PAGE))};
    }
    await explore.close();

    if (fx.builtInRoutine) {
        const builtIn = await open(client, {path: routinePage(fx.builtInRoutine)});
        results.guestBuiltIn = {loaded: true, ...(await builtIn.evaluate(PAGE))};
        await builtIn.close();
    } else {
        results.guestBuiltIn = {loaded: false};
    }

    const viewer = await open(client, {token: fx.viewerToken, path: routinePage(fx.publicRoutine)});
    results.signedInViewer = await viewer.evaluate(PAGE);
    await viewer.close();

    const owner = await open(client, {token: fx.ownerToken, path: routinePage(fx.publicRoutine)});
    results.owner = await owner.evaluate(PAGE);
    await owner.close();

    const layouts = {
        originalPhone: {path: routinePage(fx.publicRoutine), width: 390, theme: "original", copy: true},
        autumnPhone: {path: routinePage(fx.publicRoutine), width: 390, theme: "autumn", copy: true},
        weekPhone: {path: routinePage(fx.publicRoutine), width: 390, theme: "original", week: true},
        autumnDesktop: {path: routinePage(fx.publicRoutine), width: 1280, theme: "autumn", week: true},
        originalDesktop: {path: routinePage(fx.publicRoutine), width: 1280, theme: "original"}
    };
    for (const [name, options] of Object.entries(layouts)) {
        const view = await open(client, options);
        if (options.week) {
            await view.evaluate(click('#scheduleViews [data-view="week"]'));
        }
        if (options.copy) {
            await view.evaluate(click("#copyRoutineBtn"));
            await view.evaluate(`window.scrollTo(0, 0)`);
        }
        results[name] = await view.evaluate(LAYOUT);
        await view.snap(name);
        await view.close();
    }

    if (fx.builtInRoutine) {
        const view = await open(client, {path: routinePage(fx.builtInRoutine), width: 390, theme: "autumn"});
        results.halloweenPhone = await view.evaluate(LAYOUT);
        await view.snap("halloweenPhone");
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
