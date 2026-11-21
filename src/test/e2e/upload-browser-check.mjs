// Opens stored upload URLs in headless Chrome and reports whether any script in them ran.
// Started by UploadSecurityRegressionTest; talks only to 127.0.0.1.
//
// Usage: node upload-browser-check.mjs <chrome> <fixtures.json> <results.json>
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

async function open(client, url) {
    const {targetId} = await client.send("Target.createTarget", {url: "about:blank"});
    const {sessionId} = await client.send("Target.attachToTarget", {targetId, flatten: true});

    await client.send("Page.enable", {}, sessionId);
    await client.send("Runtime.enable", {}, sessionId);
    await client.send("Page.addScriptToEvaluateOnNewDocument", {source: `
        window.__marks = [];
        window.__mark = (id) => window.__marks.push(id);
    `}, sessionId);
    await client.send("Page.navigate", {url: fx.base + url}, sessionId);
    await pause(900);

    return {
        targetId,
        evaluate: async (expression) => (await client.send("Runtime.evaluate", {
            expression, returnByValue: true, awaitPromise: true
        }, sessionId)).result.value,
        close: () => client.send("Target.closeTarget", {targetId})
    };
}

const profile = mkdtempSync(join(tmpdir(), "cml-upload-"));
const {chrome, endpoint} = await startChrome(profile);

try {
    const client = await connect(endpoint);
    const pages = [];

    for (const url of fx.pages) {
        const page = await open(client, url);
        pages.push({url, marks: await page.evaluate("window.__marks || []")});
        await page.close();
    }

    // Files that are fine must still display inside a normal site page.
    const host = await open(client, "/login.html");
    const brokenImages = await host.evaluate(`(async () => {
        const urls = ${JSON.stringify(fx.goodImages)};
        const loaded = await Promise.all(urls.map((url) => new Promise((done) => {
            const image = new Image();
            image.onload = () => done(image.naturalWidth > 0 ? null : url);
            image.onerror = () => done(url);
            image.src = url;
        })));
        return loaded.filter(Boolean);
    })()`);
    await host.close();

    writeFileSync(resultsPath, JSON.stringify({pages, brokenImages}, null, 1));
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
