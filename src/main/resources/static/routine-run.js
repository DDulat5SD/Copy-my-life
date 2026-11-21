const token =
    localStorage.getItem("token");

const ROUTINE_ID =
    Number(
        new URLSearchParams(
            location.search
        ).get("id")
    );

const $ =
    id =>
        document.getElementById(id);

let routine = null;
let run = null;

let timerId = null;
let serverOffsetMs = 0;
let busy = false;

const STATUS_LABELS = {
    COMPLETED: "Completed",
    SKIPPED: "Skipped",
    POSTPONED: "Postponed",
    IN_PROGRESS: "In progress",
    READY: "Up next",
    NOT_FINISHED: "Not finished",
    NOT_STARTED: "Not started"
};

if (!token) {
    location.href = "login.html";
}

if (!ROUTINE_ID) {
    location.href = "profile.html";
}

function escapeHTML(value) {
    return SafeDom.escapeHtml(value);
}

function initials(name) {

    return (name || "U")
        .split(" ")
        .map(part => part[0])
        .join("")
        .slice(0, 2)
        .toUpperCase();
}

function formatDuration(minutes) {

    const h =
        Math.floor(
            minutes / 60
        );

    const m =
        minutes % 60;

    return h
        ? `${h}h${m ? " " + m + "m" : ""}`
        : `${m} min`;
}

function formatClock(iso) {

    if (!iso) {
        return "Not started";
    }

    return new Date(iso)
        .toLocaleTimeString(
            "en-US",
            {
                hour: "numeric",
                minute: "2-digit"
            }
        );
}

function formatCountdown(seconds) {

    const value =
        Math.max(
            0,
            Math.ceil(seconds)
        );

    return String(
            Math.floor(
                value / 60
            )
        )
            .padStart(2, "0")
        +
        ":"
        +
        String(
            value % 60
        )
            .padStart(2, "0");
}

function serverNow() {

    return Date.now()
        + serverOffsetMs;
}

// The server files each run under the user's own calendar day.
function timeZone() {

    try {
        return Intl.DateTimeFormat()
            .resolvedOptions()
            .timeZone || "";
    } catch (error) {
        return "";
    }
}

async function request(
    url,
    options = {}
) {

    const response =
        await fetch(
            url,
            {
                ...options,

                headers: {
                    "Authorization":
                        "Bearer " + token,

                    "X-Time-Zone":
                        timeZone(),

                    ...(
                        options.body
                            ? {
                                "Content-Type":
                                    "application/json"
                            }
                            : {}
                    ),

                    ...(options.headers || {})
                }
            }
        );

    if (!response.ok) {

        let message =
            "HTTP "
            + response.status;

        try {

            const body =
                await response.json();

            if (body.message) {
                message =
                    body.message;
            }

        } catch (error) {
        }

        const failure = new Error(message);
        failure.status = response.status;
        throw failure;
    }

    return response.status === 204
        ? null
        : response.json();
}

async function loadPage() {

    const [
        routineData,
        habits,
        user,
        runData
    ] =
        await Promise.all([

            request(
                "/routines/"
                + ROUTINE_ID
            ),

            request(
                "/routines/"
                + ROUTINE_ID
                + "/routine-habits"
            ),

            request(
                "/users/me"
            ),

            request(
                "/routine-runs/"
                + ROUTINE_ID
            )

        ]);

    if (!runData) {

        location.href =
            "routine.html?id="
            + ROUTINE_ID;

        return;
    }

    routine = {
        ...routineData,
        habits
    };

    run =
        runData;

    syncServerTime();

    renderUser(user);
    renderRoutine();
    renderRun();
    startTimer();
}

function syncServerTime() {

    serverOffsetMs =
        run
        &&
        run.serverTime
            ? new Date(
                run.serverTime
            ).getTime()
            - Date.now()
            : 0;
}

function renderUser(user) {

    $("currentUserName")
        .textContent =
        user.name || "User";

    if (SafeDom.safeImageUrl(user.avatarUrl)) {

        $("headerAvatar").src =
            SafeDom.safeImageUrl(user.avatarUrl);

        $("headerAvatar").style.display =
            "block";

        $("headerAvatarFallback")
            .style.display =
            "none";

    } else {

        $("headerAvatar").style.display =
            "none";

        $("headerAvatarFallback")
            .style.display =
            "flex";

        $("headerAvatarFallback")
            .textContent =
            initials(
                user.name
            )[0];
    }
}

