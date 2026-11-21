// Signs in, edits the profile, checks privacy badges and signs out in headless Chrome.
// Started by AuthProfilePrivacyRegressionTest; talks only to 127.0.0.1.
//
// Usage: node auth-profile-check.mjs <chrome> <fixtures.json> <results.json>
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
// Optional: set AUTH_SCREENSHOTS to a folder to keep a picture of each state for a visual check.
const screenshots = process.env.AUTH_SCREENSHOTS;

const run = (body) => `(async () => {
    ${body}
    await new Promise((done) => setTimeout(done, 1000));
    return true;
})()`;

const SESSION = `(() => ({
    url: location.href,
    hasToken: !!localStorage.getItem("token"),
    hasUser: !!localStorage.getItem("user"),
    notice: (document.getElementById("loginNotice")?.hidden === false
        ? document.getElementById("loginNotice").textContent : "").trim(),
    error: (document.getElementById("loginError")?.hidden === false
        ? document.getElementById("loginError").textContent : "").trim()
}))()`;

const PROFILE = `(() => {
    const text = (id) => (document.getElementById(id)?.textContent || "").trim();
    const error = document.getElementById("editProfileError");
    return {
        name: text("profileName"),
        username: text("profileUsername"),
        interests: text("profileInterests"),
        goals: text("profileGoals"),
        usernameField: document.getElementById("editUsername")?.value ?? null,
        modalOpen: document.getElementById("editProfileModal").classList.contains("show"),
        error: error && !error.hidden ? error.textContent.trim() : ""
    };
})()`;

