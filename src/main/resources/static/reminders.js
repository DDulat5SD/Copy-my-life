// US-22: routine reminders, shown inside the app on every signed-in page. While the page is in
// view it asks the server once a minute for the reminders that are due; the server hands each
// one out once (to one tab or device) and none while the user has reminders off. Profile ->
// Settings also uses this file for the reminder setting and today's list.
(function () {

    const POLL_MS = 60 * 1000;
    const MAX_SHOWN = 3;
    let timer = null;
    let stopped = false;

    function token() {
        return localStorage.getItem("token");
    }

    function headers(json) {
        const result = {
            "Authorization": "Bearer " + token(),
            "X-Time-Zone": Intl.DateTimeFormat().resolvedOptions().timeZone || ""
        };
        if (json) {
            result["Content-Type"] = "application/json";
        }
        return result;
    }

    function element(tag, className, text) {
        const node = document.createElement(tag);
        if (className) {
            node.className = className;
        }
        if (text !== undefined) {
            node.textContent = text;
        }
        return node;
    }

    function time(reminder) {
        return String(reminder.time || "").slice(0, 5);
    }

    function when(reminder) {
        const minutes = Number(reminder.minutesUntil);
        return (minutes > 0 ? "Starts in " + minutes + " min" : "Starting now") + " · " + time(reminder);
    }

    function detail(reminder) {
        return reminder.kind === "HABIT"
            ? "Activity in " + reminder.routineTitle
            : "Routine on your calendar";
    }

    function stack() {
        let box = document.getElementById("cmlReminders");
        if (!box) {
            box = element("div", "cml-reminders");
            box.id = "cmlReminders";
            box.setAttribute("role", "region");
            box.setAttribute("aria-label", "Reminders");
            box.setAttribute("aria-live", "polite");
            document.body.appendChild(box);
        }
        return box;
    }

    function show(reminder) {
        const box = stack();

        if (box.querySelector('[data-reminder-key="' + CSS.escape(reminder.key) + '"]')) {
            return;
        }

        const card = element("div", "cml-reminder");
        card.setAttribute("role", "status");
        card.dataset.reminderKey = reminder.key;

        const body = element("div", "cml-reminder-body");
        body.appendChild(element("span", "cml-reminder-when", when(reminder)));
        body.appendChild(element("strong", "cml-reminder-title", reminder.title));
        body.appendChild(element("span", "cml-reminder-detail", detail(reminder)));

        const actions = element("div", "cml-reminder-actions");
        const open = element("a", "cml-reminder-open", "Open routine");
        open.href = "routine.html?id=" + encodeURIComponent(Number(reminder.routineId));
        const dismiss = element("button", "cml-reminder-dismiss", "Dismiss");
        dismiss.type = "button";
        dismiss.addEventListener("click", function () {
            card.remove();
        });
        actions.appendChild(open);
        actions.appendChild(dismiss);
        body.appendChild(actions);

        card.appendChild(element("span", "cml-reminder-icon", "⏰"));
        card.appendChild(body);
        box.appendChild(card);

        while (box.children.length > MAX_SHOWN) {
            box.firstElementChild.remove();
        }
    }

    async function poll() {
        if (stopped || !token() || document.visibilityState !== "visible") {
            return;
        }

        try {
            const response = await fetch("/reminders/due", {method: "POST", headers: headers(false)});

            // Signed out or the session ended: other code on the page handles that; stop asking.
            if (response.status === 401 || response.status === 403) {
                stop();
                return;
            }

            if (!response.ok) {
                return;
            }

            (await response.json()).forEach(show);
        } catch (error) {
            // Offline for a moment: the next minute tries again.
        }
    }

    function stop() {
        stopped = true;
        clearInterval(timer);
    }

    function start() {
        if (!token()) {
            return;
        }

        const link = document.createElement("link");
        link.rel = "stylesheet";
        link.href = "reminders.css";
        document.head.appendChild(link);

        poll();
        timer = setInterval(poll, POLL_MS);
        document.addEventListener("visibilitychange", poll);
    }

    // ---- Profile -> Settings ----

    function setStatus(root, text, isError) {
        const status = root.querySelector("[data-reminder-status]");
        status.textContent = text;
        status.classList.toggle("error", !!isError);
    }

    function renderUpcoming(root, reminders, enabled) {
        const list = root.querySelector("[data-reminder-list]");
        list.textContent = "";

        if (reminders.length === 0) {
            list.appendChild(element("p", "cml-reminder-none", "Nothing with a time is left for today."));
            return;
        }

        reminders.forEach(function (reminder) {
            const row = element("div", "cml-reminder-row");
            row.appendChild(element("span", "cml-reminder-row-time", time(reminder)));
            const text = element("span", "cml-reminder-row-text");
            text.appendChild(element("strong", "", reminder.title));
            text.appendChild(element("span", "", detail(reminder)));
            row.appendChild(text);
            row.appendChild(element("span", "cml-reminder-row-state",
                !enabled ? "Off" : reminder.shown ? "Reminded" : "Will remind"));
            list.appendChild(row);
        });
    }

    async function loadSettings(root) {
        const toggle = root.querySelector("[data-reminder-enabled]");
        const lead = root.querySelector("[data-reminder-lead]");

        try {
            const [settingsResponse, upcomingResponse] = await Promise.all([
                fetch("/reminders/settings", {headers: headers(false)}),
                fetch("/reminders/upcoming", {headers: headers(false)})
            ]);

            if (!settingsResponse.ok || !upcomingResponse.ok) {
                throw new Error("Failed to load reminders");
            }

            const settings = await settingsResponse.json();

            lead.textContent = "";
            settings.leadOptions.forEach(function (minutes) {
                const option = element("option", "", minutes + " minutes before");
                option.value = String(minutes);
                lead.appendChild(option);
            });
            lead.value = String(settings.leadMinutes);
            toggle.checked = settings.enabled;
            lead.disabled = !settings.enabled;
            renderUpcoming(root, await upcomingResponse.json(), settings.enabled);
            setStatus(root, settings.enabled ? "Reminders are on." : "Reminders are off.", false);
        } catch (error) {
            setStatus(root, "Could not load your reminders. Try again later.", true);
        }
    }

    async function saveSettings(root) {
        const toggle = root.querySelector("[data-reminder-enabled]");
        const lead = root.querySelector("[data-reminder-lead]");

        try {
            const response = await fetch("/reminders/settings", {
                method: "PUT",
                headers: headers(true),
                body: JSON.stringify({enabled: toggle.checked, leadMinutes: Number(lead.value)})
            });

            if (!response.ok) {
                const body = await response.json().catch(() => ({}));
                throw new Error(body.message || "Could not save");
            }

            await loadSettings(root);
            setStatus(root, toggle.checked ? "Saved. Reminders are on." : "Saved. Reminders are off.", false);
        } catch (error) {
            setStatus(root, error.message || "Could not save", true);
        }
    }

    function initSettings() {
        const root = document.getElementById("reminderSettings");

        if (!root || !token()) {
            return;
        }

        root.querySelector("[data-reminder-enabled]").addEventListener("change", function () {
            saveSettings(root);
        });
        root.querySelector("[data-reminder-lead]").addEventListener("change", function () {
            saveSettings(root);
        });

        loadSettings(root);
    }

    // Profile calls refreshSettings when Settings is opened, so today's list is current.
    window.CopyMyLifeReminders = {
        poll: poll,
        refreshSettings: function () {
            const root = document.getElementById("reminderSettings");
            if (root && token()) {
                loadSettings(root);
            }
        }
    };

    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", function () {
            start();
            initSettings();
        });
    } else {
        start();
        initSettings();
    }
})();