function renderRoutine() {

    document.title =
        routine.title
        + " — Routine Progress";

    $("routineTag")
        .textContent =
        (routine.category || "Routine")
        + " routine";

    $("routineTitle")
        .textContent =
        routine.title;

    $("routineDesc")
        .textContent =
        routine.description || "";

    $("routineCategory")
        .textContent =
        routine.category || "Routine";

    // Only the habits planned for the run's day are in it; paused and other days' habits sit out.
    const activeHabits =
        Number(run.totalCount || 0);

    $("habitsCount")
        .textContent =
        activeHabits
        +
        " habit"
        +
        (
            activeHabits === 1
                ? ""
                : "s"
        );

    $("routineTotal")
        .textContent =
        "Total "
        +
        formatDuration(
            Number(
                routine.durationMinutes
                || 0
            )
        );

    $("authorName")
        .textContent =
        routine.authorUsername
            ? "@"
            + routine.authorUsername
            : routine.authorName
            || "User";

    if (SafeDom.safeImageUrl(routine.authorAvatarUrl)) {

        $("authorAvatar")
            .textContent =
            "";

        $("authorAvatar")
            .style.backgroundImage =
            SafeDom.cssImageUrl(routine.authorAvatarUrl);

        $("authorAvatar")
            .style.backgroundSize =
            "cover";

        $("authorAvatar")
            .style.backgroundPosition =
            "center";

    } else {

        $("authorAvatar")
            .textContent =
            initials(
                routine.authorName
                || "User"
            )[0];
    }

    if (SafeDom.safeImageUrl(routine.imageUrl)) {

        $("routineImage")
            .style.backgroundImage =
            SafeDom.cssImageUrl(routine.imageUrl);
    }
}

function renderRun() {

    const done =
        Number(run.completedCount || 0);

    const total =
        Number(run.totalCount || 0);

    $("progressCount")
        .textContent =
        `${done} / ${total} completed`;

    $("progressBar")
        .style.width =
        Number(
            run.progressPercent
            || 0
        )
        + "%";

    $("progressSummary")
        .textContent =
        [
            run.skippedCount
                ? `${Number(run.skippedCount)} skipped`
                : "",
            run.postponedCount
                ? `${Number(run.postponedCount)} postponed`
                : ""
        ]
            .filter(Boolean)
            .join(" · ");

    const onlyPostponedLeft =
        run.postponedCount
        && run.habits.every(habit =>
            !habit.canStart
            && habit.status !== "IN_PROGRESS"
        );

    const headings = {
        RUNNING: [
            "Routine in progress",
            onlyPostponedLeft
                ? "Resume or skip your postponed habits to finish."
                : `${done} of ${total} habits completed. Keep going!`
        ],
        COMPLETED: [
            "Routine completed",
            `${done} of ${total} habits completed. Great job!`
        ],
        FINISHED: [
            "Routine finished",
            `${done} of ${total} habits completed, ${Number(run.skippedCount)} skipped.`
        ],
        STOPPED: [
            "Routine stopped",
            `You stopped this routine. ${done} of ${total} habits were completed and stay saved.`
        ],
        EXPIRED: [
            "This run has ended",
            `This run was for ${run.date}. Start the routine again to track today.`
        ]
    };

    const [title, text] =
        headings[run.status]
        || headings.RUNNING;

    $("progressTitle")
        .textContent =
        title;

    $("progressText")
        .textContent =
        text;

    const running =
        run.status === "RUNNING";

    if (!running) {
        $("stopConfirm").hidden =
            true;
    }

    $("stopRoutineBtn").hidden =
        !running
        || !$("stopConfirm").hidden;

    // A page cached from before the routine assistant has no such link; the run still works.
    const summaryLink =
        $("runSummaryLink");

    if (summaryLink) {
        summaryLink.hidden =
            running;
    }

    $("runHabits")
        .innerHTML =
        run.habits.length
            ? run.habits
                .map(renderHabit)
                .join("")
            : `<li class="run-empty">Nothing is planned for this day in this routine.</li>`;

    updateTimerButtons();
}

