// Opens routine pages as different users in headless Chrome and uses the owner buttons for real.
// Started by RoutineOwnerControlsRegressionTest; talks only to 127.0.0.1.
//
// Usage: node owner-controls-check.mjs <chrome> <fixtures.json> <results.json>
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

const BUTTONS = `(() => {
    const visible = (id) => {
        const element = document.getElementById(id);
        return !!element && !element.hidden && element.offsetParent !== null;
    };
    return {
        path: location.pathname,
        title: document.getElementById("routineTitle")?.textContent || "",
        edit: visible("editRoutineBtn"),
        editHabits: visible("editHabitsBtn"),
        start: visible("startBtn"),
        unavailable: !!document.querySelector(".routine-unavailable")
    };
})()`;

// A user can only press what they can see, so hidden buttons count as not working.
const PRESS = `const press = (id) => {
    const button = document.getElementById(id);
    if (button.hidden || button.offsetParent === null) {
        return false;
    }
    button.click();
    return true;
};`;

const USE_EDIT = `(async () => {
    const wait = (ms) => new Promise((done) => setTimeout(done, ms));
    ${PRESS}
    if (!press("editRoutineBtn")) {
        return {opened: false};
    }
    await wait(250);
    const opened = document.getElementById("editRoutineModal").classList.contains("show");
    document.getElementById("editRoutineTitle").value = ${JSON.stringify(fx.renamedTitle)};
    document.getElementById("saveEditRoutine").click();
    await wait(900);
    return {opened, title: document.getElementById("routineTitle").textContent};
})()`;

const USE_EDIT_HABITS = `(async () => {
    const wait = (ms) => new Promise((done) => setTimeout(done, ms));
    ${PRESS}
    if (!press("editHabitsBtn")) {
        return {opened: false};
    }
    await wait(600);
    return {
        opened: document.getElementById("editHabitsModal").classList.contains("show"),
        rows: document.querySelectorAll("#routineHabitsEditor .routine-habit-edit-item").length
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

async function open(client, token, url) {
    const {targetId} = await client.send("Target.createTarget", {url: "about:blank"});
    const {sessionId} = await client.send("Target.attachToTarget", {targetId, flatten: true});

    await client.send("Page.enable", {}, sessionId);
    await client.send("Runtime.enable", {}, sessionId);
    await client.send("Page.addScriptToEvaluateOnNewDocument", {
        source: `localStorage.setItem("token", ${JSON.stringify(token)});`
    }, sessionId);
    await client.send("Page.navigate", {url: fx.base + url}, sessionId);
    await pause(1500);

    return {
        evaluate: async (expression) => (await client.send("Runtime.evaluate", {
            expression, returnByValue: true, awaitPromise: true
        }, sessionId)).result.value,
        close: () => client.send("Target.closeTarget", {targetId})
    };
}

const profile = mkdtempSync(join(tmpdir(), "cml-owner-"));
const {chrome, endpoint} = await startChrome(profile);

try {
    const client = await connect(endpoint);
    const results = {};

    for (const [name, token, routine] of [
        ["ownerNoUsernamePublic", fx.ownerToken, fx.publicRoutine],
        ["ownerWithUsernamePublic", fx.namedOwnerToken, fx.namedOwnerRoutine],
        ["strangerPublic", fx.strangerToken, fx.publicRoutine],
        ["sameUsernameStranger", fx.sameUsernameToken, fx.namedOwnerRoutine],
        ["strangerPrivate", fx.strangerToken, fx.privateRoutine]
    ]) {
        const page = await open(client, token, `/routine.html?id=${routine}`);
        results[name] = await page.evaluate(BUTTONS);
        await page.close();
    }

    // Everything the owner without a username must really be able to do, on a private routine.
    const owner = await open(client, fx.ownerToken, `/routine.html?id=${fx.privateRoutine}`);
    results.ownerNoUsernamePrivate = await owner.evaluate(BUTTONS);
    results.editResult = await owner.evaluate(USE_EDIT);
    results.editHabitsResult = await owner.evaluate(USE_EDIT_HABITS);
    await owner.close();

    const starter = await open(client, fx.ownerToken, `/routine.html?id=${fx.privateRoutine}`);
    await starter.evaluate(`(() => { ${PRESS} press("startBtn"); })()`);
    await pause(1800);
    results.startResult = await starter.evaluate(`({
        path: location.pathname,
        running: /in progress/i.test(document.querySelector("main")?.innerText || "")
    })`);
    await starter.close();

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
