// US-26: in headless Chrome a signed-in user opens Find Similar People in the profile's AI Assistant
// section (profile.html#ai-matches), presses Find My Matches, sees the loading state, then cards
// with the score, the reasons and links to the profile and public routines; a user who has written
// nothing gets the empty state, an unavailable matching service gives the error state with Try
// again, and a visitor following the old page address is sent to sign in. The cards are measured in
// both designs from a 360 px phone to a 1920 px desktop, and Explore's AI Assistant button leads there.
// Started by LifestyleMatchingRegressionTest, whose stand-in matching service it switches with
// GET <stub>/__mode; talks only to 127.0.0.1.
//
// Usage: node lifestyle-matches-check.mjs <chrome> <fixtures.json> <results.json>
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
// Optional: set MATCH_SCREENSHOTS to a folder to keep a picture of each state.
const screenshots = process.env.MATCH_SCREENSHOTS;

const mode = async (name, delay = 0) => {
    const response = await fetch(`${fx.stub}/__mode?mode=${name}&delay=${delay}`);
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
        state: () => ["lmGuest", "lmLoading", "lmEmpty", "lmError", "lmResults"]
            .filter((id) => window.__f.visible(document.getElementById(id)))
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
    const root = document.getElementById("lifestyleMatches");
    const parts = [root, ...root.querySelectorAll("*")].filter(visible);
    const buttons = parts.filter((el) => el.tagName === "BUTTON" || el.tagName === "A");
    // An avatar's initial is decoration; the name is written next to it.
    const texts = parts.filter((el) => !el.classList.contains("lm-avatar-fallback"))
        .filter((el) => [...el.childNodes].some((n) => n.nodeType === 3 && n.textContent.trim()));
    const faintest = texts.length ? texts.reduce((a, b) => contrast(a) <= contrast(b) ? a : b) : null;
    const cards = [...document.querySelectorAll(".lm-card")];
    return {
        width: innerWidth,
        overflow: document.documentElement.scrollWidth - innerWidth,
        cards: cards.length,
        cardsInside: cards.length > 0 && cards.every((el) => {
            const box = el.getBoundingClientRect();
            return box.left >= -1 && box.right <= innerWidth + 1;
        }),
        actionsInside: buttons.length > 0 && buttons.every((el) => {
            const box = el.getBoundingClientRect();
            return box.left >= -1 && box.right <= innerWidth + 1;
        }),
        minContrast: faintest ? Math.round(contrast(faintest) * 100) / 100 : 0,
        faintest: faintest ? (faintest.id || faintest.className || faintest.tagName) + " " + faintest.textContent.trim().slice(0, 24) : ""
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
            const {data} = await client.send("Page.captureScreenshot", {format: "png", captureBeyondViewport: false}, sessionId);
            writeFileSync(join(screenshots, `${name}.png`), Buffer.from(data, "base64"));
        },
        close: () => client.send("Target.closeTarget", {targetId})
    };
}

const PAGE = "/profile.html#ai-matches";
const find = `(async () => {
    document.getElementById("findMatchesBtn").click();
    await window.__f.until("#lmResults, #lmEmpty, #lmError");
    await window.__f.wait(300);
    return window.__f.state();
})()`;

const profile = mkdtempSync(join(tmpdir(), "cml-match-"));
const {chrome, endpoint} = await startChrome(profile);

