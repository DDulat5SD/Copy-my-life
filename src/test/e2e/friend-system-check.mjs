// US-29: in headless Chrome a user sends a friend request from a profile with a double click, the
// receiver accepts it under Friend requests, both see they are friends, one removes the friend, a
// request is declined, a refusal is explained and a visitor is asked to sign in; then the profile
// actions and Friend requests are measured in both designs from a 360 px phone to a 1920 px desktop.
// Started by FriendSystemRegressionTest; talks only to 127.0.0.1.
//
// Usage: node friend-system-check.mjs <chrome> <fixtures.json> <results.json>
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
// Optional: set FRIEND_SCREENSHOTS to a folder to keep a picture of each state.
const screenshots = process.env.FRIEND_SCREENSHOTS;

const HELPERS = `
    window.__f = {
        token: localStorage.getItem("token"),
        wait: (ms) => new Promise((done) => setTimeout(done, ms)),
        text: (el) => (el?.textContent || "").trim().replace(/\\s+/g, " "),
        visible: (el) => !!el && !el.hidden && el.offsetParent !== null,
        until: async (selector, ms = 6000) => {
            for (let waited = 0; waited < ms; waited += 100) {
                const found = document.querySelector(selector);
                if (found && found.offsetParent !== null) return found;
                await window.__f.wait(100);
            }
            return null;
        },
        count: async (path, token) => {
            const response = await fetch(path, {headers: {Authorization: "Bearer " + (token || window.__f.token)}});
            const body = await response.json();
            return Array.isArray(body) ? body.length : -1;
        },
        tab: async (name) => {
            document.querySelector('[data-friends-tab="' + name + '"]').click();
            await window.__f.wait(1500);
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
    const roots = [document.getElementById("profileActions"), document.getElementById("friendActionError"),
        document.getElementById("friendsList")].filter(visible);
    const parts = roots.flatMap((root) => [root, ...root.querySelectorAll("*")]).filter(visible);
    const buttons = parts.filter((el) => el.tagName === "BUTTON" || el.tagName === "A");
    // An avatar's initial is decoration; the name is written next to it.
    const texts = parts.filter((el) => !el.closest(".friend-card-avatar"))
        .filter((el) => [...el.childNodes].some((n) => n.nodeType === 3 && n.textContent.trim()));
    const faintest = texts.length ? texts.reduce((a, b) => contrast(a) <= contrast(b) ? a : b) : null;
    return {
        width: innerWidth,
        overflow: document.documentElement.scrollWidth - innerWidth,
        buttons: buttons.length,
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
    await pause(2500);
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

const profileOf = (user) => `/user-profile.html?id=${user.id}`;
const actions = `window.__f.text(document.getElementById("profileActions"))`;

const profile = mkdtempSync(join(tmpdir(), "cml-friend-"));
const {chrome, endpoint} = await startChrome(profile);

try {
    const client = await connect(endpoint);
    const results = {};

    // AC1: Add Friend, clicked twice.
    const sending = await open(client, profileOf(fx.receiver), {token: fx.sender.token});
    results.send = (await sending.evaluate(`(async () => {
        const f = window.__f;
        const button = await f.until("#addFriendBtn");
        button.click();
        const whileSending = f.text(button);
        button.click();
        await f.wait(1800);
        return {
            whileSending,
            statusText: f.text(document.getElementById("profileActions")),
            cancelVisible: f.visible(document.getElementById("cancelRequestBtn")),
            requests: await f.count("/friends/requests/sent")
        };
    })()`)) || {};
    await sending.snap("sent");
    await sending.close();

    // AC2: the receiver accepts under Friend requests.
    const receiving = await open(client, "/profile.html#friends", {token: fx.receiver.token});
    results.accept = (await receiving.evaluate(`(async () => {
        const f = window.__f;
        await f.tab("requests");
        const list = document.getElementById("friendsList");
        const requestText = f.text(list);
        const accept = await f.until("#friendsList .friend-accept-btn");
        if (accept) {
            accept.click();
            await f.wait(1500);
        }
        await f.tab("friends");
        return {
            requestListed: !!accept,
            requestText,
            countAfter: f.text(document.getElementById("friendsCountText")),
            receiverSeesFriends: f.text(document.getElementById("friendsList")).includes("Plain"),
            xss: window.__xss === 1
        };
    })()`)) || {};
    await receiving.snap("accepted");
    await receiving.close();

    // Both see they are friends; then the sender removes the friend.
    const friends = await open(client, profileOf(fx.receiver), {token: fx.sender.token});
    results.accept = {...results.accept, ...((await friends.evaluate(`(async () => {
        const f = window.__f;
        await f.until("#removeFriendBtn");
        return {senderSeesFriends: f.text(document.getElementById("profileActions")).includes("Friends")
            && f.visible(document.getElementById("removeFriendBtn"))};
    })()`)) || {})};
    results.remove = (await friends.evaluate(`(async () => {
        const f = window.__f;
        const remove = await f.until("#removeFriendBtn");
        if (remove) {
            remove.click();
            remove.click();
            await f.wait(1800);
        }
        return {
            addFriendBack: !!(await f.until("#addFriendBtn")),
            friendsOfRemover: await f.count("/friends"),
            friendsOfOther: await f.count("/friends", ${JSON.stringify(fx.receiver.token)})
        };
    })()`)) || {};
    await friends.close();

    // AC3: a request declined from the asker's profile.
    const declining = await open(client, profileOf(fx.asker), {token: fx.decliner.token});
    results.decline = (await declining.evaluate(`(async () => {
        const f = window.__f;
        const decline = await f.until("#rejectRequestBtn");
        const declineLabel = f.text(decline);
        if (decline) {
            decline.click();
            await f.wait(1800);
        }
        return {
            declineLabel,
            addFriendBack: !!(await f.until("#addFriendBtn")),
            pendingAfter: await f.count("/friends/requests")
        };
    })()`)) || {};
    await declining.snap("declined");
    await declining.close();

    // A refusal: the same request was sent from another tab first.
    const refused = await open(client, profileOf(fx.receiver), {token: fx.busy.token});
    results.refused = (await refused.evaluate(`(async () => {
        const f = window.__f;
        const button = await f.until("#addFriendBtn");
        await fetch("/friends/request/${fx.receiver.id}", {method: "POST", headers: {Authorization: "Bearer " + f.token}});
        button.click();
        await f.wait(1800);
        const error = document.getElementById("friendActionError");
        return {
            error: f.visible(error) ? f.text(error) : "",
            statusText: f.text(document.getElementById("profileActions"))
        };
    })()`)) || {};
    await refused.snap("refused");
    await refused.close();

    // A visitor is asked to sign in.
    const guest = await open(client, profileOf(fx.receiver));
    results.guest = (await guest.evaluate(`({
        addFriendVisible: window.__f.visible(document.getElementById("addFriendBtn")),
        signIn: ${actions}
    })`)) || {};
    await guest.close();

    results.layouts = {};
    for (const width of [360, 390, 1280, 1366, 1920]) {
        for (const theme of ["original", "autumn"]) {
            const card = await open(client, profileOf(fx.receiver), {token: fx.layout.token, width, theme});
            await card.evaluate(`window.__f.until("#addFriendBtn")`);
            results.layouts[`profile-${theme}${width}`] = await card.evaluate(LAYOUT);
            await card.snap(`profile-${theme}-${width}`);
            await card.close();

            const requests = await open(client, "/profile.html#friends", {token: fx.layout.token, width, theme});
            await requests.evaluate(`(async () => {
                await window.__f.tab("requests");
                document.getElementById("friendsList").scrollIntoView();
                return true;
            })()`);
            results.layouts[`requests-${theme}${width}`] = await requests.evaluate(LAYOUT);
            await requests.snap(`requests-${theme}-${width}`);
            await requests.close();
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