function actionButton(
    habit,
    action,
    label,
    enabled,
    style = ""
) {

    return `
      <button
        type="button"
        class="run-action-btn ${style}"
        data-action="${action}"
        data-routine-habit-id="${Number(habit.routineHabitId)}"
        ${enabled && !busy ? "" : "disabled"}>
        ${label}
      </button>
    `;
}

function renderActions(habit) {

    let primary = "";

    if (habit.status === "IN_PROGRESS") {

        primary =
            `
      <button
        type="button"
        class="run-action-btn complete-habit-btn"
        data-action="complete"
        data-routine-habit-id="${Number(habit.routineHabitId)}"
        data-can-complete-at="${escapeHTML(habit.canCompleteAt || "")}"
        disabled>

        Complete in ${formatCountdown(habit.remainingSeconds || 0)}

      </button>
    `;

    } else if (habit.status === "READY") {

        primary =
            actionButton(habit, "start", "Start", true);

    } else if (habit.status === "POSTPONED" && run.status === "RUNNING") {

        primary =
            actionButton(habit, "start", "Resume", habit.canResume);

    } else if (habit.status === "LOCKED") {

        primary =
            actionButton(habit, "start", "Start", false);
    }

    const secondary = [];

    if (habit.canPostpone) {
        secondary.push(
            actionButton(habit, "postpone", "Postpone", true, "run-action-btn--ghost")
        );
    }

    if (habit.canSkip) {
        secondary.push(
            actionButton(habit, "skip", "Skip", true, "run-action-btn--ghost")
        );
    }

    return primary
        + (
            secondary.length
                ? `<div class="run-habit__secondary">${secondary.join("")}</div>`
                : ""
        );
}

function habitTime(habit) {

    if (habit.status === "COMPLETED" && habit.startedAt) {
        return `${formatClock(habit.startedAt)} – ${formatClock(habit.completedAt)}`;
    }

    if (habit.status === "IN_PROGRESS") {
        return `${formatClock(habit.startedAt)} – ${formatClock(habit.canCompleteAt)}`;
    }

    if (habit.status === "NOT_FINISHED" && habit.startedAt) {
        return `Started ${formatClock(habit.startedAt)}`;
    }

    if (habit.status === "SKIPPED") {
        return "Skipped";
    }

    if (habit.status === "POSTPONED") {
        return "Saved for later";
    }

    return habit.startTime
        ? `Planned ${escapeHTML(habit.startTime)}`
        : "Not started";
}

function renderHabit(habit) {

    // Only known statuses reach the class name and the data attribute.
    const status =
        Object.hasOwn(STATUS_LABELS, habit.status)
            ? habit.status
            : "LOCKED";

    const label =
        STATUS_LABELS[status];

    return `
    <li
      class="run-habit run-habit--${status.toLowerCase()}"
      data-status="${status}">

      <span class="run-habit__icon">
        ${
        status === "COMPLETED"
            ? "✓"
            : Number(habit.position)
    }
      </span>

      <div class="run-habit__main">

        <div class="run-habit__title">
          ${escapeHTML(habit.title)}
        </div>

        <div class="run-habit__desc">
          ${escapeHTML(habit.description || "")}
        </div>

        ${
        label
            ? `<span class="run-status-badge run-status-badge--${status.toLowerCase()}">${label}</span>`
            : ""
    }

      </div>

      <div class="run-habit__time">
        ${habitTime(habit)}
      </div>

      <div class="run-habit__duration">
        ${Number(habit.durationMinutes) || 0} min
      </div>

      <div class="run-habit__action">
        ${renderActions(habit)}
      </div>

    </li>
  `;
}

function showMessage(text) {

    $("runMessage")
        .textContent =
        text || "";

    $("runMessage").hidden =
        !text;
}

function explain(error) {

    if (!error.status) {
        return "We couldn't reach the server. Check your connection and try again.";
    }

    if (error.status === 401 || error.status === 403) {
        return "Your session has ended. Please sign in again.";
    }

    if (error.status >= 500) {
        return "We couldn't save that change. Nothing was lost, please try again.";
    }

    return error.message;
}

