// Uses the Copy Routine button in headless Chrome the way a user would.
// Started by RoutineCopyRegressionTest; talks only to 127.0.0.1.
//
// Usage: node routine-copy-check.mjs <chrome> <fixtures.json> <results.json>
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

// A user can only press what they can see.
const HELPERS = `
    const wait = (ms) => new Promise((done) => setTimeout(done, ms));
    const visible = (id) => {
        const element = document.getElementById(id);
        return !!element && !element.hidden && element.offsetParent !== null;
    };
    const press = (id) => {
        if (!visible(id)) {
            return false;
        }
        document.getElementById(id).click();
        return true;
    };
`;

const STATE = `(() => {
    ${HELPERS}
    return {
        title: document.getElementById("routineTitle")?.textContent || "",
        copyVisible: visible("copyRoutineBtn"),
        edit: visible("editRoutineBtn"),
        editHabits: visible("editHabitsBtn"),
        start: visible("startBtn"),
        habits: document.querySelectorAll("#habitsList .habit").length,
        unavailable: !!document.querySelector(".routine-unavailable")
    };
})()`;

const NOTICE = `(() => {
    const notice = document.getElementById("copyNotice");
    const button = document.getElementById("copyRoutineBtn");
    return {
        shown: !!notice && !notice.hidden,
        message: notice?.textContent || "",
        isError: !!notice?.classList.contains("routine-copy-notice--error"),
        link: notice?.querySelector("a")?.getAttribute("href") || "",
        buttonEnabled: !!button && !button.disabled
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

const profile = mkdtempSync(join(tmpdir(), "cml-copy-"));
const {chrome, endpoint} = await startChrome(profile);

try {
    const client = await connect(endpoint);
    const results = {};

    for (const [name, token, routine] of [
        ["ownerPublic", fx.ownerToken, fx.publicRoutine],
        ["ownerPrivate", fx.ownerToken, fx.privateRoutine],
        ["otherUserPublic", fx.copierToken, fx.publicRoutine],
        ["otherUserPrivate", fx.copierToken, fx.privateRoutine],
        ["halloween", fx.copierToken, fx.halloweenRoutine]
    ]) {
        const page = await open(client, token, `/routine.html?id=${routine}`);
        results[name] = await page.evaluate(STATE);
        await page.close();
    }

    // Copy, read the success notice, confirm the other buttons are still there.
    const source = await open(client, fx.copierToken, `/routine.html?id=${fx.publicRoutine}`);
    await source.evaluate(`(async () => { ${HELPERS} press("copyRoutineBtn"); await wait(1000); })()`);
    results.copyResult = await source.evaluate(NOTICE);
    results.afterCopy = await source.evaluate(`({
        otherButtonsStillThere: ["saveBtn", "likeBtn"].every((id) => document.getElementById(id)?.offsetParent !== null)
            && !!document.querySelector(".rating-star-btn")
            && !!document.getElementById("commentInput")
    })`);
    await source.close();

    const copyPath = results.copyResult.link.startsWith("/") ? results.copyResult.link : `/${results.copyResult.link}`;

    if (results.copyResult.link) {
        const copy = await open(client, fx.copierToken, copyPath);
        results.openedCopy = await copy.evaluate(STATE);
        results.editModal = await copy.evaluate(`(async () => {
            ${HELPERS}
            if (!press("editRoutineBtn")) { return {opened: false}; }
            await wait(250);
            const opened = document.getElementById("editRoutineModal").classList.contains("show");
            document.getElementById("cancelEditRoutine")?.click();
            document.getElementById("editRoutineModal").classList.remove("show");
            return {opened};
        })()`);
        results.habitsModal = await copy.evaluate(`(async () => {
            ${HELPERS}
            if (!press("editHabitsBtn")) { return {opened: false}; }
            await wait(600);
            const opened = document.getElementById("editHabitsModal").classList.contains("show");
            document.getElementById("editHabitsModal").classList.remove("show");
            return {opened};
        })()`);
        await copy.evaluate(`(() => { ${HELPERS} press("startBtn"); })()`);
        await pause(1800);
        results.startResult = await copy.evaluate(`({path: location.pathname})`);
        await copy.close();

        const profilePage = await open(client, fx.copierToken, "/profile.html");
        results.myRoutines = await profilePage.evaluate(`(async () => {
            ${HELPERS}
            document.getElementById("myRoutinesNav").click();
            await wait(500);
            return {listed: document.getElementById("accountContent").innerText.includes("Browser source")};
        })()`);
        await profilePage.close();
    }

    // The routine goes private after the page has loaded, so the copy must fail clearly.
    const stale = await open(client, fx.copierToken, `/routine.html?id=${fx.vanishingRoutine}`);
    const boundary = "OwnerUpdate";
    const form = ["title", "Goes private later", "description", "d", "category", "Morning", "durationMinutes", "5", "isPublic", "false"];
    let body = "";
    for (let i = 0; i < form.length; i += 2) {
        body += `--${boundary}\r\nContent-Disposition: form-data; name="${form[i]}"\r\n\r\n${form[i + 1]}\r\n`;
    }
    body += `--${boundary}--\r\n`;
    const update = await fetch(`${fx.base}/routines/${fx.vanishingRoutine}`, {
        method: "PUT",
        headers: {
            "Authorization": `Bearer ${fx.ownerToken}`,
            "Content-Type": `multipart/form-data; boundary=${boundary}`
        },
        body
    });
    if (!update.ok) {
        throw new Error(`Could not make the routine private: ${update.status}`);
    }
    await stale.evaluate(`(async () => { ${HELPERS} press("copyRoutineBtn"); await wait(900); })()`);
    results.failedCopy = await stale.evaluate(NOTICE);
    await stale.close();

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