const fill = (values) => run(`
    for (const [id, value] of Object.entries(${JSON.stringify(values)})) {
        const input = document.getElementById(id);
        if (!input) {
            continue;
        }
        input.value = value;
        input.dispatchEvent(new Event("input", {bubbles: true}));
    }
`);

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
        return {r: 255, g: 255, b: 255, a: 1};
    };
    const contrast = (el) => {
        const a = luminance(parse(getComputedStyle(el).color));
        const b = luminance(background(el));
        return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
    };
    const visible = (el) => !!el && !el.hidden && el.offsetParent !== null;
    const scope = document.querySelector("#editProfileModal.show") ? "#editProfileModal.show " : "";
    const texts = [...document.querySelectorAll(scope
        ? scope + "h2, " + scope + "label, " + scope + "button, " + scope + ".edit-profile-hint, #editProfileError"
        : "#loginNotice, #loginError, .profile-extra-label, .profile-extra-value, .routine-privacy-badge")]
        .filter(visible);
    const controls = [...document.querySelectorAll(scope
        ? scope + "input, " + scope + "textarea, " + scope + "button"
        : "form input, form button, .routine-privacy-badge")]
        .filter(visible);
    const all = [...texts, ...controls];
    return {
        width: innerWidth,
        theme: document.documentElement.getAttribute("data-theme") || "",
        rendered: controls.length > 0,
        overflow: document.documentElement.scrollWidth - innerWidth,
        rightmost: Math.round(Math.max(0, ...all.map((el) => el.getBoundingClientRect().right))),
        minContrast: texts.length ? Math.round(Math.min(...texts.map(contrast)) * 100) / 100 : 21
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
            // A blocking alert() would freeze the page; record it and dismiss it.
            if (message.method === "Page.javascriptDialogOpening") {
                dialogs.push(message.params.message);
                socket.send(JSON.stringify({
                    id: nextId++, method: "Page.handleJavaScriptDialog",
                    params: {accept: true}, sessionId: message.sessionId
                }));
                return;
            }
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

async function tab(client, {width = 1280, theme = "original", token = null}) {
    const {targetId} = await client.send("Target.createTarget", {url: "about:blank"});
    const {sessionId} = await client.send("Target.attachToTarget", {targetId, flatten: true});

    await client.send("Page.enable", {}, sessionId);
    await client.send("Runtime.enable", {}, sessionId);
    await client.send("Emulation.setDeviceMetricsOverride", {
        width, height: width < 600 ? 844 : 900, deviceScaleFactor: width < 600 ? 2 : 1, mobile: width < 600
    }, sessionId);
    await client.send("Page.addScriptToEvaluateOnNewDocument", {
        // The token is placed once per tab, so a page that signs the user out is not undone.
        source: `localStorage.setItem("copymylife-theme", ${JSON.stringify(theme)});`
            + (token ? `if (!sessionStorage.getItem("seeded")) {
                   localStorage.setItem("token", ${JSON.stringify(token)});
                   sessionStorage.setItem("seeded", "1");
               }` : "")
    }, sessionId);

    return {
        // A step that breaks on the page is recorded instead of stopping the whole check.
        evaluate: async (expression) => {
            try {
                const reply = await client.send("Runtime.evaluate", {
                    expression, returnByValue: true, awaitPromise: true
                }, sessionId);
                return reply.exceptionDetails ? {failed: reply.exceptionDetails.text} : reply.result.value;
            } catch (error) {
                return {failed: error.message};
            }
        },
        go: async (path) => {
            await client.send("Page.navigate", {url: fx.base + path}, sessionId);
            await pause(2000);
        },
        snap: async (name) => {
            if (!screenshots) {
                return;
            }
            const {data} = await client.send("Page.captureScreenshot", {format: "png"}, sessionId);
            writeFileSync(join(screenshots, `${name}.png`), Buffer.from(data, "base64"));
        },
        close: () => client.send("Target.closeTarget", {targetId})
    };
}

const signIn = (password) => run(`
    document.getElementById("email").value = ${JSON.stringify(fx.email)};
    document.getElementById("password").value = ${JSON.stringify(password)};
    document.querySelector("form button[type=submit]").click();
    await new Promise((done) => setTimeout(done, 1500));
`);

const profile = mkdtempSync(join(tmpdir(), "cml-auth-"));
const {chrome, endpoint} = await startChrome(profile);

try {
    const client = await connect(endpoint);
    const results = {};

    const page = await tab(client, {});
    await page.go("/login.html");
    await page.evaluate(signIn("Wrong#12345"));
    results.wrongPassword = await page.evaluate(SESSION);
    await page.snap("login-error");
    await page.evaluate(signIn(fx.password));
    await pause(1000);
    results.signedIn = await page.evaluate(SESSION);
    const token = await page.evaluate(`localStorage.getItem("token")`);

    await page.go("/profile.html");
    await page.evaluate(run(`document.getElementById("editProfileBtn").click();`));
    results.editOpened = await page.evaluate(PROFILE);
    await page.evaluate(fill({editName: "Cancelled Name"}));
    await page.evaluate(run(`document.getElementById("cancelEditBtn").click();`));
    results.afterCancel = await page.evaluate(PROFILE);

    await page.evaluate(run(`document.getElementById("editProfileBtn").click();`));
    await page.evaluate(fill({editName: "   "}));
    await page.evaluate(run(`document.getElementById("saveProfileBtn").click();`));
    results.blankName = await page.evaluate(PROFILE);
    await page.evaluate(fill({editName: "Ada Browser", editUsername: fx.rivalName}));
    await page.evaluate(run(`document.getElementById("saveProfileBtn").click();`));
    results.takenUsername = await page.evaluate(PROFILE);
    await page.snap("profile-error");
    await page.evaluate(fill({
        editName: "Ada Browser", editUsername: fx.newUsername,
        editInterests: "Robots, chess", editGoals: "Marathon in spring"
    }));
    await page.evaluate(run(`document.getElementById("saveProfileBtn").click();`));
    results.afterSave = await page.evaluate(PROFILE);
    await page.go("/profile.html");
    results.afterReload = await page.evaluate(PROFILE);
    await page.snap("profile-saved");

    await page.evaluate(run(`document.getElementById("myRoutinesNav")?.click();`));
    results.routineCards = await page.evaluate(`({badges: [...document.querySelectorAll(".routine-card .routine-privacy-badge")].map((el) => el.textContent.trim())})`);
    await page.go(`/routine.html?id=${fx.privateRoutine}`);
    results.privateRoutine = await page.evaluate(`({badge: (document.getElementById("routinePrivacyBadge")?.textContent || "").trim()})`);
    await page.snap("routine-private");
    await page.go(`/routine.html?id=${fx.publicRoutine}`);
    results.publicRoutine = await page.evaluate(`({badge: (document.getElementById("routinePrivacyBadge")?.textContent || "").trim()})`);

    await page.go("/profile.html");
    await page.evaluate(run(`document.getElementById("logoutBtn").click(); await new Promise((done) => setTimeout(done, 1500));`));
    await pause(2000);
    results.signedOut = await page.evaluate(SESSION);
    results.signedOut.oldTokenStatus = await page.evaluate(`fetch("/users/me", {headers: {Authorization: "Bearer " + ${JSON.stringify(token)}}}).then((r) => r.status)`);
    await page.snap("signed-out");
    await page.close();

    const expired = await tab(client, {token: "expired.invalid.token"});
    await expired.go("/profile.html");
    await pause(2500);
    results.expired = await expired.evaluate(SESSION);
    await expired.close();

    const fresh = await tab(client, {});
    await fresh.go("/login.html");
    await fresh.evaluate(signIn(fx.password));
    const layoutToken = await fresh.evaluate(`localStorage.getItem("token")`);
    await fresh.close();

    const layouts = {
        loginPhone: {width: 390, theme: "original", path: "/login.html"},
        profilePhone: {width: 390, theme: "original", path: "/profile.html", token: layoutToken, modal: true},
        profileAutumnPhone: {width: 390, theme: "autumn", path: "/profile.html", token: layoutToken, modal: true},
        profileAutumnDesktop: {width: 1280, theme: "autumn", path: "/profile.html", token: layoutToken, modal: true}
    };
    for (const [name, options] of Object.entries(layouts)) {
        const view = await tab(client, options);
        await view.go(options.path);
        if (options.modal) {
            await view.evaluate(run(`document.getElementById("editProfileBtn").click();`));
        }
        results[name] = await view.evaluate(LAYOUT);
        await view.snap(name);
        await view.close();
    }

    results.dialogs = dialogs;
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