try {
    const client = await connect(endpoint);
    const results = {};

    // A visitor following the old page address ends on the sign-in page; nothing is requested.
    const guest = await open(client, "/lifestyle-matches.html");
    results.guest = await guest.evaluate(`({
        page: location.pathname,
        matches: !!document.getElementById("findMatchesBtn")
    })`);
    await guest.snap("guest");
    await guest.close();

    // AC1 + AC2: loading, then the cards.
    await mode("words", 2500);
    const viewer = await open(client, PAGE, {token: fx.viewer.token});
    results.loading = await viewer.evaluate(`(async () => {
        const button = document.getElementById("findMatchesBtn");
        const before = window.__f.state();
        button.click();
        await window.__f.wait(400);
        return {before, during: window.__f.state(), disabled: button.disabled,
            text: window.__f.text(document.getElementById("lmLoading"))};
    })()`);
    await viewer.snap("loading");
    results.results = await viewer.evaluate(`(async () => {
        await window.__f.until("#lmResults");
        await window.__f.wait(300);
        const cards = [...document.querySelectorAll(".lm-card")];
        return {
            state: window.__f.state(),
            ids: cards.map((card) => Number(card.dataset.userId)),
            first: cards[0] ? {
                name: window.__f.text(cards[0].querySelector(".lm-name")),
                username: window.__f.text(cards[0].querySelector(".lm-username")),
                score: window.__f.text(cards[0].querySelector(".lm-score")),
                reasons: [...cards[0].querySelectorAll(".lm-reasons li")].map(window.__f.text),
                parts: window.__f.text(cards[0].querySelector(".lm-parts")),
                avatar: !!cards[0].querySelector(".lm-avatar"),
                profile: cards[0].querySelector(".lm-actions a")?.getAttribute("href"),
                routinesLink: cards[0].querySelectorAll(".lm-actions a")[1]?.getAttribute("href"),
                routines: [...cards[0].querySelectorAll(".lm-routines a")].map((a) => a.getAttribute("href"))
            } : null,
            names: cards.map((card) => window.__f.text(card.querySelector(".lm-name"))),
            markupInjected: !!document.querySelector(".lm-card img[src='x']") || window.__xss === 1,
            button: window.__f.text(document.getElementById("findMatchesBtn"))
        };
    })()`);
    await viewer.snap("results");

    // AC2: View Profile and View Public Routines lead to the member's existing pages.
    const firstProfile = results.results.first?.profile;
    const firstRoutine = results.results.first?.routines?.[0];
    await viewer.close();
    if (firstProfile) {
        const page = await open(client, "/" + firstProfile, {token: fx.viewer.token});
        results.profilePage = await page.evaluate(`(async () => {
            await window.__f.until("#publicRoutinesGrid *");
            return {
                name: window.__f.text(document.getElementById("userProfileName")),
                routines: window.__f.text(document.getElementById("publicRoutinesGrid")),
                anchor: !!document.getElementById("publicRoutinesSection")
            };
        })()`);
        await page.close();
    }
    if (firstRoutine) {
        const page = await open(client, "/" + firstRoutine, {token: fx.viewer.token});
        results.routinePage = await page.evaluate(`(async () => {
            for (let i = 0; i < 60 && !window.__f.text(document.getElementById("routineTitle")); i++) await window.__f.wait(250);
            return {title: window.__f.text(document.getElementById("routineTitle"))};
        })()`);
        await page.close();
    }

    // Empty state for someone who has written nothing.
    await mode("words");
    const empty = await open(client, PAGE, {token: fx.empty.token});
    results.empty = await empty.evaluate(`(async () => {
        const state = await (${find});
        return {state, title: window.__f.text(document.getElementById("lmEmptyTitle")),
            text: window.__f.text(document.getElementById("lmEmptyText")),
            action: window.__f.visible(document.getElementById("lmEmptyAction"))};
    })()`);
    await empty.snap("empty");
    await empty.close();

    // Error state while the matching service fails, then Try again once it is back.
    await mode("error");
    const failing = await open(client, PAGE, {token: fx.viewer.token});
    results.error = await failing.evaluate(`(async () => ({
        state: await (${find}),
        text: window.__f.text(document.getElementById("lmErrorText")),
        button: !document.getElementById("findMatchesBtn").disabled
    }))()`);
    await failing.snap("error");
    await mode("words");
    results.retry = await failing.evaluate(`(async () => {
        document.getElementById("lmRetry").click();
        await window.__f.until("#lmResults");
        return {state: window.__f.state(), cards: document.querySelectorAll(".lm-card").length};
    })()`);
    await failing.close();

    // Layout of the cards in both designs and every width.
    results.layouts = {};
    for (const width of [360, 390, 768, 1280, 1366, 1920]) {
        for (const theme of ["original", "autumn"]) {
            const page = await open(client, PAGE, {token: fx.viewer.token, width, theme});
            await page.evaluate(find);
            results.layouts[`matches-${theme}${width}`] = await page.evaluate(LAYOUT);
            await page.snap(`matches-${theme}-${width}`);
            await page.close();
        }
    }

    // Explore's one AI Assistant button leads to the profile's AI section in both designs.
    results.explore = {};
    for (const width of [390, 1280]) {
        for (const theme of ["original", "autumn"]) {
            const page = await open(client, "/Explore.html", {token: fx.viewer.token, width, theme});
            results.explore[`${theme}${width}`] = await page.evaluate(`(() => {
                const link = document.getElementById("aiAssistantButton");
                const box = link ? link.getBoundingClientRect() : null;
                return {
                    visible: window.__f.visible(link),
                    href: link?.getAttribute("href") || "",
                    aiLinks: document.querySelectorAll('a[href*="assistant.html"], a[href*="lifestyle-matches"], a[href*="#ai-"]').length,
                    inside: !!box && box.left >= -1 && box.right <= innerWidth + 1,
                    overflow: document.documentElement.scrollWidth - innerWidth
                };
            })()`);
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
