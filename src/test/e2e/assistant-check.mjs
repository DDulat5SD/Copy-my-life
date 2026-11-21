// Routine assistant: in headless Chrome a signed-in user fills the generator from a sentence,
// generates a day (loading state, preview, reasons, public routine ideas) and saves it as a new
// routine; reviews a routine, approves a change, previews and applies it; sees a length
// suggestion and keeps the plan; opens the daily summary and enters an actual time. Empty, error
// and guest states are checked, and the page is measured in both designs from 360 to 1920 px.
// Started by RoutineAssistantRegressionTest, whose stand-in AI service it switches with
// GET <stub>/__mode; talks only to 127.0.0.1.
//
// Usage: node assistant-check.mjs <chrome> <fixtures.json> <results.json>
import {spawn} from "node:child_process";
import {mkdtempSync, readFileSync, rmSync, writeFileSync} from "node:fs";
import {tmpdir} from "node:os";
import {join} from "node:path";

const [chromePath, fixturesPath, resultsPath] = process.argv.slice(2);
const fx = JSON.parse(readFileSync(fixturesPath, "utf8"));

for (const url of [fx.base, fx.stub]) {
    if (!/^http:\/\/127\.0\.0\.1:\d+$/.test(url)) {
        throw new Error("Refusing to run against a non-local target");
    }
}

const pause = (ms) => new Promise((done) => setTimeout(done, ms));
const dialogs = [];
const errors = [];
// Optional: set ASSISTANT_SCREENSHOTS to a folder to keep a picture of each state.
const screenshots = process.env.ASSISTANT_SCREENSHOTS;

const mode = async (name) => {
    const response = await fetch(`${fx.stub}/__mode?mode=${name}`);
    if (!response.ok) throw new Error("stub mode " + name + " failed");
};

const HELPERS = `
    window.__f = {
        wait: (ms) => new Promise((done) => setTimeout(done, ms)),
        text: (el) => (el?.textContent || "").trim().replace(/\\s+/g, " "),
        visible: (el) => !!el && !el.hidden && el.offsetParent !== null,
        until: async (selector, ms = 15000) => {
            for (let waited = 0; waited < ms; waited += 100) {
                const found = document.querySelector(selector);
                if (found && found.offsetParent !== null) return found;
                await window.__f.wait(100);
            }
            return null;
        },
        untilText: async (selector, test, ms = 15000) => {
            for (let waited = 0; waited < ms; waited += 100) {
                const found = document.querySelector(selector);
                if (found && test((found.textContent || "").trim())) return found;
                await window.__f.wait(100);
            }
            return null;
        }
    };
`;

const LAYOUT = `(() => {
    const parse = (value) => {
        const found = value.match(/rgba?\\(([^)]+)\\)/);
        if (!found) return null;
        const parts = found[1].split(/[\\s,\\/]+/).filter(Boolean).map(Number);
        return {r: parts[0], g: parts[1], b: parts[2], a: parts.length > 3 ? parts[3] : 1};
    };
    const channel = (v) => { v /= 255; return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4); };
    const luminance = (c) => 0.2126 * channel(c.r) + 0.7152 * channel(c.g) + 0.0722 * channel(c.b);
    const background = (el) => {
        for (let node = el; node; node = node.parentElement) {
            const color = parse(getComputedStyle(node).backgroundColor);
            if (color && color.a > 0.5) return color;
        }
        return {r: 255, g: 255, b: 255, a: 1};
    };
    const contrast = (el) => {
        const a = luminance(parse(getComputedStyle(el).color));
        const b = luminance(background(el));
        return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
    };
    const visible = (el) => !!el && !el.hidden && el.offsetParent !== null;
    const root = document.getElementById("routineAssistant");
    const parts = [root, ...root.querySelectorAll("*")].filter(visible);
    const controls = parts.filter((el) => ["BUTTON", "A", "INPUT", "SELECT", "TEXTAREA"].includes(el.tagName));
    const texts = parts.filter((el) => !["OPTION", "SELECT", "INPUT", "TEXTAREA"].includes(el.tagName))
        .filter((el) => !el.closest("button:disabled") && !el.matches("button:disabled"))
        .filter((el) => [...el.childNodes].some((n) => n.nodeType === 3 && n.textContent.trim()));
    const faintest = texts.length ? texts.reduce((a, b) => contrast(a) <= contrast(b) ? a : b) : null;
    const outside = controls.filter((el) => {
        const box = el.getBoundingClientRect();
        return box.left < -1 || box.right > innerWidth + 1;
    });
    return {
        width: innerWidth,
        overflow: document.documentElement.scrollWidth - innerWidth,
        inside: outside.length === 0,
        outside: outside.slice(0, 3).map((el) => el.tagName + "#" + el.id + "." + el.className).join(", "),
        minContrast: faintest ? Math.round(contrast(faintest) * 100) / 100 : 0,
        faintest: faintest ? (faintest.id || faintest.className || faintest.tagName) + " " + faintest.textContent.trim().slice(0, 30) : ""
    };
})()`;

