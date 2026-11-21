// US-24: drives every Report button in headless Chrome. A member opens the Report dialog from a
// profile, a routine, a routine comment and a reply, a post and a post comment, sends each report,
// and sees the confirmation, the "already reported" answer, the loading state and the refusals;
// the dialog is used with the keyboard; the administrator then reviews the reports. Finally the
// dialog and the admin page are measured in both designs from a 360 px phone to a 1920 px desktop.
// Started by ReportContentRegressionTest; talks only to 127.0.0.1.
//
// Usage: node report-content-check.mjs <chrome> <fixtures.json> <results.json>
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
// Optional: set REPORT_CONTENT_SCREENSHOTS to a folder to keep a picture of each page.
const screenshots = process.env.REPORT_CONTENT_SCREENSHOTS;

const HELPERS = `
    window.__t = {
        wait: (ms) => new Promise((done) => setTimeout(done, ms)),
        text: (el) => (el?.textContent || "").trim().replace(/\\s+/g, " "),
        visible: (el) => !!el && !el.hidden && el.offsetParent !== null,
        until: async (test, ms = 8000) => {
            for (let waited = 0; waited < ms; waited += 100) {
                const found = typeof test === "string" ? document.querySelector(test) : test();
                if (found) return found;
                await window.__t.wait(100);
            }
            return null;
        },
        // Every report request the page sends, and an optional delay or canned answer for them.
        sent: [],
        delay: 0,
        mine: null,
        dialog: () => document.querySelector(".cml-report-backdrop"),
        open: () => { const d = window.__t.dialog(); return !!d && !d.hidden; },
        // Waits until the dialog has finished looking for an earlier report.
        ready: async () => window.__t.until(() => {
            const d = window.__t.dialog();
            if (!d || d.hidden) return null;
            const form = d.querySelector(".cml-report__form");
            const result = d.querySelector(".cml-report__result");
            return (!form.hidden || !result.hidden) ? d : null;
        }),
        describe: (expectedTitle) => {
            const d = window.__t.dialog();
            return {
                visible: window.__t.open(),
                title: window.__t.text(d.querySelector(".cml-report__title")),
                expectedTitle,
                reasons: d.querySelectorAll("input[name=reason]").length,
                focusInside: d.querySelector(".cml-report").contains(document.activeElement)
            };
        },
        // Picks a reason, writes an explanation and sends; answers what the dialog then says.
        submit: async (reason, details) => {
            const d = window.__t.dialog();
            d.querySelector("input[name=reason][value=" + reason + "]").click();
            const area = d.querySelector("textarea");
            area.value = details || "";
            area.dispatchEvent(new Event("input", {bubbles: true}));
            d.querySelector(".cml-report__btn--primary[type=submit]").click();
            const shown = await window.__t.until(() => {
                const result = d.querySelector(".cml-report__result");
                const error = d.querySelector(".cml-report__form .cml-report__error:not(#cmlReportReasonError)");
                if (!result.hidden) return window.__t.text(d.querySelector(".cml-report__result-title"));
                if (!error.hidden) return "error: " + window.__t.text(error);
                return null;
            });
            return shown;
        },
        close: async () => {
            const d = window.__t.dialog();
            const done = d.querySelector(".cml-report__result:not([hidden]) button");
            (done || d.querySelector(".cml-report__close")).click();
            await window.__t.wait(200);
        },
        lastId: async (type, field, id, token) => {
            const mine = await (await fetch("/reports/mine", {headers: {Authorization: "Bearer " + token}})).json();
            const found = mine.find((r) => r.type === type && Number(r[field]) === Number(id));
            return found ? found.id : 0;
        }
    };
    const __fetch = window.fetch.bind(window);
    window.fetch = async (url, options = {}) => {
        const path = String(url);
        if (path.startsWith("/reports/") && (options.method || "GET") === "POST") {
            window.__t.sent.push(path);
            if (window.__t.delay) await window.__t.wait(window.__t.delay);
        }
        if (path === "/reports/mine" && window.__t.mine !== null) {
            return new Response(window.__t.mine, {status: 200, headers: {"Content-Type": "application/json"}});
        }
        return __fetch(url, options);
    };
`;

