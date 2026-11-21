// US-16 (posts): in headless Chrome, a reader opens an author's public post from their profile and
// saves it; posts.html -> Saved posts lists it and removing it empties the list. The Saved posts
// view fits at 390 px (both designs) and 1280 px.
// Started by SavedPostsRegressionTest; talks only to 127.0.0.1.
//
// Usage: node saved-posts-check.mjs <chrome> <fixtures.json> <results.json>
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

const text = (selector) => `(document.querySelector(${JSON.stringify(selector)})?.textContent || "").trim()`;

const SAVED = `(() => ({
    savedTexts: [...document.querySelectorAll("#postsApp .post__text")].map((el) => el.textContent),
    formInSaved: !!document.querySelector("#postsApp [data-part=newPostForm]")?.offsetParent,
    xss: window.__xss === 1
}))()`;

const LAYOUT = `(() => {
    const root = document.getElementById("postsApp");
    const parts = [...root.querySelectorAll("*")].filter((el) => el.offsetParent !== null);
    const outside = parts.filter((el) => {
        const box = el.getBoundingClientRect();
        return box.width > 0 && (box.left < -1 || box.right > innerWidth + 1);
    });
    return {
        overflow: document.documentElement.scrollWidth - innerWidth,
        inside: outside.length === 0,
        outside: outside.slice(0, 3).map((el) => el.className || el.tagName).join(", "),
        tabs: [...root.querySelectorAll(".post-tab")].filter((el) => el.offsetParent !== null).length,
        posts: root.querySelectorAll(".post").length
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
                errors.push(message.params.exceptionDetails.exception?.description
                    || message.params.exceptionDetails.text);
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

async function open(client, {token, page, width = 1280, theme = "original"}) {
    const {targetId} = await client.send("Target.createTarget", {url: "about:blank"});
    const {sessionId} = await client.send("Target.attachToTarget", {targetId, flatten: true});

    await client.send("Page.enable", {}, sessionId);
    await client.send("Runtime.enable", {}, sessionId);
    await client.send("Emulation.setDeviceMetricsOverride", {
        width, height: width < 600 ? 844 : 900, deviceScaleFactor: width < 600 ? 2 : 1, mobile: width < 600
    }, sessionId);
    await client.send("Page.addScriptToEvaluateOnNewDocument", {
        source: `localStorage.setItem("token", ${JSON.stringify(token)});`
            + `localStorage.setItem("copymylife-theme", ${JSON.stringify(theme)});`
    }, sessionId);
    await client.send("Page.navigate", {url: fx.base + "/" + page}, sessionId);
    await pause(3000);

    const view = {
        evaluate: async (expression) => (await client.send("Runtime.evaluate", {
            expression, returnByValue: true, awaitPromise: true
        }, sessionId)).result.value,
        click: async (selector) => {
            await view.evaluate(`document.querySelector(${JSON.stringify(selector)}).click()`);
            await pause(1500);
        },
        close: () => client.send("Target.closeTarget", {targetId})
    };
    return view;
}

const profile = mkdtempSync(join(tmpdir(), "cml-saved-posts-"));
const {chrome, endpoint} = await startChrome(profile);

try {
    const client = await connect(endpoint);
    const results = {flow: {}, layouts: {}};

    const author = await open(client, {token: fx.readerToken, page: `user-profile.html?id=${fx.authorId}`});
    await author.click(`.public-content-view[data-view="post"][data-id="${fx.postId}"]`);
    results.flow.before = await author.evaluate(text('[data-social="save"]'));
    await author.click('[data-social="save"]');
    results.flow.after = await author.evaluate(text('[data-social="save"]'));
    await author.close();

    const mine = await open(client, {token: fx.readerToken, page: "posts.html"});
    await mine.click('#postsApp [data-view="saved"]');
    Object.assign(results.flow, await mine.evaluate(SAVED));
    await mine.click('#postsApp [data-social="save"]');
    results.flow.emptyAfterRemove = await mine.evaluate(
        `!document.querySelector("#postsApp [data-part=postsEmpty]").hidden && !document.querySelector("#postsApp .post")`);
    await mine.close();

    for (const [name, width, theme] of [["original390", 390, "original"], ["autumn390", 390, "autumn"], ["autumn1280", 1280, "autumn"]]) {
        const view = await open(client, {token: fx.layoutToken, page: "posts.html", width, theme});
        await view.click('#postsApp [data-view="saved"]');
        results.layouts[name] = await view.evaluate(LAYOUT);
        await view.close();
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