function startChrome(profile) {
    return new Promise((resolve, reject) => {
        const chrome = spawn(chromePath, [
            "--headless=new", "--remote-debugging-port=0", `--user-data-dir=${profile}`,
            "--no-first-run", "--disable-extensions", "--disable-gpu",
            "--host-resolver-rules=MAP * ~NOTFOUND, EXCLUDE 127.0.0.1"
        ], {stdio: ["ignore", "ignore", "pipe"]});
        let output = "";
        chrome.stderr.on("data", (chunk) => {
            output += chunk;
            const found = output.match(/DevTools listening on (ws:\/\/\S+)/);
            if (found) resolve({chrome, endpoint: found[1]});
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
                errors.push(message.params.exceptionDetails.exception?.description || message.params.exceptionDetails.text);
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

async function open(client, path, {token = null, width = 1280, theme = "original"} = {}) {
    const {targetId} = await client.send("Target.createTarget", {url: "about:blank"});
    const {sessionId} = await client.send("Target.attachToTarget", {targetId, flatten: true});
    await client.send("Page.enable", {}, sessionId);
    await client.send("Runtime.enable", {}, sessionId);
    await client.send("Emulation.setDeviceMetricsOverride", {
        width, height: width < 600 ? 844 : 900, deviceScaleFactor: width < 600 ? 2 : 1, mobile: width < 600
    }, sessionId);
    await client.send("Emulation.setTimezoneOverride", {timezoneId: "Asia/Almaty"}, sessionId);
    await client.send("Page.addScriptToEvaluateOnNewDocument", {
        source: (token ? `localStorage.setItem("token", ${JSON.stringify(token)});` : `localStorage.removeItem("token");`)
            + `localStorage.setItem("copymylife-theme", ${JSON.stringify(theme)});`
            + HELPERS
    }, sessionId);
    await client.send("Page.navigate", {url: fx.base + path}, sessionId);
    await pause(2000);
    return {
        evaluate: async (expression) => (await client.send("Runtime.evaluate", {
            expression, returnByValue: true, awaitPromise: true
        }, sessionId)).result.value,
        snap: async (name) => {
            if (!screenshots) return;
            const {data} = await client.send("Page.captureScreenshot", {format: "png", captureBeyondViewport: true}, sessionId);
            writeFileSync(join(screenshots, `${name}.png`), Buffer.from(data, "base64"));
        },
        close: () => client.send("Target.closeTarget", {targetId})
    };
}

const PAGE = "/profile.html";
const GENERATE = `(async () => {
    const loading = document.getElementById("genLoading");
    window.__loadingSeen = false;
    new MutationObserver(() => { if (!loading.hidden) window.__loadingSeen = true; })
        .observe(loading, {attributes: true});
    const first = document.querySelector("#gHabits .ra-item [data-field=title]");
    if (first && !first.value) {
        first.value = "Java Study";
        document.querySelector("#gHabits .ra-item [data-field=minutes]").value = "90";
        document.querySelector("#gHabits .ra-item [data-field=kind]").value = "STUDY";
    }
    document.getElementById("raGenerate").click();
    await window.__f.until("#genResult, #genError");
    await window.__f.wait(300);
    return window.__f.visible(document.getElementById("genResult"));
})()`;

const profile = mkdtempSync(join(tmpdir(), "cml-assistant-"));
const {chrome, endpoint} = await startChrome(profile);

try {
    const client = await connect(endpoint);
    const results = {states: {}};

    // A visitor following the old assistant address is sent to sign in.
    const guest = await open(client, "/assistant.html");
    results.states.guest = await guest.evaluate(`location.pathname === "/login.html"
        && !document.getElementById("routineAssistant")`);
    await guest.close();

    // One AI section in the profile: its place in the menu, moving between sections without
    // drawing it twice or doubling its buttons, and Back/Forward between its tabs.
    const navigation = await open(client, PAGE + "#ai-generate", {token: fx.viewer.token});
    results.navigation = await navigation.evaluate(`(async () => {
        const shown = (id) => getComputedStyle(document.getElementById(id)).display !== "none";
        const selected = (tab) => document.getElementById("tab-" + tab).getAttribute("aria-selected") === "true";
        let plans = 0;
        const realFetch = window.fetch;
        window.fetch = (url, init) => {
            if (String(url).startsWith("/assistant/plans") && !String(url).includes("save")) plans++;
            return realFetch(url, init);
        };
        const order = [...document.querySelectorAll(".profile-navigation .profile-nav-btn")].map((b) => b.id);
        const result = {
            order,
            openedByAddress: shown("aiAssistantContent") && selected("generate") && !shown("calendarContent"),
            activeNav: document.querySelector(".profile-nav-btn.active")?.id || ""
        };

        document.getElementById("calendarNav").click();
        await window.__f.wait(300);
        result.calendarShown = shown("calendarContent") && !shown("aiAssistantContent");
        document.getElementById("aiAssistantNav").click();
        await window.__f.wait(300);
        result.backToAi = shown("aiAssistantContent") && !shown("calendarContent") && selected("generate");
        result.drawnOnce = document.querySelectorAll("#routineAssistant .ra-tabs").length === 1
            && document.querySelectorAll("#gHabits").length === 1;

        const first = document.querySelector("#gHabits .ra-item [data-field=title]");
        first.value = "Navigation check";
        document.getElementById("raGenerate").click();
        await window.__f.until("#genResult, #genError");
        await window.__f.wait(500);
        result.plansPerClick = plans;

        document.getElementById("tab-review").click();
        await window.__f.wait(200);
        document.getElementById("tab-summary").click();
        await window.__f.wait(200);
        result.hashAfterTabs = location.hash;
        history.back();
        await window.__f.wait(700);
        result.backHash = location.hash;
        result.backTab = selected("review") && window.__f.visible(document.getElementById("panel-review"));
        history.back();
        await window.__f.wait(700);
        result.back2Hash = location.hash;
        history.back();
        await window.__f.wait(700);
        result.back3Hash = location.hash;
        result.back3Calendar = shown("calendarContent") && !shown("aiAssistantContent");
        history.forward();
        await window.__f.wait(700);
        result.forwardHash = location.hash;
        result.forwardAi = shown("aiAssistantContent") && selected("generate");

        const sections = {myRoutinesNav: "accountContent", myHabitsNav: "habitsContent", progressNav: "progressContent",
            friendsNav: "friendsContent", todoListsNav: "todoListsContent", postsNav: "postsContent",
            settingsNav: "themeSettingsContent", calendarNav: "calendarContent"};
        result.otherSections = {};
        for (const [nav, content] of Object.entries(sections)) {
            document.getElementById(nav).click();
            await window.__f.wait(300);
            result.otherSections[nav] = shown(content) && !shown("aiAssistantContent");
        }
        document.getElementById("aiAssistantNav").click();
        await window.__f.wait(300);
        result.aiAgain = shown("aiAssistantContent") && !shown("themeSettingsContent") && !shown("calendarContent");
        return result;
    })()`);
    await navigation.snap("navigation");
    await navigation.close();

    // Old addresses of the standalone AI pages open the matching tab in the profile.
    results.redirects = {};
    for (const [name, path, tab, wait] of [
        ["assistant", "/assistant.html", "generate", "#gHabits"],
        ["review", "/assistant.html?review=" + fx.reviewRoutine, "review", "#reviewIssues li"],
        ["matches", "/lifestyle-matches.html", "matches", "#findMatchesBtn"]]) {
        const page = await open(client, path, {token: fx.viewer.token});
        results.redirects[name] = await page.evaluate(`(async () => {
            const ready = await window.__f.until(${JSON.stringify(wait)});
            return {
                page: location.pathname + location.search + location.hash,
                shown: !!ready && getComputedStyle(document.getElementById("aiAssistantContent")).display !== "none",
                tab: document.getElementById("tab-${tab}").getAttribute("aria-selected") === "true"
            };
        })()`);
        await page.close();
    }

    // Explore has one AI button, and it opens the profile's AI section.
    const explore = await open(client, "/Explore.html", {token: fx.viewer.token});
    results.explore = await explore.evaluate(`({
        buttons: document.querySelectorAll("#aiAssistantButton").length,
        oldLinks: document.querySelectorAll("#findSimilarPeopleLink, #routineAssistantLink, a[href*='assistant.html'], a[href*='lifestyle-matches']").length,
        text: window.__f.text(document.getElementById("aiAssistantButton")),
        icon: !!document.querySelector("#aiAssistantButton svg")
    })`);
    await explore.evaluate(`document.getElementById("aiAssistantButton").click()`);
    await pause(2500);
    results.explore.after = await explore.evaluate(`({
        page: location.pathname + location.hash,
        shown: getComputedStyle(document.getElementById("aiAssistantContent")).display !== "none"
    })`);
    await explore.close();

    // Generate: quick fill, loading, preview with reasons and ideas, then save.
    await mode("words");
    const generator = await open(client, PAGE + "#ai-generate", {token: fx.viewer.token});
    results.generate = await generator.evaluate(`(async () => {
        document.getElementById("raQuickText").value =
            "I wake up at 07:00, study Java, go to the gym, need lunch at 13:00 and prefer frequent breaks.";
        document.getElementById("raQuickFill").click();
        await window.__f.until("#raQuickResult");
        const filled = document.getElementById("gWake").value === "07:00"
            && document.getElementById("gLunch").value === "13:00"
            && document.getElementById("gRest").value === "FREQUENT"
            && document.querySelectorAll("#gHabits .ra-item").length === 2;
        const shown = await (${GENERATE});
        const slots = [...document.querySelectorAll("#genTimeline .ra-slot")];
        const lunch = slots.find((slot) => slot.querySelector("[data-field=title]").value === "Lunch");
        const result = {
            filled, shown, loadingSeen: window.__loadingSeen, slots: slots.length,
            lunch: lunch ? lunch.querySelector("[data-field=start]").value : "",
            why: document.querySelectorAll("#genWhy li").length,
            examples: document.getElementById("exList").dataset.status || "",
            exampleCards: document.querySelectorAll("#exList .ra-example").length
        };
        const add = document.querySelector("#exList .ra-chip__add");
        if (add) {
            const before = document.querySelectorAll("#gHabits .ra-item").length;
            add.click();
            result.ideaAdded = document.querySelectorAll("#gHabits .ra-item").length === before + 1;
        }
        document.getElementById("saveTitle").value = "Browser planned day";
        document.getElementById("raSave").click();
        await window.__f.untilText("#saveResult", (text) => text.length > 0);
        result.saveText = window.__f.text(document.getElementById("saveResult"));
        result.savedLink = document.querySelector("#saveResult a")?.getAttribute("href") || "";
        return result;
    })()`);
    await generator.snap("generate");
    await generator.close();

    // Review: approve the break suggestion, preview it, apply it.
    const reviewer = await open(client, PAGE + "?review=" + fx.reviewRoutine + "#ai-review", {token: fx.viewer.token});
    results.review = await reviewer.evaluate(`(async () => {
        await window.__f.until("#reviewIssues li");
        const issues = [...document.querySelectorAll("#reviewIssues li")].map((li) => li.dataset.code);
        const box = document.querySelector("#reviewIssues li[data-code=NO_BREAK] .ra-approve");
        if (box) box.checked = true;
        document.getElementById("raPreview").click();
        await window.__f.until("#previewBox");
        const previewShown = window.__f.visible(document.getElementById("previewBox"))
            && document.querySelectorAll("#previewTimeline .ra-slot--new").length > 0;
        const previewNote = window.__f.text(document.getElementById("previewNote"));
        document.getElementById("raApply").click();
        await window.__f.untilText("#reviewMessage", (text) => text.startsWith("Applied") || text.length > 40);
        return {issues, previewShown, previewNote, appliedMessage: window.__f.text(document.getElementById("reviewMessage"))};
    })()`);
    await reviewer.snap("review");
    await reviewer.close();

    const routinePage = await open(client, "/routine.html?id=" + fx.reviewRoutine, {token: fx.viewer.token});
    results.review.ownerLink = await routinePage.evaluate(`(async () => {
        const link = await window.__f.until("#reviewWithAssistantLink");
        return !!link && link.getAttribute("href").startsWith("profile.html?review=");
    })()`);
    await routinePage.close();

    // AI suggestions: a habit that usually runs longer; the user keeps the plan.
    const durations = await open(client, PAGE + "#ai-suggestions", {token: fx.viewer.token});
    results.durations = await durations.evaluate(`(async () => {
        const card = await window.__f.until('#durList li[data-routine-habit-id="${fx.eveningLink}"]');
        const longer = !!card && card.classList.contains("ra-insight--often_longer");
        card?.querySelector("[data-action=keep]")?.click();
        let kept = false;
        for (let i = 0; i < 100 && !kept; i++) {
            await window.__f.wait(100);
            kept = !!document.querySelector('#durList li[data-routine-habit-id="${fx.eveningLink}"].ra-insight--kept_by_you');
        }
        return {longer, keptAfterClick: kept};
    })()`);
    await durations.snap("durations");
    await durations.close();

    // Daily summary: highlights, then an actual time entered for today's habit.
    const summary = await open(client, PAGE + "#ai-summary", {token: fx.viewer.token});
    results.summary = await summary.evaluate(`(async () => {
        await window.__f.until("#sumResult");
        const highlights = window.__f.text(document.getElementById("sumHighlights"));
        const row = document.querySelector('#sumRuns li[data-routine-habit-id="${fx.eveningLink}"]');
        const input = row?.querySelector("input");
        if (input) {
            input.value = "17";
            row.querySelector("button").click();
            await window.__f.untilText("#sumRuns", (text) => text.includes("17 min (entered by you)"));
        }
        return {highlights, rowFound: !!row, entered: window.__f.text(document.getElementById("sumRuns")).includes("17 min (entered by you)")};
    })()`);
    await summary.snap("summary");
    await summary.close();

    // Empty and error states.
    const empty = await open(client, PAGE + "#ai-summary", {token: fx.empty.token});
    results.states.summaryEmpty = await empty.evaluate(`(async () => !!(await window.__f.until("#sumEmpty")))()`);
    results.states.durationsEmpty = await empty.evaluate(`(async () => {
        document.getElementById("tab-suggestions").click();
        return !!(await window.__f.until("#durEmpty"));
    })()`);
    results.states.generateError = await empty.evaluate(`(async () => {
        document.getElementById("tab-generate").click();
        document.getElementById("raGenerate").click();
        await window.__f.until("#genError");
        return window.__f.text(document.getElementById("genError"));
    })()`);
    await empty.snap("empty");
    await empty.close();

    await mode("error");
    const offline = await open(client, PAGE + "#ai-generate", {token: fx.viewer.token});
    results.states.examplesUnavailable = await offline.evaluate(`(async () => {
        await (${GENERATE});
        return document.getElementById("exList").dataset.status || "";
    })()`);
    results.states.markupInjected = await offline.evaluate(`!!document.querySelector("img[src='x']") || window.__xss === 1`);
    await offline.snap("offline");
    await offline.close();
    await mode("words");

    // Layout in both designs and every width, with a generated plan and the other panels.
    results.layouts = {};
    for (const width of [360, 390, 768, 1280, 1366, 1920]) {
        for (const theme of ["original", "autumn"]) {
            const page = await open(client, PAGE + "#ai-generate", {token: fx.viewer.token, width, theme});
            await page.evaluate(GENERATE);
            const views = [await page.evaluate(LAYOUT)];
            for (const tab of ["preferences", "review", "suggestions", "summary", "matches"]) {
                views.push(await page.evaluate(`(async () => {
                    document.getElementById("tab-${tab}").click();
                    if ("${tab}" === "review") document.getElementById("raReview").click();
                    await window.__f.wait(1500);
                    return ${LAYOUT};
                })()`));
            }
            results.layouts[`assistant-${theme}${width}`] = {
                width,
                overflow: Math.max(...views.map((v) => v.overflow)),
                inside: views.every((v) => v.inside),
                outside: views.map((v) => v.outside).filter(Boolean).join(" | "),
                minContrast: Math.min(...views.map((v) => v.minContrast || 99)),
                faintest: views.reduce((a, b) => (a.minContrast || 99) <= (b.minContrast || 99) ? a : b).faintest
            };
            await page.snap(`layout-${theme}-${width}`);
            await page.close();
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