// Sideways scrolling, whether the dialog fits the screen, and its faintest text.
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
    const blend = (el) => {
        const color = parse(getComputedStyle(el).color);
        const opacity = [...Array(4)].reduce((acc, _, i) => {
            let node = el;
            for (let k = 0; k < i && node; k++) node = node.parentElement;
            return node ? acc * Number(getComputedStyle(node).opacity) : acc;
        }, 1);
        const back = background(el);
        return {r: color.r * opacity + back.r * (1 - opacity), g: color.g * opacity + back.g * (1 - opacity), b: color.b * opacity + back.b * (1 - opacity)};
    };
    const contrast = (el) => {
        const a = luminance(blend(el));
        const b = luminance(background(el));
        return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
    };
    const visible = (el) => !!el && !el.hidden && el.offsetParent !== null;
    const dialog = document.querySelector(".cml-report-backdrop:not([hidden]) .cml-report");
    let dialogInside = true;
    let minContrast = 10;
    let faintest = "";
    if (dialog) {
        const box = dialog.getBoundingClientRect();
        dialogInside = box.left >= 0 && box.right <= innerWidth + 1 && box.top >= 0 && box.width > 0;
        const texts = [...dialog.querySelectorAll("h2, p, label, legend, strong, span, button")]
            .filter(visible).filter((el) => [...el.childNodes].some((n) => n.nodeType === 3 && n.textContent.trim()));
        texts.forEach((el) => {
            const value = contrast(el);
            if (value < minContrast) {
                minContrast = Math.round(value * 100) / 100;
                faintest = (el.className || el.tagName) + " " + el.textContent.trim().slice(0, 20);
            }
        });
    }
    // The routine card's buttons, Report among them, stay inside the card.
    const hero = document.querySelector(".hero");
    const heroBox = hero ? hero.getBoundingClientRect() : null;
    const heroInside = !hero || [...hero.querySelectorAll(".hero__actions > *")].filter(visible)
        .every((el) => { const box = el.getBoundingClientRect(); return box.left >= heroBox.left - 1 && box.right <= heroBox.right + 1; });
    return {
        width: innerWidth,
        overflow: document.documentElement.scrollWidth - innerWidth,
        dialogInside,
        heroInside,
        minContrast,
        faintest
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

async function open(client, path, {admin = false, token = null, width = 1280, theme = "original"} = {}) {
    const {targetId} = await client.send("Target.createTarget", {url: "about:blank"});
    const {sessionId} = await client.send("Target.attachToTarget", {targetId, flatten: true});
    await client.send("Page.enable", {}, sessionId);
    await client.send("Runtime.enable", {}, sessionId);
    await client.send("Emulation.setDeviceMetricsOverride", {
        width, height: width < 600 ? 844 : 900, deviceScaleFactor: width < 600 ? 2 : 1, mobile: width < 600
    }, sessionId);
    await client.send("Page.addScriptToEvaluateOnNewDocument", {
        source: (admin ? `sessionStorage.setItem("adminToken", ${JSON.stringify(fx.adminToken)});` : "")
            + (token ? `localStorage.setItem("token", ${JSON.stringify(token)});` : `localStorage.removeItem("token");`)
            + `localStorage.setItem("copymylife-theme", ${JSON.stringify(theme)});`
            + HELPERS
    }, sessionId);
    // Key presses only reach a page that has the focus.
    await client.send("Emulation.setFocusEmulationEnabled", {enabled: true}, sessionId);
    await client.send("Page.bringToFront", {}, sessionId);
    await client.send("Page.navigate", {url: fx.base + path}, sessionId);
    await pause(2500);
    const key = async (keyName, code, keyCode, shift = false) => {
        for (const type of ["keyDown", "keyUp"]) {
            // Enter needs its text on keyDown, or a focused button is not pressed.
            await client.send("Input.dispatchKeyEvent", {
                type, key: keyName, code, windowsVirtualKeyCode: keyCode, modifiers: shift ? 8 : 0,
                ...(type === "keyDown" && keyName === "Enter" ? {text: "\r"} : {})
            }, sessionId);
        }
    };
    return {
        evaluate: async (expression) => {
            const answer = await client.send("Runtime.evaluate", {expression, returnByValue: true, awaitPromise: true}, sessionId);
            if (answer.exceptionDetails) {
                throw new Error(path + ": " + (answer.exceptionDetails.exception?.description || answer.exceptionDetails.text));
            }
            return answer.result.value;
        },
        key,
        snap: async (name) => {
            if (!screenshots) return;
            const {data} = await client.send("Page.captureScreenshot", {format: "png"}, sessionId);
            writeFileSync(join(screenshots, `${name}.png`), Buffer.from(data, "base64"));
        },
        close: () => client.send("Target.closeTarget", {targetId})
    };
}

const results = {opened: {}, sent: [], form: {}, keyboard: {}, menus: {}, admin: {types: {}}, layouts: {}};

async function remember(page, place, type, field, id, reason, details, title) {
    results.opened[place] = await page.evaluate(`(async () => { await __t.ready(); return __t.describe(${JSON.stringify(title)}); })()`);
    const result = await page.evaluate(`__t.submit(${JSON.stringify(reason)}, ${JSON.stringify(details)})`);
    const reportId = await page.evaluate(`__t.lastId(${JSON.stringify(type)}, ${JSON.stringify(field)}, ${id}, ${JSON.stringify(fx.readerToken)})`);
    results.sent.push({place, type, reason, result, reportId});
    await page.evaluate(`__t.close()`);
}

async function routineChecks(client) {
    const page = await open(client, `/routine.html?id=${fx.routine}`, {token: fx.readerToken});
    await page.evaluate(`__t.until(() => !document.getElementById("reportRoutineBtn").hidden)`);
    await page.snap("routine");

    // The keyboard alone: Enter opens, Tab stays inside, Escape closes and gives focus back.
    await page.evaluate(`document.getElementById("reportRoutineBtn").focus()`);
    await page.key("Enter", "Enter", 13);
    await page.evaluate(`__t.ready()`);
    results.keyboard.openedWithEnter = await page.evaluate(`__t.open()`);
    let inside = true;
    for (let i = 0; i < 14; i++) {
        await page.key("Tab", "Tab", 9, i % 5 === 4);
        inside = inside && await page.evaluate(`document.querySelector(".cml-report").contains(document.activeElement)`);
    }
    results.keyboard.tabStaysInside = inside;
    results.keyboard.labelled = await page.evaluate(`(() => {
        const box = document.querySelector(".cml-report");
        const title = document.getElementById(box.getAttribute("aria-labelledby"));
        const area = document.getElementById("cmlReportDetails");
        return box.getAttribute("role") === "dialog" && box.getAttribute("aria-modal") === "true"
            && !!title && title.textContent === "Report routine"
            && !!document.querySelector("label[for=cmlReportDetails]") && !!area
            && !!document.querySelector(".cml-report__reasons legend")
            && document.querySelector(".cml-report__close").getAttribute("aria-label") === "Close";
    })()`);
    await page.key("Escape", "Escape", 27);
    await pause(200);
    results.keyboard.closedWithEscape = await page.evaluate(`!__t.open()`);
    results.keyboard.focusReturned = await page.evaluate(`document.activeElement === document.getElementById("reportRoutineBtn")`);

    // An empty form is explained and sends nothing; a slow answer shows the loading state.
    await page.evaluate(`document.getElementById("reportRoutineBtn").click()`);
    await page.evaluate(`__t.ready()`);
    await page.snap("routine-dialog");
    results.opened.routine = await page.evaluate(`__t.describe("Report routine")`);
    results.form.emptyError = await page.evaluate(`(async () => {
        __t.sent.length = 0;
        document.querySelector(".cml-report__btn--primary").click();
        await __t.wait(300);
        return __t.text(document.getElementById("cmlReportReasonError"));
    })()`);
    results.form.requestsForEmpty = await page.evaluate(`__t.sent.length`);
    const loading = await page.evaluate(`(async () => {
        __t.delay = 1200;
        const d = __t.dialog();
        d.querySelector("input[value=SPAM]").click();
        const button = d.querySelector(".cml-report__btn--primary");
        button.click();
        await __t.wait(150);
        const state = {disabled: button.disabled, text: __t.text(button)};
        button.click();
        d.querySelector("form").requestSubmit();
        await __t.until(() => !d.querySelector(".cml-report__result").hidden, 6000);
        __t.delay = 0;
        state.requests = __t.sent.length;
        state.result = __t.text(d.querySelector(".cml-report__result-title"));
        return state;
    })()`);
    results.form.loadingDisabled = loading.disabled;
    results.form.loadingText = loading.text;
    results.form.requestsForDoubleClick = loading.requests;
    await page.snap("routine-sent");
    results.sent.push({
        place: "routine", type: "ROUTINE", reason: "SPAM", result: loading.result,
        reportId: await page.evaluate(`__t.lastId("ROUTINE", "reportedRoutineId", ${fx.routine}, ${JSON.stringify(fx.readerToken)})`)
    });
    await page.evaluate(`__t.close()`);

    // A comment, from its ··· menu; the other menu items are still there.
    await page.evaluate(`document.querySelector('.comment[data-id="${fx.comment}"] .comment__more').click()`);
    results.menus.viewProfile = await page.evaluate(`(() => {
        const menu = document.querySelector('.comment[data-id="${fx.comment}"] .comment-user-menu');
        const button = menu.querySelector(".comment-view-profile");
        return menu.classList.contains("open") && __t.visible(button) && Number(button.dataset.userId) === ${fx.authorId};
    })()`);
    results.menus.addFriend = await page.evaluate(`__t.visible(document.querySelector('.comment[data-id="${fx.comment}"] .comment-add-friend'))`);
    await page.evaluate(`document.querySelector('.comment[data-id="${fx.comment}"] .comment-report-user').click()`);
    results.menus.menuClosed = await page.evaluate(`!document.querySelector('.comment[data-id="${fx.comment}"] .comment-user-menu').classList.contains("open")`);
    await remember(page, "routineComment", "COMMENT", "reportedCommentId", fx.comment, "HARASSMENT", "Rude comment", "Report comment");

    await page.evaluate(`document.querySelector('.comment[data-id="${fx.reply}"] .comment-report-user').click()`);
    await remember(page, "routineReply", "COMMENT", "reportedCommentId", fx.reply, "OTHER", "A reply that " + fx.hostile, "Report comment");

    results.menus.ownCommentHasNoReport = await page.evaluate(
        `!!document.querySelector('.comment[data-id="${fx.readerComment}"]') && !document.querySelector('.comment[data-id="${fx.readerComment}"] .comment-report-user')`);
    results.menus.likeStillWorks = await page.evaluate(`(async () => {
        const before = __t.text(document.getElementById("likesCount"));
        document.getElementById("likeBtn").click();
        await __t.wait(1200);
        return __t.text(document.getElementById("likesCount")) !== before;
    })()`);
    await page.close();

    const owner = await open(client, `/routine.html?id=${fx.routine}`, {token: fx.authorToken});
    results.menus.ownerSeesNoReport = await owner.evaluate(`(async () => {
        await __t.until(() => !document.getElementById("editRoutineBtn").hidden);
        return document.getElementById("reportRoutineBtn").hidden;
    })()`);
    await owner.close();

    const guest = await open(client, `/routine.html?id=${fx.routine}`);
    results.menus.guestAskedToSignIn = await guest.evaluate(`(async () => {
        await __t.until(() => !document.getElementById("reportRoutineBtn").hidden);
        document.getElementById("reportRoutineBtn").click();
        await __t.wait(300);
        const notice = document.getElementById("copyNotice");
        return !notice.hidden && notice.textContent.includes("Sign in to report this routine") && !__t.open();
    })()`);
    await guest.close();
}

async function profileAndPostChecks(client) {
    const profile = await open(client, `/user-profile.html?id=${fx.authorId}`, {token: fx.readerToken});
    await profile.evaluate(`__t.until(() => !document.getElementById("reportUserBtn").hidden)`);
    await profile.evaluate(`document.getElementById("reportUserBtn").click()`);
    await profile.snap("profile-dialog");
    await remember(profile, "user", "USER", "reportedUserId", fx.authorId, "SPAM", "Fake profile", "Report user");
    await profile.close();

    const posts = await open(client, `/posts.html?user=${fx.authorId}`, {token: fx.readerToken});
    await posts.evaluate(`__t.until('.post[data-id="${fx.post}"] .post-report')`);
    await posts.evaluate(`document.querySelector('.post[data-id="${fx.post}"] .post-report').click()`);
    await remember(posts, "post", "POST", "reportedPostId", fx.post, "MISINFORMATION", "Wrong advice", "Report post");

    await posts.evaluate(`document.querySelector('.post[data-id="${fx.post}"] [data-social=toggle-comments]').click()`);
    await posts.evaluate(`__t.until('.post-comment[data-comment="${fx.postComment}"] .post-comment__report')`);
    await posts.evaluate(`document.querySelector('.post-comment[data-comment="${fx.postComment}"] .post-comment__report').click()`);
    await remember(posts, "postComment", "POST_COMMENT", "reportedPostCommentId", fx.postComment, "INAPPROPRIATE_CONTENT", "", "Report comment");

    // Reported before: said as soon as the dialog opens.
    await posts.evaluate(`document.querySelector('.post[data-id="${fx.alreadyPost}"] .post-report').click()`);
    results.form.alreadyOnOpen = await posts.evaluate(`(async () => {
        await __t.ready();
        const d = __t.dialog();
        return d.querySelector(".cml-report__result").hidden ? "" : __t.text(d.querySelector(".cml-report__result-title"));
    })()`);
    await posts.snap("already-reported");
    await posts.evaluate(`__t.close()`);

    // Reported from another tab after this one loaded: the server's 409 says the same.
    await posts.evaluate(`__t.mine = "[]"`);
    await posts.evaluate(`document.querySelector('.post[data-id="${fx.serverAlreadyPost}"] .post-report').click()`);
    await posts.evaluate(`__t.ready()`);
    results.form.alreadyFromServer = await posts.evaluate(`__t.submit("SPAM", "")`);
    await posts.evaluate(`__t.close()`);
    await posts.evaluate(`__t.mine = null`);

    // Deleted while the dialog was open.
    await posts.evaluate(`document.querySelector('.post[data-id="${fx.gonePost}"] .post-report').click()`);
    await posts.evaluate(`__t.ready()`);
    await posts.evaluate(`fetch("/posts/${fx.gonePost}", {method: "DELETE", headers: {Authorization: "Bearer " + ${JSON.stringify(fx.authorToken)}}})`);
    const gone = await posts.evaluate(`__t.submit("SPAM", "")`);
    results.form.goneError = String(gone || "").replace(/^error: /, "");
    await posts.evaluate(`__t.close()`);
    await posts.close();
}

async function adminChecks(client) {
    const page = await open(client, "/admin-reports.html", {admin: true});
    await page.evaluate(`__t.until("#reportsTable tr[data-report]")`);
    await page.snap("admin-reports");
    const labels = {USER: "User", COMMENT: "Routine comment", ROUTINE: "Routine", POST: "Post", POST_COMMENT: "Post comment"};
    for (const [type, id] of Object.entries(fx.adminReports)) {
        results.admin.types[type] = await page.evaluate(`(() => {
            const row = document.querySelector('#reportsTable tr[data-report="${id}"]');
            return !!row && __t.text(row.children[1]) === ${JSON.stringify(labels[type])}
                && __t.text(row.children[4]).startsWith("Harassment");
        })()`);
    }
    results.admin.hostileShownAsText = await page.evaluate(`(() => {
        const row = document.querySelector('#reportsTable tr[data-report="${fx.adminReports.POST}"]');
        return row.querySelector(".report-details").textContent === ${JSON.stringify(fx.hostile)}
            && !row.querySelector(".report-details img, .report-details script");
    })()`);
    results.admin.xssRan = await page.evaluate(`window.__xss !== undefined`);

    await page.evaluate(`document.querySelector('#reportsTable tr[data-report="${fx.adminReports.USER}"] [data-action=review]').click()`);
    await page.evaluate(`(async () => { (await __t.until(".admin-confirm-submit")).click(); await __t.wait(1500); })()`);
    results.admin.reviewedId = fx.adminReports.USER;

    await page.evaluate(`document.querySelector('#reportsTable tr[data-report="${fx.adminReports.ROUTINE}"] [data-action="hide-routine"]').click()`);
    await page.evaluate(`(async () => { (await __t.until(".admin-confirm-submit")).click(); await __t.wait(1500); })()`);
    results.admin.hiddenId = fx.adminReports.ROUTINE;
    results.admin.hiddenRowClosed = await page.evaluate(
        `__t.text(document.querySelector('#reportsTable tr[data-report="${fx.adminReports.ROUTINE}"] .actions')) === "Closed"`);
    await page.close();
}

async function layoutChecks(client) {
    for (const theme of ["original", "autumn"]) {
        for (const width of [360, 390, 768, 1280, 1366, 1920]) {
            const page = await open(client, `/routine.html?id=${fx.routine}`, {token: fx.layoutToken, width, theme});
            await page.evaluate(`__t.until(() => !document.getElementById("reportRoutineBtn").hidden)`);
            await page.evaluate(`document.getElementById("reportRoutineBtn").click()`);
            await page.evaluate(`__t.ready()`);
            const dialog = await page.evaluate(LAYOUT);
            dialog.theme = await page.evaluate(`document.documentElement.getAttribute("data-theme") || ""`);
            dialog.themeApplied = dialog.theme === theme;
            await page.snap(`dialog-${theme}-${width}`);
            await page.evaluate(`__t.close()`);
            const closed = await page.evaluate(LAYOUT);
            dialog.pageOverflow = closed.overflow;
            dialog.heroInside = closed.heroInside;
            dialog.overflow = Math.max(dialog.overflow, dialog.pageOverflow);
            results.layouts[`dialog-${theme}-${width}`] = dialog;
            await page.close();

            const admin = await open(client, "/admin-reports.html", {admin: true, width, theme});
            await admin.evaluate(`__t.until("#reportsTable tr[data-report]")`);
            const view = await admin.evaluate(LAYOUT);
            view.theme = await admin.evaluate(`document.documentElement.dataset.adminTheme || ""`);
            view.themeApplied = view.theme === theme;
            await admin.snap(`admin-${theme}-${width}`);
            results.layouts[`admin-${theme}-${width}`] = view;
            await admin.close();
        }
    }
}

const profile = mkdtempSync(join(tmpdir(), "cml-report-"));
const {chrome, endpoint} = await startChrome(profile);
try {
    const client = await connect(endpoint);
    await routineChecks(client);
    await profileAndPostChecks(client);
    await adminChecks(client);
    await layoutChecks(client);
    client.close();
} finally {
    chrome.kill();
    await pause(500);
    try {
        rmSync(profile, {recursive: true, force: true});
    } catch (ignored) {
        // Chrome may still hold a file for a moment; the folder is in the temp directory.
    }
}

results.errors = errors;
results.dialogs = dialogs;
writeFileSync(resultsPath, JSON.stringify(results, null, 2));
