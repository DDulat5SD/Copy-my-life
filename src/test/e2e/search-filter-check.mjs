// US-05: searches and filters Explore as a visitor and as a signed-in user, and reads what is
// listed, what is said when nothing matches, and how the page looks in every design; then opens
// a public profile as a visitor.
// A slow, failed or empty server is answered inside the browser only; nothing is written.
// Started by SearchAndFilterRegressionTest; talks only to 127.0.0.1.
//
// Usage: node search-filter-check.mjs <chrome> <fixtures.json> <results.json>
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
// Per session: [{match, status, body, hold}] answered inside the browser instead of the server.
const answers = new Map();
// Requests held back to see the page while it waits; released by release(sessionId).
const held = new Map();
// Optional: set SEARCH_SCREENSHOTS to a folder to keep a picture of each state for a visual check.
const screenshots = process.env.SEARCH_SCREENSHOTS;

const NO_MATCH = "zzqx-no-such-routine";

const READ = `(() => {
    const visible = (el) => !!el && !el.hidden && el.offsetParent !== null;
    const text = (el) => (el?.textContent || "").trim().replace(/\\s+/g, " ");
    const empty = document.getElementById("exploreEmpty");
    const cards = [...document.querySelectorAll("#routineResults .featured, #routineResults .card, #routineResults .mini-card")]
        .filter(visible);
    const viewAll = [...document.querySelectorAll("#viewAllGrid .view-all-card")].filter(visible);
    const keyOf = (card) => {
        const link = card.querySelector('a[href^="routine.html?id="]');
        return card.classList.contains("public-routine-card") && link
            ? "id:" + link.getAttribute("href").split("=")[1]
            : "name:" + text(card.querySelector(".view-all-name"));
    };
    const names = cards.map((card) => text(card.querySelector(".featured-name, .card-name, .mini-name")));
    const people = [...document.querySelectorAll("#peopleResults .person-result")].filter(visible);
    const status = document.getElementById("exploreSearchStatus");
    const clear = document.getElementById("clearSearchBtn");
    const reset = document.getElementById("resetFiltersBtn");
    const note = document.querySelector("#peopleResults .people-note");
    return {
        query: document.getElementById("routineSearch").value,
        focused: document.activeElement === document.getElementById("routineSearch"),
        emptyVisible: visible(empty),
        emptyTitle: visible(empty) ? text(empty.querySelector(".explore-empty-title")) : "",
        emptyText: visible(empty) ? text(empty.querySelector(".explore-empty-text")) : "",
        hint: visible(empty) ? text(empty.querySelector(".explore-empty-hint")) : "",
        resetVisible: visible(reset),
        clearVisible: visible(clear),
        status: visible(status) ? text(status) : "",
        cards: cards.length,
        blankCards: cards.filter((card) => !text(card)).length + viewAll.filter((card) => !text(card.querySelector(".view-all-name"))).length,
        names,
        viewAll: viewAll.map((card) => text(card.querySelector(".view-all-name"))),
        viewAllTotal: viewAll.length,
        keys: [...names.map((name) => "name:" + name), ...viewAll.map(keyOf)],
        peopleVisible: visible(document.getElementById("peopleResults")),
        people: people.map((card) => ({href: card.getAttribute("href") || card.querySelector("a")?.getAttribute("href") || "", text: text(card)})),
        peopleNote: visible(note) ? text(note) : "",
        viewAllEmpty: [...document.querySelectorAll("#viewAllGrid .view-all-empty")].some(visible),
        activeChip: document.querySelector(".chip.chip-active")?.dataset.filter || "",
        halloween: document.body.classList.contains("halloween-mode"),
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
    const inside = (el) => {
        if (!visible(el)) {
            return true;
        }
        const box = el.getBoundingClientRect();
        return box.left >= -1 && box.right <= innerWidth + 1;
    };
    const texts = [...document.querySelectorAll(
        "#exploreEmpty .explore-empty-title, #exploreEmpty .explore-empty-text, #exploreEmpty .explore-empty-hint,"
        + " #exploreSearchStatus, #peopleResults .person-name, #peopleResults .person-detail, #peopleResults .people-title,"
        + " #resetFiltersBtn")].filter(visible).filter((el) => el.textContent.trim());
    const chips = [...document.querySelectorAll(".filters .chip")];
    const filters = document.querySelector(".filters");
    return {
        width: innerWidth,
        theme: document.documentElement.getAttribute("data-theme") || "",
        halloween: document.body.classList.contains("halloween-mode"),
        overflow: document.documentElement.scrollWidth - innerWidth,
        chipsOneRow: new Set(chips.map((chip) => Math.round(chip.getBoundingClientRect().top))).size === 1,
        chipsScrollInside: filters.scrollWidth <= filters.clientWidth + 1
            || ["auto", "scroll"].includes(getComputedStyle(filters).overflowX),
        searchFits: inside(document.querySelector(".search")) && inside(document.getElementById("clearSearchBtn")),
        emptyFits: inside(document.getElementById("exploreEmpty")) && inside(document.getElementById("peopleResults"))
            && [...document.querySelectorAll("#peopleResults .person-result")].every(inside),
        peopleShown: [...document.querySelectorAll("#peopleResults .person-result")].filter(visible).length,
        measured: texts.length,
        minContrast: texts.length ? Math.round(Math.min(...texts.map(contrast)) * 100) / 100 : 0,
        faintest: texts.length ? texts.reduce((a, b) => contrast(a) <= contrast(b) ? a : b).className
            || texts.reduce((a, b) => contrast(a) <= contrast(b) ? a : b).id : ""
    };
})()`;