// While a change is being saved every button waits, so a second press cannot overtake the first.
// renderRun() draws the habit buttons again once the answer arrives.
function setBusy(value) {

    busy =
        value;

    document
        .querySelectorAll(
            value
                ? "#runHabits button, .run-controls button"
                : ".run-controls button"
        )
        .forEach(button => {
            button.disabled =
                value;
        });

    $("runHabits")
        .setAttribute(
            "aria-busy",
            value ? "true" : "false"
        );
}

async function updateRun(url) {

    if (busy) {
        return;
    }

    setBusy(true);

    try {

        run =
            await request(
                url,
                {
                    method: "POST"
                }
            );

        showMessage("");

    } catch (error) {

        showMessage(
            explain(error)
        );

        // The run may have changed elsewhere, for example in another tab.
        if (error.status === 400) {
            await refreshRun();
        }
    }

    setBusy(false);
    syncServerTime();
    renderRun();
}

async function refreshRun() {

    try {

        const latest =
            await request(
                "/routine-runs/"
                + ROUTINE_ID
            );

        if (latest) {
            run = latest;
        }

    } catch (error) {
    }
}

function updateTimerButtons() {

    document
        .querySelectorAll(
            ".complete-habit-btn"
        )
        .forEach(button => {

            const target =
                new Date(
                    button.dataset.canCompleteAt
                )
                    .getTime();

            const seconds =
                Math.max(
                    0,
                    Math.ceil(
                        (
                            target
                            - serverNow()
                        )
                        / 1000
                    )
                );

            button.disabled =
                seconds > 0
                || busy;

            button.textContent =
                seconds > 0
                    ? "Complete in "
                    + formatCountdown(
                        seconds
                    )
                    : "Complete";
        });
}

function startTimer() {

    clearInterval(
        timerId
    );

    updateTimerButtons();

    timerId =
        setInterval(
            updateTimerButtons,
            1000
        );
}

$("runHabits")
    .addEventListener(
        "click",
        event => {

            const button =
                event.target.closest(
                    "button[data-action]"
                );

            if (!button || button.disabled) {
                return;
            }

            updateRun(
                `/routine-runs/${ROUTINE_ID}/routine-habits/${Number(button.dataset.routineHabitId)}/${button.dataset.action}`
            );
        }
    );

$("stopRoutineBtn")
    .addEventListener(
        "click",
        () => {

            $("stopRoutineBtn").hidden =
                true;

            $("stopConfirm").hidden =
                false;

            $("stopCancelBtn")
                .focus();
        }
    );

$("stopCancelBtn")
    .addEventListener(
        "click",
        () => {

            $("stopConfirm").hidden =
                true;

            $("stopRoutineBtn").hidden =
                run.status !== "RUNNING";

            $("stopRoutineBtn")
                .focus();
        }
    );

$("stopConfirmBtn")
    .addEventListener(
        "click",
        () => {

            $("stopConfirm").hidden =
                true;

            updateRun(
                `/routine-runs/${ROUTINE_ID}/stop`
            );
        }
    );

$("backBtn")
    .addEventListener(
        "click",
        () => {

            location.href =
                "routine.html?id="
                + ROUTINE_ID;
        }
    );

$("userMenuToggle")
    .addEventListener(
        "click",
        event => {

            event.stopPropagation();

            $("userDropdown")
                .classList
                .toggle("show");
        }
    );

document
    .addEventListener(
        "click",
        event => {

            if (
                !event.target.closest(
                    ".topbar-user-wrap"
                )
            ) {

                $("userDropdown")
                    .classList
                    .remove("show");
            }
        }
    );

$("logoutBtn")
    .addEventListener(
        "click",
        () => {

            CopyMyLifeSession.signOut();
        }
    );

document
    .addEventListener(
        "DOMContentLoaded",
        () => {

            loadPage()
                .catch(error => {

                    console.error(
                        error
                    );

                    // Only the owner can run a routine; the details page explains anything else.
                    if (error.status === 400 || error.status === 404) {
                        location.replace(
                            "routine.html?id="
                            + ROUTINE_ID
                        );
                        return;
                    }

                    showMessage(
                        explain(error)
                    );
                });
        }
    );