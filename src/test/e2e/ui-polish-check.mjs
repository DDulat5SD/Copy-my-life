// Sign In and Sign Up, inline form errors, the main footer, Halloween Explore and Top Routines
// in headless Chrome, in both designs and from a 360 px phone to a 1920 px desktop.
// Started by UiPolishRegressionTest; talks only to 127.0.0.1.
//
// Usage: node ui-polish-check.mjs <chrome> <fixtures.json> <results.json>
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
// Fixed answers for some requests, by session; everything else goes to the real server.
const answers = new Map();
// Optional: set UI_SCREENSHOTS to a folder to keep a picture of each state for a visual check.
const screenshots = process.env.UI_SCREENSHOTS;
const WIDTHS = [360, 390, 1280, 1366, 1536, 1920];

const HELPERS = `
    window.__ui = {
        visible: (el) => !!el && !el.hidden && el.offsetParent !== null && getComputedStyle(el).visibility !== "hidden",
        text: (el) => (el?.textContent || "").trim().replace(/\\s+/g, " "),
        shown(el) { return this.visible(el) ? this.text(el) : ""; },
        wait: (ms) => new Promise((done) => setTimeout(done, ms)),
        fill(values) {
            for (const [id, value] of Object.entries(values)) {
                const field = document.getElementById(id);
                field.value = value;
                field.dispatchEvent(new Event("input", {bubbles: true}));
            }
        },
        async submit(selector, ms = 600) {
            document.querySelector(selector).querySelector('[type="submit"], button:not([type="button"])').click();
            await this.wait(ms);
        },
        parse(value) {
            const found = value.match(/rgba?\\(([^)]+)\\)/);
            if (!found) return null;
            const parts = found[1].split(/[\\s,\\/]+/).filter(Boolean).map(Number);
            return {r: parts[0], g: parts[1], b: parts[2], a: parts.length > 3 ? parts[3] : 1};
        },
        contrast(el) {
            const channel = (v) => { v /= 255; return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4); };
            const lum = (c) => 0.2126 * channel(c.r) + 0.7152 * channel(c.g) + 0.0722 * channel(c.b);
            let back = {r: 255, g: 255, b: 255};
            for (let node = el; node; node = node.parentElement) {
                const color = this.parse(getComputedStyle(node).backgroundColor);
                if (color && color.a > 0.5) { back = color; break; }
            }
            const a = lum(this.parse(getComputedStyle(el).color));
            const b = lum(back);
            return Math.round((Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05) * 100) / 100;
        },
        overlaps(elements) {
            const boxes = elements.filter((el) => this.visible(el)).map((el) => el.getBoundingClientRect());
            let found = 0;
            for (let i = 0; i < boxes.length; i++) {
                for (let j = i + 1; j < boxes.length; j++) {
                    const a = boxes[i], b = boxes[j];
                    if (a.left < b.right - 1 && b.left < a.right - 1 && a.top < b.bottom - 1 && b.top < a.bottom - 1) found++;
                }
            }
            return found;
        },
        requests: () => window.__requests.length
    };
    true`;

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
            // A blocking alert() would freeze the page; record it and dismiss it.
            if (message.method === "Page.javascriptDialogOpening") {
                dialogs.push(message.params.message);
                send("Page.handleJavaScriptDialog", {accept: true}, message.sessionId);
                return;
            }
            if (message.method === "Runtime.exceptionThrown") {
                errors.push(message.params.exceptionDetails.exception?.description
                    || message.params.exceptionDetails.text);
                return;
            }
            if (message.method === "Fetch.requestPaused") {
                const answer = answers.get(message.sessionId);
                send("Fetch.fulfillRequest", {
                    requestId: message.params.requestId,
                    responseCode: answer.status,
                    responseHeaders: [{name: "Content-Type", value: "application/json"}],
                    body: Buffer.from(answer.body).toString("base64")
                }, message.sessionId);
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

// token null opens the page as a visitor; answer fixes the reply to requests matching answer.url.
async function open(client, {path, token = null, width = 1280, theme = "original", answer = null, wait = 2200}) {
    const {targetId} = await client.send("Target.createTarget", {url: "about:blank"});
    const {sessionId} = await client.send("Target.attachToTarget", {targetId, flatten: true});

    await client.send("Page.enable", {}, sessionId);
    await client.send("Runtime.enable", {}, sessionId);
    if (answer) {
        answers.set(sessionId, answer);
        await client.send("Fetch.enable", {patterns: [{urlPattern: answer.url}]}, sessionId);
    }
    await client.send("Emulation.setDeviceMetricsOverride", {
        width, height: width < 600 ? 844 : 900, deviceScaleFactor: width < 600 ? 2 : 1, mobile: width < 600
    }, sessionId);
    await client.send("Page.addScriptToEvaluateOnNewDocument", {
        source: `if (!sessionStorage.getItem("uiCheckStarted")) {
                     sessionStorage.setItem("uiCheckStarted", "1");
                     ${token ? `localStorage.setItem("token", ${JSON.stringify(token)});` : `localStorage.removeItem("token");`}
                 }
                 localStorage.setItem("copymylife-theme", ${JSON.stringify(theme)});
                 window.__requests = [];
                 const realFetch = window.fetch;
                 window.fetch = function (url, options) {
                     window.__requests.push({url: String(url), method: (options && options.method) || "GET"});
                     return realFetch.apply(this, arguments);
                 };`
    }, sessionId);

    const evaluate = async (expression) => {
        let reply;
        try {
            reply = await client.send("Runtime.evaluate", {expression, returnByValue: true, awaitPromise: true}, sessionId);
        } catch (error) {
            // The page left while it was being read, for example after a successful sign-in.
            if (/navigated or closed|context was destroyed/.test(error.message)) {
                await pause(800);
                return null;
            }
            throw error;
        }
        if (reply.exceptionDetails) {
            throw new Error(path + ": " + (reply.exceptionDetails.exception?.description || reply.exceptionDetails.text));
        }
        return reply.result.value;
    };
    const go = async (to, ms = wait) => {
        await client.send("Page.navigate", {url: `${fx.base}/${to}`}, sessionId);
        await pause(ms);
        await evaluate(HELPERS);
    };
    await go(path);

    return {
        evaluate,
        go,
        reload: async (ms = wait) => {
            await client.send("Page.reload", {}, sessionId);
            await pause(ms);
            await evaluate(HELPERS);
        },
        helpers: () => evaluate(HELPERS),
        snap: async (name) => {
            if (!screenshots) {
                return;
            }
            const {data} = await client.send("Page.captureScreenshot", {format: "png", captureBeyondViewport: true}, sessionId);
            writeFileSync(join(screenshots, `${name}.png`), Buffer.from(data, "base64"));
        },
        close: () => client.send("Target.closeTarget", {targetId})
    };
}

const SIGN_UP = `(() => {
    const u = window.__ui;
    const after = (el) => el && el.nextElementSibling && el.nextElementSibling.matches(".signup-field-error")
        ? el.nextElementSibling : null;
    const name = document.getElementById("fullname");
    const email = document.getElementById("email");
    const wrap = document.querySelector(".password-wrap");
    let messagesUnderName = 0;
    for (let el = name.nextElementSibling; el && el.tagName !== "LABEL"; el = el.nextElementSibling) {
        if (u.visible(el) && u.text(el)) messagesUnderName++;
    }
    const rules = [...document.querySelectorAll(".signup-strength-rules span")].filter((el) => u.visible(el));
    return {
        noValidate: document.getElementById("registerForm").noValidate,
        name: u.shown(after(name)),
        email: u.shown(after(email)),
        password: u.shown(after(wrap)),
        form: u.shown(document.getElementById("registerError")),
        focused: document.activeElement?.id || "",
        nameInvalid: name.getAttribute("aria-invalid") || "",
        messagesUnderName,
        rules: rules.length,
        rulesPassed: rules.filter((el) => el.classList.contains("passed")).length,
        requests: window.__requests.filter((r) => r.url.includes("/auth/")).length,
        url: location.pathname,
        hasToken: !!localStorage.getItem("token")
    };
})()`;

const LOGIN = `(() => {
    const u = window.__ui;
    return {
        noValidate: document.getElementById("loginForm").noValidate,
        email: u.shown(document.getElementById("emailError")),
        password: u.shown(document.getElementById("passwordError")),
        form: u.shown(document.getElementById("loginError")),
        focused: document.activeElement?.id || "",
        requests: window.__requests.filter((r) => r.url.includes("/auth/")).length,
        url: location.pathname,
        hasToken: !!localStorage.getItem("token")
    };
})()`;

// The CopyMyLife mark: .logo-icon/.logo-text in the site header, .brand-icon/.brand-name on Sign In and Sign Up.
const BRAND = `(() => {
    const iconEl = document.querySelector(".header .logo-icon, .brand .brand-icon, .brand img");
    const nameEl = document.querySelector(".header .logo-text, .brand .brand-name");
    const icon = iconEl.getBoundingClientRect();
    const name = nameEl.getBoundingClientRect();
    const iconStyle = getComputedStyle(iconEl);
    const style = getComputedStyle(nameEl);
    const rect = (selector) => {
        const el = document.querySelector(selector);
        if (!el || el.offsetParent === null) return null;
        const r = el.getBoundingClientRect();
        return {left: Math.round(r.left), right: Math.round(r.right), width: Math.round(r.width), height: Math.round(r.height)};
    };
    const card = document.querySelector(".auth-card");
    return {
        iconWidth: icon.width, iconHeight: icon.height,
        iconRadius: iconStyle.borderRadius, iconBackground: iconStyle.backgroundImage,
        imageSrc: (iconEl.matches("img") ? iconEl : iconEl.querySelector("img"))?.getAttribute("src") || "",
        nameFamily: style.fontFamily, nameSize: style.fontSize, nameWeight: style.fontWeight,
        nameColor: style.color, nameText: nameEl.textContent.trim(),
        gap: Math.round(name.left - icon.right),
        logoLeft: Math.round(icon.left), logoTop: Math.round(icon.top),
        form: rect(".form-panel"), photo: rect(".photo-panel"), photoImage: rect(".photo-panel img"),
        card: rect(".auth-card"),
        cardRadius: card ? getComputedStyle(card).borderRadius : "",
        overflow: document.documentElement.scrollWidth - document.documentElement.clientWidth
    };
})()`;

// Clicks a link the way a person would and reports whether the page held the navigation back.
const follow = (selector) => `(() => {
    const link = document.querySelector(${JSON.stringify(selector)});
    const click = new MouseEvent("click", {bubbles: true, cancelable: true});
    link.dispatchEvent(click);
    return click.defaultPrevented;
})()`;

const EXPLORE_LAYOUT = `(() => {
    const u = window.__ui;
    const title = document.getElementById("exploreTitle");
    const box = title.getBoundingClientRect();
    const filters = document.querySelector(".explore-hero .filters");
    const cards = [...document.querySelectorAll("#routineResults .featured, #routineResults .card, #routineResults .mini-card")]
        .filter((el) => u.visible(el));
    return {
        width: innerWidth,
        overflow: document.documentElement.scrollWidth - document.documentElement.clientWidth,
        scrollWidth: document.documentElement.scrollWidth,
        clientWidth: document.documentElement.clientWidth,
        rows: new Set([...filters.querySelectorAll(".chip")].map((chip) => Math.round(chip.getBoundingClientRect().top))).size,
        chips: [...filters.querySelectorAll(".chip")].map((chip) => u.text(chip)),
        chipsClipped: [...filters.querySelectorAll(".chip")].filter((chip) => chip.scrollWidth > chip.clientWidth + 1).length,
        viewAllOffCenter: (() => {
            const r = document.getElementById("viewAllBtn").getBoundingClientRect();
            return Math.round(Math.abs((r.left + r.right) / 2 - document.documentElement.clientWidth / 2));
        })(),
        title: u.text(title),
        titleClipped: title.scrollWidth > title.clientWidth + 1,
        titleLeft: Math.round(box.left),
        titleRight: Math.round(box.right),
        titleContrast: u.contrast(title),
        filtersScroll: filters.scrollWidth > filters.clientWidth + 1,
        filtersScrollbarColor: getComputedStyle(filters).scrollbarColor,
        cardsOutside: cards.filter((el) => { const r = el.getBoundingClientRect(); return r.left < -1 || r.right > innerWidth + 1; }).length,
        viewAllVisible: u.visible(document.getElementById("viewAllBtn"))
    };
})()`;

const HEADING = `({
    title: window.__ui.text(document.getElementById("exploreTitle")),
    subtitle: window.__ui.text(document.getElementById("exploreSubtitle"))
})`;

const chip = (filter) => `(async () => {
    document.querySelector('.chip[data-filter="${filter}"]').click();
    await window.__ui.wait(700);
    return true;
})()`;

const HALLOWEEN_CARDS = `(() => {
    const u = window.__ui;
    const idOf = (el, attr) => el ? Number(attr ? el.getAttribute(attr) : (el.getAttribute("href").match(/id=(\\d+)/) || [])[1] || 0) : 0;
    const describe = (card) => ({
        name: u.text(card.querySelector(".featured-name, .card-name, .mini-name, .view-all-name")),
        community: card.classList.contains("public-routine-card"),
        saveId: idOf(card.querySelector(".save-routine-btn"), "data-routine-id"),
        likeId: idOf(card.querySelector(".like-routine-btn"), "data-routine-id"),
        linkId: idOf(card.querySelector('a[href^="routine.html?id="]')),
        rating: u.visible(card.querySelector(".routine-rating-display")),
        ratingValue: u.text(card.querySelector(".rating-value, .routine-rating-value")),
        ratingCount: u.text(card.querySelector(".rating-count, .routine-rating-count")),
        saved: !!card.querySelector(".save-routine-btn.is-saved"),
        liked: !!card.querySelector(".like-routine-btn.is-liked"),
        saveCount: Number(u.text(card.querySelector(".save-routine-count")))
    });
    const cards = [...document.querySelectorAll("#routineResults .featured, #routineResults .card, #routineResults .mini-card")];
    const viewAll = [...document.querySelectorAll("#viewAllGrid .view-all-card")];
    const overlaps = [...cards, ...viewAll].reduce((sum, card) => sum + u.overlaps(
        [...card.querySelectorAll(".save-routine-btn, .like-routine-btn, a[href^='routine.html'], .routine-rating-display")]), 0);
    return {
        cards: cards.map(describe),
        viewAll: viewAll.map(describe),
        overlaps,
        overflow: document.documentElement.scrollWidth - innerWidth,
        halloween: document.body.classList.contains("halloween-mode")
    };
})()`;

const FOOTER = `(() => {
    const u = window.__ui;
    const footer = document.querySelector(".site-footer");
    const box = footer.getBoundingClientRect();
    const texts = [...footer.querySelectorAll("a, p, h4, .site-footer-logo, .site-footer-copy, button")].filter((el) => u.visible(el));
    return {
        width: innerWidth,
        overflow: document.documentElement.scrollWidth - innerWidth,
        height: Math.round(box.height),
        rightmost: Math.round(Math.max(0, ...texts.map((el) => el.getBoundingClientRect().right))),
        minContrast: Math.min(...texts.map((el) => u.contrast(el))),
        links: [...footer.querySelectorAll("a")].map((a) => ({text: u.text(a), href: a.getAttribute("href")})),
        copyright: u.text(footer.querySelector(".site-footer-copy")),
        rateButton: u.visible(document.getElementById("footerRateSiteBtn"))
    };
})()`;

const TOP_IDS = `[...document.querySelectorAll("#rankingList .ranking-card")].map((card) => Number(card.dataset.routineId))`;

const topRoutines = (category, sort, period) => `(async () => {
    const select = document.getElementById("categoryFilter");
    select.value = ${JSON.stringify(category)};
    select.dispatchEvent(new Event("change", {bubbles: true}));
    document.querySelector('.ranking-filter[data-filter="${sort}"]').click();
    document.querySelector('.period-btn[data-period="${period}"]').click();
    await window.__ui.wait(900);
    return ${TOP_IDS};
})()`;

const profile = mkdtempSync(join(tmpdir(), "cml-ui-polish-"));
const {chrome, endpoint} = await startChrome(profile);

try {
    const client = await connect(endpoint);
    const results = {};

    // ---- Sign Up ----
    const signUp = await open(client, {path: "signin.html"});
    await signUp.evaluate(`window.__ui.submit("#registerForm")`);
    results.signUpEmpty = await signUp.evaluate(SIGN_UP);
    await signUp.snap("signup-empty");
    await signUp.evaluate(`window.__ui.fill({fullname: "Ada Lovelace", email: "ada@", password: ""}); window.__ui.submit("#registerForm")`);
    results.signUpInvalidEmail = await signUp.evaluate(SIGN_UP);
    await signUp.evaluate(`window.__ui.fill({email: "ada@example.com", password: "abc"}); window.__ui.submit("#registerForm")`);
    results.signUpWeakPassword = await signUp.evaluate(SIGN_UP);
    await signUp.snap("signup-weak");
    await signUp.evaluate(`window.__ui.fill({fullname: "Ada Lovelace", email: "ada@example.com", password: ${JSON.stringify(fx.password)}}); true`);
    results.signUpValidBeforeSubmit = await signUp.evaluate(SIGN_UP);
    await signUp.evaluate(`window.__ui.fill({email: ${JSON.stringify(fx.fanEmail)}}); window.__ui.submit("#registerForm", 1500)`);
    results.signUpDuplicate = await signUp.evaluate(SIGN_UP);
    await signUp.snap("signup-duplicate");
    await signUp.evaluate(`window.__ui.fill({email: ${JSON.stringify(fx.newEmail)}}); window.__ui.submit("#registerForm", 2500)`);
    await signUp.helpers();
    results.signUpSuccess = await signUp.evaluate(`({url: location.pathname, hasToken: !!localStorage.getItem("token")})`);
    await signUp.close();

    const signUpDown = await open(client, {path: "signin.html", answer: {url: "*/auth/register*", status: 500, body: "{}"}});
    await signUpDown.evaluate(`window.__ui.fill({fullname: "Ada Lovelace", email: "down@example.com", password: ${JSON.stringify(fx.password)}});
        window.__ui.submit("#registerForm", 1200)`);
    results.signUpServerError = await signUpDown.evaluate(SIGN_UP);
    await signUpDown.close();

    // ---- Sign In ----
    const login = await open(client, {path: "login.html"});
    await login.evaluate(`window.__ui.submit("#loginForm")`);
    results.loginEmpty = await login.evaluate(LOGIN);
    await login.snap("login-empty");
    await login.evaluate(`window.__ui.fill({email: "ada@", password: "x"}); window.__ui.submit("#loginForm")`);
    results.loginInvalidEmail = await login.evaluate(LOGIN);
    await login.evaluate(`window.__ui.fill({email: ${JSON.stringify(fx.fanEmail)}, password: "Wrong#12345"}); window.__ui.submit("#loginForm", 1500)`);
    results.loginWrongPassword = await login.evaluate(LOGIN);
    await login.evaluate(`window.__ui.fill({password: ${JSON.stringify(fx.password)}}); window.__ui.submit("#loginForm", 2500)`);
    await login.helpers();
    results.loginSuccess = await login.evaluate(`({url: location.pathname, hasToken: !!localStorage.getItem("token")})`);
    await login.go("login.html?signedout=1");
    const signedOut = await login.evaluate(`window.__ui.shown(document.getElementById("loginNotice"))`);
    await login.go("login.html?session=expired");
    results.loginNotices = {signedOut, expired: await login.evaluate(`window.__ui.shown(document.getElementById("loginNotice"))`)};
    await login.close();

    // ---- switching between them ----
    const switching = await open(client, {path: "signin.html"});
    const bodyTransition = await switching.evaluate(`getComputedStyle(document.body).transitionProperty`);
    const signUpToSignInDelayed = await switching.evaluate(follow('.switch-text a[href="login.html"]'));
    await pause(1200);
    await switching.helpers();
    const arrivedAtSignIn = await switching.evaluate(`location.pathname.endsWith("/login.html")`);
    const signInToSignUpDelayed = await switching.evaluate(follow('.switch-text a[href="signin.html"]'));
    await pause(1200);
    const arrivedAtSignUp = await switching.evaluate(`location.pathname.endsWith("/signin.html")`);
    results.switching = {bodyTransition, signUpToSignInDelayed, arrivedAtSignIn, signInToSignUpDelayed, arrivedAtSignUp};
    await switching.close();

    // ---- one brand on both pages ----
    for (const [name, options] of Object.entries({
        brand1280: {width: 1280}, brand1366: {width: 1366}, brand390: {width: 390},
        brandAutumn1280: {width: 1280, theme: "autumn"}, brandAutumn390: {width: 390, theme: "autumn"}
    })) {
        const pageA = await open(client, {path: "main.html", ...options});
        const mainBrand = await pageA.evaluate(BRAND);
        await pageA.go("login.html");
        const loginBrand = await pageA.evaluate(BRAND);
        await pageA.snap(name + "-login");
        await pageA.go("signin.html");
        const signupBrand = await pageA.evaluate(BRAND);
        await pageA.snap(name + "-signup");
        results[name] = {main: mainBrand, login: loginBrand, signup: signupBrand};
        await pageA.close();
    }

    // ---- other forms ----
    const forgot = await open(client, {path: "forgot-password.html"});
    await forgot.evaluate(`window.__ui.submit("#forgotPasswordForm")`);
    results.forgotEmpty = {error: await forgot.evaluate(`window.__ui.shown(document.getElementById("emailError"))`)};
    await forgot.evaluate(`window.__ui.fill({email: "ada@"}); window.__ui.submit("#forgotPasswordForm")`);
    results.forgotInvalid = {error: await forgot.evaluate(`window.__ui.shown(document.getElementById("emailError"))`)};
    await forgot.evaluate(`window.__ui.fill({email: ${JSON.stringify(fx.fanEmail)}}); window.__ui.submit("#forgotPasswordForm", 1500)`);
    results.forgotSent = {message: await forgot.evaluate(`window.__ui.shown(document.getElementById("formMessage"))`)};
    await forgot.close();

    const todo = await open(client, {path: "todo-lists.html", token: fx.fanToken});
    await todo.evaluate(`window.__ui.submit("#newListForm")`);
    results.todoEmpty = {error: await todo.evaluate(`window.__ui.shown(document.querySelector("#newListForm .mc-field-error"))`)};
    await todo.close();

    const posts = await open(client, {path: "posts.html", token: fx.fanToken});
    await posts.evaluate(`window.__ui.submit("#newPostForm")`);
    results.postEmpty = {error: await posts.evaluate(`window.__ui.shown(document.querySelector("#newPostForm .mc-field-error"))`)};
    await posts.close();

    const habit = await open(client, {path: "profile.html", token: fx.fanToken, wait: 2800});
    await habit.evaluate(`(async () => {
        document.getElementById("createHabitBtn").click();
        await window.__ui.wait(300);
        document.getElementById("saveHabitBtn").click();
        await window.__ui.wait(500);
        return true;
    })()`);
    results.habitEmpty = await habit.evaluate(`({
        title: window.__ui.shown(document.getElementById("habitTitleError")),
        duration: window.__ui.shown(document.getElementById("habitDurationError"))
    })`);
    await habit.close();

    const admin = await open(client, {path: "admin-login.html"});
    await admin.evaluate(`window.__ui.submit("#loginForm")`);
    results.adminLoginEmpty = await admin.evaluate(`({
        email: window.__ui.shown(document.getElementById("emailError")),
        password: window.__ui.shown(document.getElementById("passwordError"))
    })`);
    await admin.close();

    // ---- footer ----
    const footer = await open(client, {path: "main.html"});
    results.footer = await footer.evaluate(FOOTER);
    await footer.close();

    const rate = await open(client, {path: "main.html", token: fx.fanToken});
    await rate.evaluate(`(async () => { document.getElementById("footerRateSiteBtn").click(); await window.__ui.wait(500); return true; })()`);
    results.footerRate = {ratingModalOpen: await rate.evaluate(`document.getElementById("siteRatingModal").classList.contains("show")`)};
    await rate.close();

    const sections = await open(client, {path: "profile.html#calendar", token: fx.fanToken, wait: 2800});
    results.footerSections = {calendar: await sections.evaluate(`window.__ui.visible(document.getElementById("calendarContent"))`)};
    await sections.go("main.html", 1200);
    await sections.go("profile.html#my-routines", 2800);
    results.footerSections.myRoutines = await sections.evaluate(`window.__ui.visible(document.getElementById("accountContent"))
        && !window.__ui.visible(document.getElementById("calendarContent"))`);
    await sections.go("main.html", 1200);
    await sections.go("profile.html#create-routine", 2800);
    results.footerSections.createRoutine = await sections.evaluate(`document.getElementById("addRoutineModal").classList.contains("show")`);
    await sections.go("main.html", 1200);
    await sections.go("profile.html#progress", 2800);
    results.footerSections.progress = await sections.evaluate(`window.__ui.visible(document.getElementById("progressContent"))`);
    await sections.close();

    const guestProfile = await open(client, {path: "profile.html#progress", wait: 2500});
    results.footerGuest = {url: await guestProfile.evaluate(`location.pathname`)};
    await guestProfile.close();

    for (const theme of ["original", "autumn"]) {
        for (const width of WIDTHS) {
            const view = await open(client, {path: "main.html", width, theme});
            await view.evaluate(`document.querySelector(".site-footer").scrollIntoView({block: "end"}); true`);
            results[`footer_${theme}_${width}`] = await view.evaluate(FOOTER);
            await view.snap(`footer-${theme}-${width}`);
            await view.close();
        }
    }

    // ---- Halloween heading ----
    const heading = await open(client, {path: "Explore.html"});
    await heading.evaluate(chip("halloween"));
    results.heading = {halloween: await heading.evaluate(HEADING)};
    await heading.evaluate(chip("all"));
    results.heading.all = await heading.evaluate(HEADING);
    await heading.evaluate(chip("morning"));
    results.heading.morning = await heading.evaluate(HEADING);
    await heading.go("Explore.html?category=halloween");
    results.heading.returning = await heading.evaluate(HEADING);
    await heading.close();

    // ---- every design and screen: heading, sideways scrolling ----
    for (const theme of ["original", "autumn"]) {
        for (const filter of ["all", "halloween", "creators"]) {
            for (const width of WIDTHS) {
                const view = await open(client, {path: "Explore.html", width, theme});
                if (filter !== "all") {
                    await view.evaluate(chip(filter));
                }
                results[`explore_${theme}_${filter}_${width}`] = await view.evaluate(EXPLORE_LAYOUT);
                await view.evaluate(`(async () => { document.getElementById("viewAllBtn").click(); await window.__ui.wait(900);
                    scrollTo(0, document.documentElement.scrollHeight); await window.__ui.wait(300); return true; })()`);
                results[`explore_${theme}_${filter}_${width}`].viewAllOpen = await view.evaluate(EXPLORE_LAYOUT);
                await view.evaluate(`document.getElementById("viewAllBtn").click(); true`);
                await pause(600);
                await view.evaluate(`document.getElementById("viewAllBtn").scrollIntoView({block: "center"}); true`);
                await view.snap(`explore-${theme}-${filter}-${width}`);
                await view.close();
            }
        }
    }

    // ---- Halloween Like, Save and Rating ----
    const cards = await open(client, {path: "Explore.html?category=halloween", token: fx.fanToken, wait: 3000});
    await cards.evaluate(`(async () => { document.getElementById("viewAllBtn").click(); await window.__ui.wait(600); return true; })()`);
    results.halloweenCards = await cards.evaluate(HALLOWEEN_CARDS);
    await cards.snap("halloween-cards");
    const before = results.halloweenCards.cards[0];
    const likeRequests = await cards.evaluate(`(async () => {
        const like = document.querySelector("#routineResults .featured .like-routine-btn");
        like?.click();
        like?.click();
        await window.__ui.wait(1200);
        document.querySelector("#routineResults .featured .save-routine-btn")?.click();
        await window.__ui.wait(1200);
        return window.__requests.filter((r) => r.url.includes("/liked-routines/") && r.method === "POST").length;
    })()`);
    await cards.reload(3000);
    const after = (await cards.evaluate(HALLOWEEN_CARDS)).cards[0];
    results.halloweenSocial = {
        likeRequests,
        likedAfterReload: after.liked,
        savedAfterReload: after.saved,
        saveCountBefore: before.saveCount,
        saveCountAfterReload: after.saveCount
    };
    await cards.snap("halloween-cards-after");
    await cards.go(`routine.html?id=${fx.billyId}`, 3000);
    results.halloweenDetail = await cards.evaluate(`({
        liked: document.getElementById("likeBtn").classList.contains("is-active"),
        saved: document.getElementById("saveBtn").classList.contains("is-active"),
        ratingAverage: window.__ui.text(document.getElementById("ratingAverage")),
        ratingInput: window.__ui.visible(document.getElementById("ratingInput"))
    })`);
    await cards.close();

    const guest = await open(client, {path: "Explore.html?category=halloween", wait: 3000});
    const buttons = await guest.evaluate(`document.querySelectorAll("#routineResults .save-routine-btn, #routineResults .like-routine-btn").length`);
    await guest.evaluate(`document.querySelector("#routineResults .featured .like-routine-btn")?.click(); true`);
    await pause(1500);
    results.halloweenGuest = {buttons, urlAfterLike: await guest.evaluate(`location.pathname`)};
    await guest.close();

    for (const width of [360, 390]) {
        for (const theme of ["original", "autumn"]) {
            const phone = await open(client, {path: "Explore.html?category=halloween", token: fx.fanToken, width, theme, wait: 3000});
            await phone.evaluate(`(async () => { document.getElementById("viewAllBtn").click(); await window.__ui.wait(600); return true; })()`);
            const view = await phone.evaluate(HALLOWEEN_CARDS);
            await phone.snap(`halloween-phone-${theme}-${width}`);
            const key = "halloweenPhone" + width;
            results[key] = {
                overlaps: (results[key]?.overlaps || 0) + view.overlaps,
                overflow: Math.max(results[key]?.overflow || 0, view.overflow)
            };
            await phone.close();
        }
    }

    // ---- Top Routines ----
    const top = await open(client, {path: "top-routines.html"});
    results.topRoutines = {
        options: await top.evaluate(`[...document.querySelectorAll("#categoryFilter option")].map((o) => o.textContent.trim())`),
        halloween: {}
    };
    for (const sort of ["overall", "liked", "saved", "rated"]) {
        for (const period of ["day", "week", "month"]) {
            results.topRoutines.halloween[`${sort}_${period}`] = await top.evaluate(topRoutines("Halloween", sort, period));
        }
    }
    await top.snap("top-routines-halloween");
    results.topRoutines.morning = await top.evaluate(topRoutines("Morning", "liked", "week"));
    await top.close();

    const topEmpty = await open(client, {path: "top-routines.html", answer: {url: "*/top-routines*period=*", status: 200, body: "[]"}});
    await topEmpty.evaluate(topRoutines("Halloween", "overall", "week"));
    results.topRoutines.empty = await topEmpty.evaluate(`window.__ui.text(document.querySelector("#rankingList .ranking-empty"))`);
    await topEmpty.evaluate(topRoutines("all", "overall", "week"));
    results.topRoutines.emptyAll = await topEmpty.evaluate(`window.__ui.text(document.querySelector("#rankingList .ranking-empty"))`);
    await topEmpty.close();

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