// A visitor's view of user-profile.html.
const PROFILE_READ = `(() => {
    const visible = (el) => !!el && !el.hidden && el.offsetParent !== null;
    const text = (el) => (el?.textContent || "").trim().replace(/\\s+/g, " ");
    const byId = (id) => document.getElementById(id);
    return {
        path: location.pathname,
        name: text(byId("userProfileName")),
        username: text(byId("userProfileUsername")),
        profession: text(byId("userProfileProfession")),
        routines: [...document.querySelectorAll("#publicRoutinesGrid .public-routine-title")].map(text),
        signInVisible: visible(byId("guestSignIn")),
        menuVisible: visible(byId("userMenuToggle")),
        followVisible: visible(byId("followBtn")),
        actions: [...document.querySelectorAll("#profileActions a, #profileActions button")]
            .filter(visible).map((el) => text(el) + "|" + (el.getAttribute("href") || "")),
        todo: text(byId("publicTodoGrid")),
        posts: text(byId("publicPostsGrid")),
        email: /@audit\\.test/.test(document.body.innerText),
        back: byId("backLink")?.getAttribute("href") || ""
    };
})()`;

const PROFILE_LAYOUT = `(() => {
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
        "#guestSignIn, #userProfileName, #userProfileProfession, #profileActions a, .public-content-empty, .public-content-empty a")]
        .filter(visible).filter((el) => el.textContent.trim());
    const faintest = texts.length ? texts.reduce((a, b) => contrast(a) <= contrast(b) ? a : b) : null;
    return {
        path: location.pathname,
        width: innerWidth,
        overflow: document.documentElement.scrollWidth - innerWidth,
        measured: texts.length,
        minContrast: faintest ? Math.round(contrast(faintest) * 100) / 100 : 0,
        faintest: faintest ? (faintest.id || faintest.className || faintest.tagName) : ""
    };
})()`;

// Types without waiting for the answer, to watch the page while it is on its way.
const typeNow = (query) => `(() => {
    const input = document.getElementById("routineSearch");
    input.focus();
    input.value = ${JSON.stringify(query)};
    input.dispatchEvent(new Event("input", {bubbles: true}));
    return true;
})()`;

// Reads the page every 50 ms for a while.
const watch = (ms) => `(async () => {
    const seen = [];
    const end = Date.now() + ${ms};
    while (Date.now() < end) {
        seen.push(${READ});
        await new Promise((done) => setTimeout(done, 50));
    }
    return seen;
})()`;

const type = (query) => `(async () => {
    const input = document.getElementById("routineSearch");
    input.focus();
    input.value = ${JSON.stringify(query)};
    input.dispatchEvent(new Event("input", {bubbles: true}));
    await new Promise((done) => setTimeout(done, 1200));
    return true;
})()`;

