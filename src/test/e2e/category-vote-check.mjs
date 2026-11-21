// US-28: on a routine's page in headless Chrome, a visitor is asked to sign in, a user is asked to
// like and rate first, then likes, rates, cancels a vote, votes with a double click, sees a closed
// vote's result and a refusal; then the panel and the form are measured in both designs from a
// 360 px phone to a 1920 px desktop.
// Started by CommunityCategoryVotingRegressionTest; talks only to 127.0.0.1.
//
// Usage: node category-vote-check.mjs <chrome> <fixtures.json> <results.json>
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
// Optional: set CATEGORY_VOTE_SCREENSHOTS to a folder to keep a picture of each state.
const screenshots = process.env.CATEGORY_VOTE_SCREENSHOTS;

const HELPERS = `
    window.__v = {
        token: localStorage.getItem("token"),
        wait: (ms) => new Promise((done) => setTimeout(done, ms)),
        text: (el) => (el?.textContent || "").trim().replace(/\\s+/g, " "),
        visible: (el) => !!el && !el.hidden && el.offsetParent !== null,
        summary: async (routine) => (await fetch("/routines/" + routine + "/category-vote",
            {headers: {Authorization: "Bearer " + window.__v.token}})).json(),
        // Opens the form and picks a category by its name.
        choose: async (name) => {
            document.getElementById("categoryVoteBtn").click();
            await window.__v.wait(600);
            const option = [...document.querySelectorAll("#categoryVoteOptions label")]
                .find((label) => window.__v.text(label) === name);
            option.querySelector("input").click();
        },
        state: () => {
            const v = window.__v;
            const button = document.getElementById("categoryVoteBtn");
            return {
                panelVisible: v.visible(document.getElementById("categoryVote")),
                message: v.text(document.getElementById("categoryVoteMessage")),
                results: v.text(document.getElementById("categoryVoteResults")),
                buttonDisabled: !!button && button.disabled,
                buttonHidden: !v.visible(button),
                modalOpen: document.getElementById("categoryVoteModal").classList.contains("show"),
                error: v.visible(document.getElementById("categoryVoteError")) ? v.text(document.getElementById("categoryVoteError")) : "",
                notice: v.text(document.getElementById("copyNotice")),
                categoryShown: v.text(document.getElementById("routineCategory")),
                xss: window.__xss === 1
            };
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
    const roots = [document.getElementById("categoryVote"), document.querySelector("#categoryVoteModal .routine-edit-modal")];
    const parts = roots.flatMap((root) => [root, ...root.querySelectorAll("*")]).filter(visible);
    const outside = parts.filter((el) => {
        const box = el.getBoundingClientRect();
        return box.width > 0 && (box.left < -1 || box.right > innerWidth + 1);
    });
    const inView = (el) => {
        const box = el.getBoundingClientRect();
        return visible(el) && box.left >= 0 && box.right <= innerWidth;
    };
    const texts = parts.filter((el) => [...el.childNodes].some((n) => n.nodeType === 3 && n.textContent.trim()));
    const faintest = texts.length ? texts.reduce((a, b) => contrast(a) <= contrast(b) ? a : b) : null;
    return {
        width: innerWidth,
        overflow: document.documentElement.scrollWidth - innerWidth,
        inside: outside.length === 0,
        outside: outside.slice(0, 3).map((el) => el.id || el.className || el.tagName).join(", "),
        actionsVisible: inView(document.getElementById("categoryVoteConfirm")) && inView(document.getElementById("categoryVoteCancel")),
        othersVisible: visible(document.getElementById("likeBtn")) && visible(document.getElementById("ratingInput"))
            && visible(document.getElementById("copyRoutineBtn")),
        measured: texts.length,
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

async function open(client, routine, {token = null, width = 1280, theme = "original"} = {}) {
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
    await pause(2800);
    return {
        evaluate: async (expression) => (await client.send("Runtime.evaluate", {
            expression, returnByValue: true, awaitPromise: true
        }, sessionId)).result.value,
        snap: async (name) => {
            if (!screenshots) return;
            const {data} = await client.send("Page.captureScreenshot", {format: "png"}, sessionId);
            writeFileSync(join(screenshots, `${name}.png`), Buffer.from(data, "base64"));
        },
        close: () => client.send("Target.closeTarget", {targetId})
    };
}

const STATE = `window.__v.state()`;
const profile = mkdtempSync(join(tmpdir(), "cml-vote-"));
const {chrome, endpoint} = await startChrome(profile);

try {
    const client = await connect(endpoint);
    const results = {};

    // A visitor is asked to sign in; a user who has not liked and rated is told to.
    const guest = await open(client, fx.open);
    const guestState = (await guest.evaluate(STATE)) || {};
    await guest.close();

    const page = await open(client, fx.open, {token: fx.voterToken});
    results.before = {...((await page.evaluate(STATE)) || {}), guestMessage: guestState.message || ""};
    await page.snap("before");

    // Like and rate on the page, then open the form and cancel.
    await page.evaluate(`(async () => {
        document.getElementById("likeBtn").click();
        await window.__v.wait(800);
        document.querySelector(".rating-star-btn[data-value='5']").click();
        await window.__v.wait(1200);
        return true;
    })()`);
    results.cancel = (await page.evaluate(`(async () => {
        const v = window.__v;
        await v.choose("Fitness");
        const options = [...document.querySelectorAll("#categoryVoteOptions label")].map((label) => v.text(label));
        document.getElementById("categoryVoteCancel").click();
        await v.wait(600);
        return {...v.state(), options, votesAfter: (await v.summary(${fx.open})).totalVotes};
    })()`)) || {};

    // Vote, confirmed twice in a row.
    results.vote = (await page.evaluate(`(async () => {
        const v = window.__v;
        await v.choose("Fitness");
        const confirm = document.getElementById("categoryVoteConfirm");
        confirm.click();
        confirm.click();
        await v.wait(1800);
        return {...v.state(), votesAfter: (await v.summary(${fx.open})).totalVotes};
    })()`)) || {};
    await page.snap("voted");
    await page.close();

    // A period that has ended shows the community's choice.
    const closed = await open(client, fx.closed, {token: fx.voterToken});
    results.closed = (await closed.evaluate(STATE)) || {};
    await closed.snap("closed");
    await closed.close();

    // The server refuses: the like was taken back in another tab while the form was open.
    const refused = await open(client, fx.refused, {token: fx.voterToken});
    results.refused = (await refused.evaluate(`(async () => {
        const v = window.__v;
        await v.choose("Study");
        await fetch("/liked-routines/${fx.refused}", {method: "DELETE", headers: {Authorization: "Bearer " + v.token}});
        document.getElementById("categoryVoteConfirm").click();
        await v.wait(1500);
        return v.state();
    })()`)) || {};
    await refused.snap("refused");
    await refused.close();

    results.layouts = {};
    for (const width of [360, 390, 1280, 1366, 1920]) {
        for (const theme of ["original", "autumn"]) {
            const view = await open(client, fx.layout, {token: fx.voterToken, width, theme});
            await view.evaluate(`(async () => {
                document.getElementById("categoryVote").scrollIntoView();
                await window.__v.choose("Wellness");
                return true;
            })()`);
            results.layouts[`${theme}${width}`] = await view.evaluate(LAYOUT);
            await view.snap(`${theme}-${width}`);
            await view.evaluate(`document.getElementById("categoryVoteCancel").click(); document.getElementById("categoryVote").scrollIntoView(); true`);
            await pause(300);
            await view.snap(`${theme}-${width}-panel`);
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
