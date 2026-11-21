// Uses the Follow button in headless Chrome the way a user would.
// Started by UserFollowRegressionTest; talks only to 127.0.0.1.
//
// Usage: node follow-check.mjs <chrome> <fixtures.json> <results.json>
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

const STATE = `(() => {
    const button = document.getElementById("followBtn");
    const number = (id) => Number(document.getElementById(id)?.textContent);
    return {
        label: button?.textContent.trim() || "",
        buttonVisible: !!button && !button.hidden && button.offsetParent !== null,
        buttonEnabled: !!button && !button.disabled,
        followers: number("followersCount"),
        following: number("followingCount"),
        followersLabel: document.getElementById("followersLabel")?.textContent || "",
        hasCounts: !!document.getElementById("followersCount") && !!document.getElementById("followingCount"),
        error: document.getElementById("followError")?.hidden === false
            ? document.getElementById("followError").textContent
            : "",
        friendButton: !!document.getElementById("addFriendBtn")
    };
})()`;

// A user can only press what they can see.
const PRESS = `(async () => {
    const button = document.getElementById("followBtn");
    if (button && !button.hidden && button.offsetParent !== null) {
        button.click();
    }
    await new Promise((done) => setTimeout(done, 700));
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

async function open(client, url, width) {
    const {targetId} = await client.send("Target.createTarget", {url: "about:blank"});
    const {sessionId} = await client.send("Target.attachToTarget", {targetId, flatten: true});

    await client.send("Page.enable", {}, sessionId);
    await client.send("Runtime.enable", {}, sessionId);
    if (width) {
        await client.send("Emulation.setDeviceMetricsOverride", {
            width, height: 844, deviceScaleFactor: 2, mobile: true
        }, sessionId);
    }
    await client.send("Page.addScriptToEvaluateOnNewDocument", {
        source: `localStorage.setItem("token", ${JSON.stringify(fx.viewerToken)});`
    }, sessionId);
    await client.send("Page.navigate", {url: fx.base + url}, sessionId);
    await pause(1500);

    return {
        evaluate: async (expression) => (await client.send("Runtime.evaluate", {
            expression, returnByValue: true, awaitPromise: true
        }, sessionId)).result.value,
        reload: async () => {
            await client.send("Page.reload", {}, sessionId);
            await pause(1500);
        },
        close: () => client.send("Target.closeTarget", {targetId})
    };
}

const profile = mkdtempSync(join(tmpdir(), "cml-follow-"));
const {chrome, endpoint} = await startChrome(profile);

try {
    const client = await connect(endpoint);
    const results = {};

    const creator = await open(client, `/user-profile.html?id=${fx.creatorId}`);
    results.initial = await creator.evaluate(STATE);

    // The request fails: the page must say so and keep showing the real state.
    await creator.evaluate(`window.__realFetch = window.fetch;
        window.fetch = () => Promise.resolve(new Response("{}", {status: 500}));`);
    await creator.evaluate(PRESS);
    results.failedFollow = await creator.evaluate(STATE);
    await creator.evaluate(`window.fetch = window.__realFetch;`);

    await creator.evaluate(PRESS);
    results.afterFollow = await creator.evaluate(STATE);
    await creator.reload();
    results.afterReload = await creator.evaluate(STATE);
    await creator.evaluate(PRESS);
    results.afterUnfollow = await creator.evaluate(STATE);
    await creator.close();

    const own = await open(client, `/user-profile.html?id=${fx.viewerId}`);
    results.ownProfile = await own.evaluate(STATE);
    await own.close();

    const account = await open(client, "/profile.html");
    results.myAccount = await account.evaluate(STATE);
    await account.close();

    const phone = await open(client, `/user-profile.html?id=${fx.creatorId}`, 390);
    results.mobile = await phone.evaluate(`(() => {
        const button = document.getElementById("followBtn");
        const box = button?.getBoundingClientRect();
        return {
            width: innerWidth,
            overflow: document.documentElement.scrollWidth - innerWidth,
            buttonVisible: !!button && !button.hidden && button.offsetParent !== null,
            buttonRight: box ? Math.round(box.right) : 9999
        };
    })()`);
    await phone.close();

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