const click = (selector) => `(async () => {
    const el = document.querySelector(${JSON.stringify(selector)});
    if (!el || el.offsetParent === null) {
        return false;
    }
    el.click();
    await new Promise((done) => setTimeout(done, 900));
    return true;
})()`;

const openViewAll = `(async () => {
    const button = document.getElementById("viewAllBtn");
    if (button.offsetParent !== null && !document.getElementById("viewAllGrid").classList.contains("open")) {
        button.click();
        await new Promise((done) => setTimeout(done, 700));
    }
    return true;
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
                send("Page.handleJavaScriptDialog", {accept: true}, message.sessionId);
                return;
            }
            if (message.method === "Runtime.exceptionThrown") {
                errors.push(message.params.exceptionDetails.exception?.description
                    || message.params.exceptionDetails.text);
                return;
            }
            if (message.method === "Fetch.requestPaused") {
                const {requestId, request} = message.params;
                const answer = (answers.get(message.sessionId) || []).find((rule) => request.url.includes(rule.match));
                const fulfil = () => send("Fetch.fulfillRequest", {
                    requestId,
                    responseCode: answer.status,
                    responseHeaders: [{name: "Content-Type", value: "application/json"}],
                    body: Buffer.from(answer.body).toString("base64")
                }, message.sessionId);
                if (!answer) {
                    send("Fetch.continueRequest", {requestId}, message.sessionId);
                } else if (answer.hold) {
                    held.set(message.sessionId, [...(held.get(message.sessionId) || []), fulfil]);
                } else {
                    fulfil();
                }
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

// rules: requests answered in the browser; signedIn: the viewer's token is in localStorage.
async function open(client, {rules = [], width = 1280, theme = "original", signedIn = false, halloween = false,
    path = "/Explore.html"} = {}) {
    const {targetId} = await client.send("Target.createTarget", {url: "about:blank"});
    const {sessionId} = await client.send("Target.attachToTarget", {targetId, flatten: true});

    await client.send("Page.enable", {}, sessionId);
    await client.send("Runtime.enable", {}, sessionId);
    if (rules.length) {
        answers.set(sessionId, rules);
        await client.send("Fetch.enable", {
            patterns: rules.map((rule) => ({urlPattern: "*" + rule.match + "*"}))
        }, sessionId);
    }
    await client.send("Emulation.setDeviceMetricsOverride", {
        width, height: width < 600 ? 844 : 900, deviceScaleFactor: width < 600 ? 2 : 1, mobile: width < 600
    }, sessionId);
    await client.send("Page.addScriptToEvaluateOnNewDocument", {
        source: (signedIn
            ? `localStorage.setItem("token", ${JSON.stringify(fx.viewerToken)});`
            : `localStorage.removeItem("token");`)
            + `localStorage.setItem("copymylife-theme", ${JSON.stringify(theme)});`
    }, sessionId);
    await client.send("Page.navigate", {url: fx.base + path}, sessionId);
    await pause(2500);

    const view = {
        evaluate: async (expression) => (await client.send("Runtime.evaluate", {
            expression, returnByValue: true, awaitPromise: true
        }, sessionId)).result.value,
        read: () => view.evaluate(READ),
        release: async () => {
            (held.get(sessionId) || []).forEach((fulfil) => fulfil());
            held.delete(sessionId);
            await pause(800);
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
    if (halloween) {
        await view.evaluate(click('.chip[data-filter="halloween"]'));
    }
    return view;
}

// Opens Explore, searches once and reads the page, with View all open when asked.
async function searchOnce(client, query, options = {}) {
    const view = await open(client, options);
    if (options.chip) {
        await view.evaluate(click(`.chip[data-filter="${options.chip}"]`));
    }
    await view.evaluate(type(query));
    if (options.viewAll) {
        await view.evaluate(openViewAll);
    }
    const result = await view.read();
    await view.close();
    return result;
}

const profile = mkdtempSync(join(tmpdir(), "cml-search-"));
const {chrome, endpoint} = await startChrome(profile);

try {
    const client = await connect(endpoint);
    const results = {};

    // Every search signal, read from View all, where community routines are listed.
    results.signals = {
        title: await searchOnce(client, fx.title, {viewAll: true}),
        description: await searchOnce(client, fx.description, {viewAll: true}),
        author: await searchOnce(client, fx.personName, {viewAll: true}),
        username: await searchOnce(client, fx.username, {viewAll: true}),
        habit: await searchOnce(client, "COLD SHOWER " + fx.habit, {viewAll: true}),
        unicode: await searchOnce(client, "  ТАҢҒЫ жүгіру " + fx.marker + " ", {viewAll: true}),
        category: await searchOnce(client, "", {viewAll: true, chip: "study"}),
        profession: await searchOnce(client, fx.profession, {viewAll: true, signedIn: true}),
        goal: await searchOnce(client, fx.goal, {viewAll: true, signedIn: true}),
        guestProfession: await searchOnce(client, fx.profession, {viewAll: true})
    };
    // The study category without a word lists the study routine.
    results.signals.category.viewAll = results.signals.category.viewAll.filter((name) => name.includes(fx.title));

    // Featured people on the first screen: by name, by profession (role) and by a goal word.
    results.featured = {
        name: await searchOnce(client, "RONALDO"),
        profession: await searchOnce(client, "youtuber"),
        goal: await searchOnce(client, "mindfulness"),
        habitsWord: await searchOnce(client, "habits")
    };

    results.combined = {
        study: await searchOnce(client, fx.title, {viewAll: true, chip: "study"}),
        fitness: await searchOnce(client, fx.title, {viewAll: true, chip: "fitness"}),
        halloween: await searchOnce(client, fx.title, {viewAll: true, chip: "halloween"})
    };

    results.duplicates = {
        all: await searchOnce(client, "", {viewAll: true}),
        allSearch: await searchOnce(client, "routine", {viewAll: true}),
        fitness: await searchOnce(client, "", {viewAll: true, chip: "fitness"}),
        halloween: await searchOnce(client, "", {viewAll: true, chip: "halloween"})
    };

    // No results, Reset filters and Clear search, one after another on the same page.
    const flow = await open(client);
    await flow.evaluate(openViewAll);
    results.idle = await flow.read();
    await flow.evaluate(click('.chip[data-filter="halloween"]'));
    await flow.evaluate(type(NO_MATCH));
    results.noResults = await flow.read();
    await flow.snap("no-results");
    await flow.evaluate(click("#resetFiltersBtn"));
    // A category change folds View all away, as before; it is opened again to count it.
    await flow.evaluate(openViewAll);
    results.afterReset = await flow.read();
    await flow.evaluate(type("goggins"));
    results.typed = await flow.read();
    await flow.evaluate(click("#clearSearchBtn"));
    results.cleared = await flow.read();
    await flow.close();

    // Loading: the search answer is held back until the page has been read.
    const slow = await open(client, {rules: [{match: "/routines/search", status: 200, body: "[]", hold: true}]});
    await slow.evaluate(type("goggins"));
    results.loading = await slow.read();
    await slow.snap("loading");
    await slow.release();
    await slow.close();

    results.searchFailed = await searchOnce(client, "ronaldo", {
        rules: [{match: "/routines/search", status: 500, body: "{\"message\":\"down\"}"}]
    });

    results.emptyDatabase = await searchOnce(client, "", {
        rules: [
            {match: "/routines/built-in", status: 200, body: "[]"},
            {match: "/routines/public", status: 200, body: "[]"},
            {match: "/routines/search", status: 200, body: "[]"}
        ]
    });

    results.people = await searchOnce(client, fx.personName, {signedIn: true});
    results.peopleByGoal = await searchOnce(client, fx.goal, {signedIn: true});
    results.guestPeople = await searchOnce(client, fx.personName);
    results.guestPeopleByGoal = await searchOnce(client, fx.goal);

    // Searching, results, no results and an error, one after another; the first answer is held.
    const habitOnly = "cold shower " + fx.habit;
    const states = await open(client, {rules: [
        {match: "/routines/search?q=" + encodeURIComponent(habitOnly), status: 200, body: JSON.stringify([fx.studyRoutineId]), hold: true},
        {match: "/routines/search?q=zzqx-down", status: 500, body: "{\"message\":\"down\"}"}
    ]});
    await states.evaluate(openViewAll);
    await states.evaluate(typeNow(habitOnly));
    results.states = {waiting: await states.evaluate(watch(1500))};
    await states.snap("searching");
    await states.release();
    results.states.results = await states.read();
    await states.evaluate(type(NO_MATCH));
    results.states.noResults = await states.read();
    await states.evaluate(type("zzqx-down"));
    results.states.error = await states.read();
    await states.evaluate(type(fx.habit.toUpperCase()));
    results.states.again = await states.read();
    await states.close();

    // A search that finds nothing says so only once the answer is in.
    const none = await open(client, {rules: [{match: "/routines/search?q=" + NO_MATCH, status: 200, body: "[]", hold: true}]});
    await none.evaluate(openViewAll);
    await none.evaluate(typeNow(NO_MATCH));
    results.noneWaiting = await none.evaluate(watch(1200));
    await none.release();
    results.noneAnswered = await none.read();
    await none.close();

    // An older answer that arrives after a newer one changes nothing.
    const race = await open(client, {rules: [
        {match: "/routines/search?q=" + encodeURIComponent(fx.title), status: 200, body: JSON.stringify([fx.studyRoutineId]), hold: true}
    ]});
    await race.evaluate(openViewAll);
    await race.evaluate(typeNow(fx.title));
    await pause(600);
    await race.evaluate(type(NO_MATCH));
    results.raceBefore = await race.read();
    await race.release();
    results.raceAfter = await race.read();
    await race.close();

    // A visitor opens a public profile, directly and from a person found on Explore.
    const profileView = await open(client, {path: `/user-profile.html?id=${fx.ownerId}`});
    results.guestProfile = await profileView.evaluate(PROFILE_READ);
    await profileView.snap("guest-profile");
    await profileView.close();

    const through = await open(client);
    await through.evaluate(type(fx.personName));
    // Not awaited inside the page: the click leaves it.
    await through.evaluate(`document.querySelector('#peopleResults a[href="user-profile.html?id=${fx.ownerId}"]')?.click(); true`);
    await pause(2000);
    results.guestClickThrough = await through.evaluate(PROFILE_READ);
    await through.close();

    const bannedView = await open(client, {path: `/user-profile.html?id=${fx.bannedId}`});
    results.guestBannedProfile = await bannedView.evaluate(PROFILE_READ);
    await bannedView.close();

    results.guestProfileLayouts = {};
    for (const width of [360, 390, 1280, 1920]) {
        for (const theme of ["original", "autumn"]) {
            const view = await open(client, {path: `/user-profile.html?id=${fx.ownerId}`, width, theme});
            results.guestProfileLayouts[`${theme}${width}`] = await view.evaluate(PROFILE_LAYOUT);
            await view.snap(`guest-profile-${theme}-${width}`);
            await view.close();
        }
    }

    const tricky = await open(client, {signedIn: true});
    await tricky.evaluate(type(fx.tricky));
    const trickyPeople = await tricky.read();
    await tricky.evaluate(type(fx.trickyRoutine));
    await tricky.evaluate(openViewAll);
    const trickyRoutines = await tricky.read();
    results.xss = {
        xss: trickyPeople.xss || trickyRoutines.xss,
        people: trickyPeople.people.map((person) => person.text),
        viewAll: trickyRoutines.viewAll
    };
    await tricky.close();
    results.injection = await searchOnce(client, "' OR 1=1 --", {viewAll: true});

    // Every design and width, with people and a message on the page.
    results.layouts = {};
    for (const width of [360, 390, 1280, 1366, 1920]) {
        for (const [design, options] of Object.entries({
            original: {theme: "original"},
            autumn: {theme: "autumn"},
            halloween: {theme: "original", halloween: true},
            autumnHalloween: {theme: "autumn", halloween: true}
        })) {
            const view = await open(client, {...options, width, signedIn: true});
            await view.evaluate(type(fx.personName));
            await view.evaluate(`window.scrollTo(0, 0); true`);
            results.layouts[`${design}${width}`] = await view.evaluate(LAYOUT);
            await view.snap(`${design}-${width}`);
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
