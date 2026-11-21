// The profile's AI Assistant section: generate a plan, review a routine, find similar people
// (lifestyle-matches.js), summarise a day, compare planned and measured times, and keep
// preferences. mount() draws into its root once and wires every listener once; mounting again
// returns the same app, whose open(tab) shows a tab. One assistant per page. Every value from the
// server is written with textContent; nothing is saved or changed unless the user presses Save or Apply.
(function () {
  let token = null;
  let onTab = () => {};
  const zone = (Intl.DateTimeFormat().resolvedOptions().timeZone) || "";
  const DAYS = ["MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN"];
  const DAY_NAMES = {MON: "Mon", TUE: "Tue", WED: "Wed", THU: "Thu", FRI: "Fri", SAT: "Sat", SUN: "Sun"};
  const KINDS = [["STUDY", "Study"], ["WORK", "Work"], ["EXERCISE", "Exercise"], ["PERSONAL", "Personal"],
    ["MEAL", "Meal"], ["BREAK", "Break"], ["OTHER", "Other"]];
  const PRIORITIES = [["HIGH", "High"], ["MEDIUM", "Medium"], ["LOW", "Low"]];
  const SOURCES = {REQUEST: "This plan", SAVED: "Saved by you", HISTORY: "From your runs", DEFAULT: "Default"};
  const BASIS = {MEASURED: "your measured runs", PREFERENCE: "your preferences", RULE: "a scheduling rule of thumb",
    MODEL: "the multilingual language model"};
  const SEVERITY = {ERROR: "Problem", WARNING: "Worth changing", INFO: "Note"};
  const STATUS = {COMPLETED: "Completed", SKIPPED: "Skipped", POSTPONED: "Postponed", IN_PROGRESS: "In progress",
    NOT_FINISHED: "Not finished", NOT_STARTED: "Not started"};
  const INSIGHT = {NO_DATA: "No measured time", NOT_ENOUGH_DATA: "Not enough runs yet", ON_PLAN: "As planned",
    OFTEN_LONGER: "Usually takes longer", OFTEN_SHORTER: "Usually takes less", KEPT_BY_YOU: "You kept the plan"};

  let $ = (id) => document.getElementById(id);
  let lastPlan = null;
  let lastReview = null;

  function el(tag, className, text) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text !== undefined && text !== null) node.textContent = text;
    return node;
  }

  function option(value, label) {
    const node = el("option", null, label);
    node.value = value;
    return node;
  }

  function minutesText(minutes) {
    const value = Number(minutes) || 0;
    if (value < 60) return value + " min";
    return Math.floor(value / 60) + " h" + (value % 60 ? " " + (value % 60) + " min" : "");
  }

  function show(node, visible) {
    node.hidden = !visible;
  }

  function message(node, text, kind) {
    node.textContent = text || "";
    node.className = "ra-message" + (kind ? " ra-message--" + kind : "");
    node.hidden = !text;
  }

  async function api(method, path, body) {
    const headers = {"Authorization": "Bearer " + token, "X-Time-Zone": zone};
    if (body !== undefined) headers["Content-Type"] = "application/json";
    let response;
    try {
      response = await fetch(path, {method, headers, cache: "no-store",
        body: body === undefined ? undefined : JSON.stringify(body)});
    } catch (error) {
      throw new Error("Could not reach CopyMyLife. Check your connection and try again.");
    }
    const data = await response.json().catch(() => null);
    if (response.status === 401 || (response.status === 403 && !(data && data.message))) {
      window.CopyMyLifeSession.sessionEnded();
      throw new Error("Your session has ended. Please sign in again.");
    }
    if (!response.ok) {
      throw new Error((data && data.message) || "Something went wrong. Please try again.");
    }
    return data;
  }

  // ---- tabs ----

  const TABS = ["generate", "review", "matches", "summary", "suggestions", "preferences"];
  const loaded = {};
  let current = null;
  let pendingReview = null;
  let routinesReady = Promise.resolve();

  function openTab(name) {
    const tab = TABS.includes(name) ? name : "generate";
    current = tab;
    TABS.forEach((other) => {
      const button = $("tab-" + other);
      button.setAttribute("aria-selected", String(other === tab));
      button.classList.toggle("ra-tab--active", other === tab);
      $("panel-" + other).hidden = other !== tab;
    });
    if (tab === "matches" && !loaded.matches) {
      loaded.matches = true;
      window.CopyMyLifeMatches.mount($("lifestyleMatches"), {token});
    }
    if (tab === "review" && pendingReview) {
      routinesReady.then(() => {
        if (pendingReview && current === "review") {
          pendingReview = null;
          review();
        }
      });
    }
    if (tab === "suggestions" && !loaded.durations) {
      loaded.durations = true;
      loadDurations();
    }
    if (tab === "summary" && !loaded.summary) {
      loaded.summary = true;
      loadSummary();
    }
  }

  // ---- shared form rows ----

  function timeInput(value, label) {
    const input = el("input", "ra-input");
    input.type = "time";
    input.value = value || "";
    input.setAttribute("aria-label", label);
    return input;
  }

  function removeButton(row, label) {
    const button = el("button", "ra-btn ra-btn--ghost ra-btn--small", "Remove");
    button.type = "button";
    button.setAttribute("aria-label", label);
    button.addEventListener("click", () => row.remove());
    return button;
  }

  function commitmentRow(list, commitment) {
    const row = el("div", "ra-item ra-item--commitment");
    const title = el("input", "ra-input");
    title.maxLength = 100;
    title.placeholder = "e.g. University";
    title.value = (commitment && commitment.title) || "";
    title.setAttribute("aria-label", "Commitment name");
    title.dataset.field = "title";
    const start = timeInput(commitment && commitment.start, "Commitment start");
    start.dataset.field = "start";
    const end = timeInput(commitment && commitment.end, "Commitment end");
    end.dataset.field = "end";
    row.append(title, start, end, removeButton(row, "Remove commitment"));
    list.append(row);
  }

  function readCommitments(list) {
    return [...list.querySelectorAll(".ra-item")].map((row) => ({
      title: row.querySelector("[data-field=title]").value.trim(),
      start: row.querySelector("[data-field=start]").value || null,
      end: row.querySelector("[data-field=end]").value || null
    })).filter((item) => item.title || item.start || item.end);
  }

  function select(choices, value, label) {
    const node = el("select", "ra-input");
    choices.forEach(([key, text]) => node.append(option(key, text)));
    node.value = value;
    node.setAttribute("aria-label", label);
    return node;
  }

  function habitRow(habit) {
    const row = el("div", "ra-item ra-item--habit");
    const title = el("input", "ra-input");
    title.maxLength = 100;
    title.placeholder = "e.g. Java Study";
    title.value = (habit && habit.title) || "";
    title.setAttribute("aria-label", "Activity name");
    title.dataset.field = "title";
    const minutes = el("input", "ra-input");
    minutes.type = "number";
    minutes.min = "5";
    minutes.max = "720";
    minutes.value = (habit && habit.minutes) || 30;
    minutes.setAttribute("aria-label", "Minutes");
    minutes.dataset.field = "minutes";
    const kind = select(KINDS, (habit && habit.kind) || "OTHER", "Kind");
    kind.dataset.field = "kind";
    const priority = select(PRIORITIES, (habit && habit.priority) || "MEDIUM", "Priority");
    priority.dataset.field = "priority";
    const time = timeInput(habit && habit.preferredTime, "Preferred time (optional)");
    time.dataset.field = "time";
    row.append(title, minutes, kind, priority, time, removeButton(row, "Remove activity"));
    $("gHabits").append(row);
  }

  function readHabits() {
    return [...$("gHabits").querySelectorAll(".ra-item")].map((row) => ({
      title: row.querySelector("[data-field=title]").value.trim(),
      minutes: Number(row.querySelector("[data-field=minutes]").value),
      kind: row.querySelector("[data-field=kind]").value,
      priority: row.querySelector("[data-field=priority]").value,
      preferredTime: row.querySelector("[data-field=time]").value || null
    })).filter((habit) => habit.title);
  }

  function value(id) {
    const node = $(id);
    return node.value === "" ? null : node.value;
  }

  function number(id) {
    const node = $(id);
    return node.value === "" ? null : Number(node.value);
  }

  // ---- generate ----

  async function quickFill() {
    const button = $("raQuickFill");
    button.disabled = true;
    try {
      const parsed = await api("POST", "/assistant/parse", {text: $("raQuickText").value});
      const prefs = parsed.preferences;
      [["wake", "gWake"], ["sleep", "gSleep"], ["breakfast", "gBreakfast"], ["lunch", "gLunch"], ["dinner", "gDinner"]]
          .forEach(([key, id]) => { if (prefs[key]) $(id).value = prefs[key]; });
      if (prefs.restFrequency) $("gRest").value = prefs.restFrequency;
      if (parsed.habits.length) {
        $("gHabits").replaceChildren();
        parsed.habits.forEach(habitRow);
      }
      if (parsed.commitments.length) {
        $("gCommitments").replaceChildren();
        parsed.commitments.forEach((item) => commitmentRow($("gCommitments"), item));
      }
      const result = $("raQuickResult");
      result.textContent = parsed.recognised.length
          ? "Recognised: " + parsed.recognised.join("; ") + ". " + parsed.note
          : "Nothing was recognised. Fill in the form below instead.";
      result.hidden = false;
    } catch (error) {
      const result = $("raQuickResult");
      result.textContent = error.message;
      result.hidden = false;
    } finally {
      button.disabled = false;
    }
  }

  function planRequest() {
    const meals = [["gMealBreakfast", "breakfast"], ["gMealLunch", "lunch"], ["gMealDinner", "dinner"]]
        .filter(([id]) => $(id).checked).map(([, meal]) => meal);
    return {
      preferences: {
        wake: value("gWake"), sleep: value("gSleep"), availableFrom: value("gFrom"), availableTo: value("gTo"),
        breakfast: value("gBreakfast"), lunch: value("gLunch"), dinner: value("gDinner"),
        exercise: value("gExercise"), restFrequency: value("gRest"), intensity: value("gIntensity")
      },
      commitments: readCommitments($("gCommitments")),
      habits: readHabits(),
      meals,
      splitFocus: $("gSplit").checked,
      days: DAYS.filter((day) => $("gDay" + day).checked)
    };
  }

  async function generate() {
    const button = $("raGenerate");
    button.disabled = true;
    show($("genResult"), false);
    show($("genError"), false);
    show($("genLoading"), true);
    try {
      lastPlan = await api("POST", "/assistant/plans", planRequest());
      renderPlan(lastPlan);
      show($("genResult"), true);
    } catch (error) {
      $("genError").replaceChildren(el("h2", null, "The plan could not be made"), el("p", null, error.message));
      show($("genError"), true);
    } finally {
      show($("genLoading"), false);
      button.disabled = false;
    }
  }

  function renderPlan(plan) {
    $("genNote").textContent = plan.note + " Planned: " + minutesText(plan.plannedMinutes)
        + (plan.days.length && plan.days.length < 7 ? " on " + plan.days.map((d) => DAY_NAMES[d]).join(", ") : " every day") + ".";
    $("genWarnings").replaceChildren(...plan.warnings.map((text) => el("li", null, text)));

    $("genTimeline").replaceChildren(...plan.activities.map((activity) => {
      const item = el("li", "ra-slot ra-slot--" + activity.kind.toLowerCase());
      item.dataset.fixed = String(activity.fixed);
      const keep = el("input");
      keep.type = "checkbox";
      keep.checked = activity.save;
      keep.disabled = activity.fixed;
      keep.dataset.field = "keep";
      keep.setAttribute("aria-label", activity.fixed ? "Commitments are not saved" : "Keep " + activity.title);
      const start = timeInput(activity.start, "Start of " + activity.title);
      start.dataset.field = "start";
      start.disabled = activity.fixed;
      const minutes = el("input", "ra-input ra-input--short");
      minutes.type = "number";
      minutes.min = "1";
      minutes.max = "1440";
      minutes.value = activity.minutes;
      minutes.disabled = activity.fixed;
      minutes.dataset.field = "minutes";
      minutes.setAttribute("aria-label", "Minutes of " + activity.title);
      const title = el("input", "ra-input");
      title.value = activity.title;
      title.maxLength = 255;
      title.disabled = activity.fixed;
      title.dataset.field = "title";
      title.setAttribute("aria-label", "Name");

      const head = el("div", "ra-slot__head");
      head.append(keep, el("span", "ra-slot__time", activity.start + "–" + activity.end + (activity.endsNextDay ? " (+1 day)" : "")),
          el("span", "ra-kind", activity.fixed ? "Fixed commitment" : (KINDS.find(([k]) => k === activity.kind) || [0, activity.kind])[1]));
      const edit = el("div", "ra-slot__edit");
      edit.append(title, start, minutes, el("span", "ra-muted", "min"));
      item.append(head, edit, el("p", "ra-slot__reason", activity.reason));
      return item;
    }));

    $("genUnscheduled").replaceChildren(...plan.unscheduled.map((item) =>
        el("li", null, item.title + " (" + minutesText(item.minutes) + "): " + item.reason)));
    show($("genUnscheduledBox"), plan.unscheduled.length > 0);
    $("genWhy").replaceChildren(...plan.explanations.map((text) => el("li", null, text)));

    const category = $("saveCategory");
    category.replaceChildren(...plan.categories.map((name) => option(name, name)));
    if (plan.suggestedCategory) category.value = plan.suggestedCategory;
    renderExamples(plan.examples);
    message($("saveResult"), "");
  }

  function renderExamples(examples) {
    $("exNote").textContent = examples.note;
    const text = $("exMessage");
    text.textContent = examples.message || "";
    text.hidden = !examples.message;
    $("exList").dataset.status = examples.status;
    $("exList").replaceChildren(...examples.items.map((example) => {
      const card = el("li", "ra-example");
      const link = el("a", "ra-example__title", example.title);
      link.href = "routine.html?id=" + encodeURIComponent(example.routineId);
      const meta = [example.category, "by " + example.authorName,
        example.ratingCount ? Number(example.averageRating).toFixed(1) + " ★ (" + example.ratingCount + ")" : "No ratings",
        "closeness " + Number(example.similarity).toFixed(2)].filter(Boolean).join(" · ");
      const chips = el("ul", "ra-chips");
      example.activities.forEach((activity) => {
        const chip = el("li", "ra-chip");
        const add = el("button", "ra-chip__add", "+ " + activity.title + (activity.minutes ? " · " + activity.minutes + " min" : ""));
        add.type = "button";
        add.setAttribute("aria-label", "Add " + activity.title + " to my activities");
        add.addEventListener("click", () => {
          habitRow({title: activity.title, minutes: activity.minutes || 30, kind: "OTHER"});
          add.disabled = true;
          add.textContent = "Added: " + activity.title + " — generate again to plan it";
        });
        chip.append(add);
        chips.append(chip);
      });
      card.append(link, el("p", "ra-muted", meta), chips);
      return card;
    }));
  }

  async function savePlan() {
    const button = $("raSave");
    const rows = [...$("genTimeline").querySelectorAll(".ra-slot")]
        .filter((row) => row.dataset.fixed !== "true" && row.querySelector("[data-field=keep]").checked);
    const activities = rows.map((row) => ({
      title: row.querySelector("[data-field=title]").value.trim(),
      minutes: Number(row.querySelector("[data-field=minutes]").value),
      startTime: row.querySelector("[data-field=start]").value || null
    }));
    button.disabled = true;
    try {
      const saved = await api("POST", "/assistant/plans/save", {
        title: $("saveTitle").value, category: $("saveCategory").value, isPublic: $("savePublic").checked,
        days: lastPlan ? lastPlan.days : [], activities
      });
      const result = $("saveResult");
      message(result, "Saved “" + saved.title + "” with " + saved.activities + " activities (" + minutesText(saved.durationMinutes) + "). ", "ok");
      const open = el("a", null, "Open the routine");
      open.href = "routine.html?id=" + encodeURIComponent(saved.routineId);
      result.append(open);
      loadRoutines();
    } catch (error) {
      message($("saveResult"), error.message, "error");
    } finally {
      button.disabled = false;
    }
  }

  // ---- preferences ----

  const PREF_FIELDS = [["wake", "pWake"], ["sleep", "pSleep"], ["availableFrom", "pFrom"], ["availableTo", "pTo"],
    ["breakfast", "pBreakfast"], ["lunch", "pLunch"], ["dinner", "pDinner"], ["mealMinutes", "pMeal"],
    ["exercise", "pExercise"], ["focusBlockMinutes", "pFocus"], ["restFrequency", "pRest"],
    ["breakEveryMinutes", "pBreakEvery"], ["breakMinutes", "pBreakLength"], ["intensity", "pIntensity"]];

  function renderPreferences(data) {
    const saved = data.saved || {};
    PREF_FIELDS.forEach(([key, id]) => { $(id).value = saved[key] == null ? "" : saved[key]; });
    $("pCommitments").replaceChildren();
    (saved.commitments || []).forEach((item) => commitmentRow($("pCommitments"), item));
    $("gCommitments").replaceChildren();
    (saved.commitments || []).forEach((item) => commitmentRow($("gCommitments"), item));
    $("pMinRuns").textContent = data.minObservations;

    $("prefsResolved").replaceChildren(...data.resolved.map((line) => {
      const item = el("li", "ra-source");
      item.append(el("span", "ra-source__label", line.label), el("strong", null, line.value),
          el("span", "ra-badge ra-badge--" + line.source.toLowerCase(), SOURCES[line.source] || line.source));
      if (line.detail) item.append(el("span", "ra-muted", line.detail));
      return item;
    }));

    const seen = Object.entries(data.history);
    $("prefsHistory").replaceChildren(...(seen.length ? seen.map(([key, signal]) => el("li", null,
        key.charAt(0).toUpperCase() + key.slice(1) + ": usually around " + signal.time + " (" + signal.observations
        + " completed runs" + (signal.used ? ", used when you have no saved time)" : ", not enough yet to be used)")))
        : [el("li", "ra-muted", "No meal or exercise habits tracked yet. Complete them in a routine run and they appear here.")]));

    // The generator shows what will be used when its fields stay empty.
    const used = Object.fromEntries(data.resolved.map((line) => [line.key, line.value]));
    [["gWake", "wake"], ["gSleep", "sleep"], ["gBreakfast", "breakfast"], ["gLunch", "lunch"], ["gDinner", "dinner"]]
        .forEach(([id, key]) => { $(id).title = "Empty: " + used[key]; });
  }

  async function loadPreferences() {
    try {
      renderPreferences(await api("GET", "/assistant/preferences"));
    } catch (error) {
      message($("prefsMessage"), error.message, "error");
    }
  }

  async function savePreferences() {
    const button = $("raSavePrefs");
    const body = {};
    PREF_FIELDS.forEach(([key, id]) => {
      const node = $(id);
      body[key] = node.value === "" ? null : (node.type === "number" ? Number(node.value) : node.value);
    });
    body.commitments = readCommitments($("pCommitments"));
    button.disabled = true;
    try {
      renderPreferences(await api("PUT", "/assistant/preferences", body));
      message($("prefsMessage"), "Preferences saved. New plans and reviews use them from now on.", "ok");
    } catch (error) {
      message($("prefsMessage"), error.message, "error");
    } finally {
      button.disabled = false;
    }
  }

  // ---- review ----

  async function loadRoutines() {
    try {
      const routines = await fetch("/routines", {headers: {"Authorization": "Bearer " + token}, cache: "no-store"})
          .then((response) => response.ok ? response.json() : []);
      const list = $("reviewRoutine");
      const chosen = list.value || new URLSearchParams(location.search).get("review");
      list.replaceChildren(...routines.map((routine) => option(routine.id, routine.title)));
      if (chosen && routines.some((routine) => String(routine.id) === String(chosen))) list.value = chosen;
      show($("reviewNoRoutines"), routines.length === 0);
      $("raReview").disabled = routines.length === 0;
    } catch (error) {
      show($("reviewNoRoutines"), true);
    }
  }

  function timeline(list, activities) {
    list.replaceChildren(...activities.map((activity) => {
      const item = el("li", "ra-slot ra-slot--" + activity.kind.toLowerCase() + (activity.isNew ? " ra-slot--new" : ""));
      const head = el("div", "ra-slot__head");
      head.append(el("span", "ra-slot__time", activity.start ? activity.start + "–" + activity.end : "No time"),
          el("strong", null, activity.title), el("span", "ra-muted", minutesText(activity.minutes)));
      if (activity.days && activity.days.length && activity.days.length < 7) {
        head.append(el("span", "ra-muted", activity.days.map((d) => DAY_NAMES[d]).join(", ")));
      }
      if (activity.isNew) head.append(el("span", "ra-badge ra-badge--history", "New"));
      item.append(head);
      return item;
    }));
  }

  async function review() {
    const id = $("reviewRoutine").value;
    if (!id) return;
    const button = $("raReview");
    button.disabled = true;
    show($("reviewResult"), false);
    show($("reviewError"), false);
    show($("previewBox"), false);
    show($("reviewLoading"), true);
    try {
      lastReview = await api("GET", "/assistant/routines/" + encodeURIComponent(id) + "/review");
      renderReview(lastReview);
      show($("reviewResult"), true);
    } catch (error) {
      $("reviewError").replaceChildren(el("h2", null, "The review did not work this time"), el("p", null, error.message));
      show($("reviewError"), true);
    } finally {
      show($("reviewLoading"), false);
      button.disabled = false;
    }
  }

  function renderReview(data) {
    $("reviewTitle").textContent = data.routine.title + " · " + minutesText(data.routine.durationMinutes);
    $("reviewNote").textContent = data.note;
    show($("reviewRunning"), data.running);
    const changes = Object.fromEntries(data.changes.map((change) => [change.id, change]));

    $("reviewIssues").replaceChildren(...data.issues.map((issue, index) => {
      const item = el("li", "ra-issue ra-issue--" + issue.severity.toLowerCase());
      item.dataset.code = issue.code;
      const head = el("div", "ra-issue__head");
      head.append(el("span", "ra-badge ra-badge--" + issue.severity.toLowerCase(), SEVERITY[issue.severity] || issue.severity),
          el("h3", null, issue.title));
      item.append(head, el("p", null, issue.evidence),
          el("p", "ra-muted", "Based on " + (BASIS[issue.basis] || issue.basis) + "."),
          el("p", "ra-issue__suggestion", "Suggestion: " + issue.suggestion));
      if (issue.impact) item.append(el("p", "ra-muted", "Effect: " + issue.impact));
      if (issue.changeIds.length) {
        const label = el("label", "ra-check");
        const approve = el("input");
        approve.type = "checkbox";
        approve.className = "ra-approve";
        approve.dataset.changes = issue.changeIds.join(",");
        approve.id = "approve-" + index;
        label.append(approve, document.createTextNode(" Approve this change ("
            + issue.changeIds.map((id) => describeChange(changes[id])).join("; ") + ")"));
        item.append(label);
      }
      return item;
    }));

    $("reviewChecked").replaceChildren(...data.checked.map((text) => el("li", null, "✓ " + text)));
    $("reviewNotChecked").replaceChildren(...data.notChecked.map((text) => el("li", null, "Not checked: " + text)));
    const anyChange = data.changes.length > 0;
    $("raPreview").disabled = !anyChange;
    $("raApply").disabled = !anyChange || data.running;
    message($("reviewMessage"), anyChange ? "" : "There is nothing to apply.");
  }

  function describeChange(change) {
    if (!change) return "";
    if (change.type === "ADD") return "add " + change.title + " at " + change.toStart;
    if (change.type === "RESIZE") return change.title + " " + change.fromMinutes + " → " + change.toMinutes + " min";
    return change.title + " " + (change.fromStart || "no time") + " → " + change.toStart;
  }

  function approvedChanges() {
    const byId = Object.fromEntries(lastReview.changes.map((change) => [change.id, change]));
    const ids = [...document.querySelectorAll(".ra-approve:checked")].flatMap((box) => box.dataset.changes.split(","));
    return ids.map((id) => byId[id]).filter(Boolean).map((change) => ({
      id: change.id, type: change.type, activityId: change.activityId, title: change.title,
      toStart: change.toStart, toMinutes: change.toMinutes, days: change.days, kind: change.kind
    }));
  }

  async function previewChanges() {
    const changes = approvedChanges();
    if (!changes.length) {
      message($("reviewMessage"), "Tick “Approve this change” on the suggestions you want first.", "warn");
      return;
    }
    try {
      const preview = await api("POST", "/assistant/routines/" + encodeURIComponent(lastReview.routine.id) + "/changes/preview", {changes});
      timeline($("previewTimeline"), preview.activities);
      $("previewNote").textContent = (preview.conflicts.length
          ? "These changes alone would make activities overlap: " + preview.conflicts.join("; ") + ". Approve the related changes together."
          : "No overlaps.") + " Routine length: " + minutesText(lastReview.routine.durationMinutes) + " → "
          + minutesText(preview.newDurationMinutes) + ".";
      show($("previewBox"), true);
      message($("reviewMessage"), "");
    } catch (error) {
      message($("reviewMessage"), error.message, "error");
    }
  }

  async function applyChanges() {
    const changes = approvedChanges();
    if (!changes.length) {
      message($("reviewMessage"), "Tick “Approve this change” on the suggestions you want first.", "warn");
      return;
    }
    const button = $("raApply");
    button.disabled = true;
    try {
      const applied = await api("POST", "/assistant/routines/" + encodeURIComponent(lastReview.routine.id) + "/changes/apply", {changes});
      await review();
      message($("reviewMessage"), "Applied " + applied.applied + (applied.applied === 1 ? " change" : " changes")
          + ". Routine length: " + minutesText(applied.previousDurationMinutes) + " → " + minutesText(applied.durationMinutes) + ".", "ok");
      loaded.durations = false;
    } catch (error) {
      message($("reviewMessage"), error.message, "error");
      button.disabled = false;
    }
  }

  // ---- durations ----

  async function loadDurations() {
    show($("durLoading"), true);
    show($("durError"), false);
    show($("durEmpty"), false);
    show($("durList"), false);
    try {
      const data = await api("GET", "/assistant/insights/durations");
      $("durNote").textContent = data.note + " A change is suggested only after at least " + data.minRuns
          + " measured runs, when most of them differ clearly from the plan. Measured so far: "
          + data.measuredActivities + " of " + data.activities + " activities.";
      if (!data.items.length) {
        show($("durEmpty"), true);
        return;
      }
      $("durList").replaceChildren(...data.items.map(durationCard));
      show($("durList"), true);
    } catch (error) {
      $("durError").replaceChildren(el("h2", null, "The comparison did not load"), el("p", null, error.message));
      show($("durError"), true);
    } finally {
      show($("durLoading"), false);
    }
  }

  function durationCard(item) {
    const insight = item.insight;
    const card = el("li", "ra-card ra-insight ra-insight--" + insight.status.toLowerCase());
    card.dataset.routineHabitId = item.routineHabitId;
    const head = el("div", "ra-issue__head");
    head.append(el("span", "ra-badge", INSIGHT[insight.status] || insight.status), el("h3", null, insight.title));
    card.append(head, el("p", "ra-muted", item.routineTitle + " · planned " + insight.plannedMinutes + " min"
        + (insight.usualMinutes != null ? " · measured about " + insight.usualMinutes + " min" : "")),
        el("p", null, insight.message));
    if (insight.evidence) card.append(el("p", "ra-muted", insight.evidence));
    const status = el("p", "ra-message", "");
    status.hidden = true;

    if (insight.suggestedMinutes != null && !item.sharedHabit) {
      const actions = el("div", "ra-row");
      const accept = el("button", "ra-btn ra-btn--small", "Change to " + insight.suggestedMinutes + " min");
      accept.type = "button";
      accept.dataset.action = "accept";
      const keep = el("button", "ra-btn ra-btn--ghost ra-btn--small", "Keep " + insight.plannedMinutes + " min");
      keep.type = "button";
      keep.dataset.action = "keep";
      accept.addEventListener("click", async () => {
        accept.disabled = keep.disabled = true;
        try {
          await api("POST", "/assistant/routines/" + encodeURIComponent(item.routineId) + "/changes/apply",
              {changes: [{type: "RESIZE", activityId: item.routineHabitId, toMinutes: insight.suggestedMinutes}]});
          loadDurations();
        } catch (error) {
          message(status, error.message + " Use Review My Routine to move the activities around it.", "error");
          accept.disabled = keep.disabled = false;
        }
      });
      keep.addEventListener("click", async () => {
        accept.disabled = keep.disabled = true;
        try {
          await api("POST", "/assistant/insights/durations/" + encodeURIComponent(item.routineHabitId) + "/keep",
              {suggestedMinutes: insight.suggestedMinutes});
          loadDurations();
        } catch (error) {
          message(status, error.message, "error");
          accept.disabled = keep.disabled = false;
        }
      });
      actions.append(accept, keep);
      card.append(actions);
    } else if (insight.suggestedMinutes != null) {
      card.append(el("p", "ra-muted", "This habit is used in more than one routine, so its length is changed on the habit itself."));
    }
    card.append(status);
    return card;
  }

  // ---- summary ----

  function today() {
    const now = new Date();
    return new Date(now.getTime() - now.getTimezoneOffset() * 60000).toISOString().slice(0, 10);
  }

  async function loadSummary() {
    const button = $("raSummary");
    button.disabled = true;
    show($("sumLoading"), true);
    show($("sumError"), false);
    show($("sumEmpty"), false);
    show($("sumResult"), false);
    try {
      const data = await api("GET", "/assistant/summary?date=" + encodeURIComponent($("sumDate").value || today()));
      if (data.status === "NO_RUNS") {
        $("sumEmptyText").textContent = data.highlights[0] + " " + data.trend;
        show($("sumEmpty"), true);
        return;
      }
      renderSummary(data);
      show($("sumResult"), true);
    } catch (error) {
      $("sumError").replaceChildren(el("h2", null, "The summary did not load"), el("p", null, error.message));
      show($("sumError"), true);
    } finally {
      show($("sumLoading"), false);
      button.disabled = false;
    }
  }

  function tile(label, text) {
    const item = el("li", "ra-tile");
    item.append(el("strong", null, text), el("span", "ra-muted", label));
    return item;
  }

  function renderSummary(data) {
    const counts = data.counts;
    $("sumCounts").replaceChildren(tile("completed", counts.completed + " / " + counts.planned),
        tile("completion rate", counts.completionRate + "%"), tile("skipped", String(counts.skipped)),
        tile("lengths measured", counts.timed + " / " + counts.completed));
    $("sumHighlights").replaceChildren(...data.highlights.map((text) => el("li", null, text)));
    $("sumTiming").textContent = data.timing || "";

    $("sumRuns").replaceChildren(...data.runs.map((run) => {
      const box = el("div", "ra-run");
      box.append(el("h3", null, run.routineTitle));
      const list = el("ul", "ra-habits");
      run.habits.forEach((habit) => list.append(summaryHabit(run, habit)));
      box.append(list);
      return box;
    }));

    $("sumConsistent").replaceChildren(...(data.consistent.length ? data.consistent.map((text) => el("li", null, "Consistent: " + text))
        : [el("li", "ra-muted", "No streak of 3 days or more among these habits yet.")]));
    $("sumTrend").textContent = data.trend;
    $("sumSuggestions").replaceChildren(...data.suggestions.map((text) => el("li", null, text)));
    $("sumNotes").replaceChildren(...data.notes.map((text) => el("li", null, text)));
  }

  function summaryHabit(run, habit) {
    const item = el("li", "ra-habit ra-habit--" + habit.status.toLowerCase());
    item.dataset.routineHabitId = habit.routineHabitId;
    let actual = "not measured";
    if (habit.reportedMinutes != null) actual = habit.reportedMinutes + " min (entered by you)";
    else if (habit.timerMinutes != null) actual = habit.timerMinutes + " min (run timer)";
    item.append(el("strong", null, habit.title), el("span", "ra-badge", STATUS[habit.status] || habit.status),
        el("span", "ra-muted", "planned " + habit.plannedMinutes + " min · actual " + (habit.status === "COMPLETED" ? actual : "—")));

    if (habit.status === "COMPLETED") {
      const form = el("div", "ra-actual");
      const input = el("input", "ra-input ra-input--short");
      input.type = "number";
      input.min = "1";
      input.max = "1440";
      input.value = habit.reportedMinutes == null ? "" : habit.reportedMinutes;
      input.placeholder = "min";
      input.setAttribute("aria-label", "Actual minutes for " + habit.title);
      const save = el("button", "ra-btn ra-btn--ghost ra-btn--small", "Save actual time");
      save.type = "button";
      const note = el("span", "ra-muted", "");
      save.addEventListener("click", async () => {
        save.disabled = true;
        try {
          await api("PUT", "/assistant/runs/" + encodeURIComponent(run.runId) + "/habits/"
              + encodeURIComponent(habit.routineHabitId) + "/actual-minutes", {minutes: input.value === "" ? null : Number(input.value)});
          loaded.durations = false;
          loadSummary();
        } catch (error) {
          note.textContent = error.message;
          save.disabled = false;
        }
      });
      form.append(input, save, note);
      item.append(form);
    }
    return item;
  }

  // ---- mount ----

  const TEMPLATE = `
        <p class="ra-note" id="raHonesty">
            Times are worked out by fixed scheduling rules, not guessed. A multilingual language model (MiniLM) is used
            only to find similar people and public routines close in meaning to what you want, and to check your goals.
            Nothing is saved or changed until you press Save or Apply.
        </p>

        <nav class="ra-tabs" role="tablist" aria-label="AI Assistant">
            <button class="ra-tab" type="button" role="tab" id="tab-generate" data-tab="generate" aria-controls="panel-generate">Generate My Routine</button>
            <button class="ra-tab" type="button" role="tab" id="tab-review" data-tab="review" aria-controls="panel-review">Review My Routine</button>
            <button class="ra-tab" type="button" role="tab" id="tab-matches" data-tab="matches" aria-controls="panel-matches">Find Similar People</button>
            <button class="ra-tab" type="button" role="tab" id="tab-summary" data-tab="summary" aria-controls="panel-summary">Daily Summary</button>
            <button class="ra-tab" type="button" role="tab" id="tab-suggestions" data-tab="suggestions" aria-controls="panel-suggestions">AI Suggestions</button>
            <button class="ra-tab" type="button" role="tab" id="tab-preferences" data-tab="preferences" aria-controls="panel-preferences">My Preferences</button>
        </nav>

        <!-- Generate -->
        <section class="ra-panel" id="panel-generate" role="tabpanel" aria-labelledby="tab-generate" hidden>
            <div class="ra-card">
                <h2>Describe your day</h2>
                <p class="ra-muted">For example: “I wake up at 07:00, study Java, go to the gym, need lunch at 13:00 and prefer
                    frequent breaks.” The form below is filled from recognised phrases; check it before planning.</p>
                <label class="ra-label" for="raQuickText">Your day in one sentence</label>
                <textarea id="raQuickText" class="ra-input ra-textarea" maxlength="1000" rows="3"></textarea>
                <div class="ra-row">
                    <button id="raQuickFill" class="ra-btn ra-btn--ghost" type="button">Fill the form</button>
                </div>
                <p id="raQuickResult" class="ra-muted" role="status" hidden></p>
            </div>

            <div class="ra-card">
                <h2>Your day</h2>
                <p class="ra-muted">Leave a field empty to use My Preferences, what your tracked runs show, or a default; the plan says which.</p>
                <div class="ra-grid">
                    <label class="ra-field">Wake-up <input type="time" id="gWake" class="ra-input"></label>
                    <label class="ra-field">Sleep <input type="time" id="gSleep" class="ra-input"></label>
                    <label class="ra-field">Available from <input type="time" id="gFrom" class="ra-input"></label>
                    <label class="ra-field">Available until <input type="time" id="gTo" class="ra-input"></label>
                    <label class="ra-field">Exercise time <input type="time" id="gExercise" class="ra-input"></label>
                    <label class="ra-field">Breaks
                        <select id="gRest" class="ra-input">
                            <option value="">As in my preferences</option>
                            <option value="FREQUENT">Frequent short breaks</option>
                            <option value="NORMAL">Normal</option>
                            <option value="RARE">Long focus sessions, fewer breaks</option>
                        </select>
                    </label>
                    <label class="ra-field">Intensity
                        <select id="gIntensity" class="ra-input">
                            <option value="">As in my preferences</option>
                            <option value="LIGHT">Light</option>
                            <option value="BALANCED">Balanced</option>
                            <option value="INTENSE">Intense</option>
                        </select>
                    </label>
                </div>
                <fieldset class="ra-fieldset">
                    <legend>Meals</legend>
                    <div class="ra-grid">
                        <label class="ra-field"><span><input type="checkbox" id="gMealBreakfast" checked> Breakfast</span>
                            <input type="time" id="gBreakfast" class="ra-input" aria-label="Breakfast time"></label>
                        <label class="ra-field"><span><input type="checkbox" id="gMealLunch" checked> Lunch</span>
                            <input type="time" id="gLunch" class="ra-input" aria-label="Lunch time"></label>
                        <label class="ra-field"><span><input type="checkbox" id="gMealDinner" checked> Dinner</span>
                            <input type="time" id="gDinner" class="ra-input" aria-label="Dinner time"></label>
                    </div>
                </fieldset>
                <fieldset class="ra-fieldset">
                    <legend>Days</legend>
                    <div class="ra-days" id="gDays"></div>
                </fieldset>
                <fieldset class="ra-fieldset">
                    <legend>Fixed commitments (study, work)</legend>
                    <div id="gCommitments" class="ra-list"></div>
                    <button id="gAddCommitment" class="ra-btn ra-btn--ghost ra-btn--small" type="button">Add commitment</button>
                </fieldset>
                <fieldset class="ra-fieldset">
                    <legend>Activities you want</legend>
                    <div id="gHabits" class="ra-list"></div>
                    <button id="gAddHabit" class="ra-btn ra-btn--ghost ra-btn--small" type="button">Add activity</button>
                    <label class="ra-check"><input type="checkbox" id="gSplit" checked> Split long study and work into focus sessions</label>
                </fieldset>
                <div class="ra-row">
                    <button id="raGenerate" class="ra-btn" type="button">Generate My Routine</button>
                </div>
            </div>

            <div id="genLoading" class="ra-state ra-loading" role="status" aria-live="polite" hidden>
                <span class="ra-spinner" aria-hidden="true"></span><p>Planning your day…</p>
            </div>
            <div id="genError" class="ra-state ra-error" role="alert" hidden></div>

            <div id="genResult" hidden>
                <div class="ra-card">
                    <h2>Preview</h2>
                    <p class="ra-muted" id="genNote"></p>
                    <ul id="genWarnings" class="ra-warnings"></ul>
                    <ol id="genTimeline" class="ra-timeline" aria-label="Planned day"></ol>
                    <div id="genUnscheduledBox" hidden>
                        <h3>Did not fit</h3>
                        <ul id="genUnscheduled" class="ra-plain"></ul>
                    </div>
                    <details class="ra-details" open>
                        <summary>Why this plan fits you</summary>
                        <ul id="genWhy" class="ra-plain"></ul>
                    </details>
                </div>

                <div class="ra-card">
                    <h2>Ideas from public routines</h2>
                    <p class="ra-muted" id="exNote"></p>
                    <p class="ra-muted" id="exMessage" hidden></p>
                    <ul id="exList" class="ra-examples"></ul>
                </div>

                <div class="ra-card">
                    <h2>Save as a new routine</h2>
                    <p class="ra-muted">Your existing routines are never changed. Untick what you don't want to keep; commitments are not saved.</p>
                    <div class="ra-grid">
                        <label class="ra-field">Title <input id="saveTitle" class="ra-input" maxlength="100" value="My planned day"></label>
                        <label class="ra-field">Category <select id="saveCategory" class="ra-input"></select></label>
                    </div>
                    <label class="ra-check"><input type="checkbox" id="savePublic"> Make it public</label>
                    <div class="ra-row">
                        <button id="raSave" class="ra-btn" type="button">Save as new routine</button>
                    </div>
                    <p id="saveResult" class="ra-message" role="status" hidden></p>
                </div>
            </div>
        </section>

        <!-- Preferences -->
        <section class="ra-panel" id="panel-preferences" role="tabpanel" aria-labelledby="tab-preferences" hidden>
            <div class="ra-card">
                <h2>My Preferences</h2>
                <p class="ra-muted">Saved preferences come first. Empty ones are filled from your tracked runs (after
                    <span id="pMinRuns">3</span> completed runs) or a default. You can change them any time.</p>
                <div class="ra-grid">
                    <label class="ra-field">Wake-up <input type="time" id="pWake" class="ra-input"></label>
                    <label class="ra-field">Sleep <input type="time" id="pSleep" class="ra-input"></label>
                    <label class="ra-field">Available from <input type="time" id="pFrom" class="ra-input"></label>
                    <label class="ra-field">Available until <input type="time" id="pTo" class="ra-input"></label>
                    <label class="ra-field">Breakfast <input type="time" id="pBreakfast" class="ra-input"></label>
                    <label class="ra-field">Lunch <input type="time" id="pLunch" class="ra-input"></label>
                    <label class="ra-field">Dinner <input type="time" id="pDinner" class="ra-input"></label>
                    <label class="ra-field">Meal length (min) <input type="number" min="5" max="180" id="pMeal" class="ra-input"></label>
                    <label class="ra-field">Exercise time <input type="time" id="pExercise" class="ra-input"></label>
                    <label class="ra-field">Longest focus session (min) <input type="number" min="15" max="240" id="pFocus" class="ra-input"></label>
                    <label class="ra-field">Breaks
                        <select id="pRest" class="ra-input">
                            <option value="">Default (every 90 min)</option>
                            <option value="FREQUENT">Frequent short breaks</option>
                            <option value="NORMAL">Normal</option>
                            <option value="RARE">Long focus sessions, fewer breaks</option>
                        </select>
                    </label>
                    <label class="ra-field">Break after (min) <input type="number" min="20" max="300" id="pBreakEvery" class="ra-input"></label>
                    <label class="ra-field">Break length (min) <input type="number" min="5" max="60" id="pBreakLength" class="ra-input"></label>
                    <label class="ra-field">Intensity
                        <select id="pIntensity" class="ra-input">
                            <option value="">Default (balanced)</option>
                            <option value="LIGHT">Light</option>
                            <option value="BALANCED">Balanced</option>
                            <option value="INTENSE">Intense</option>
                        </select>
                    </label>
                </div>
                <fieldset class="ra-fieldset">
                    <legend>Fixed commitments</legend>
                    <div id="pCommitments" class="ra-list"></div>
                    <button id="pAddCommitment" class="ra-btn ra-btn--ghost ra-btn--small" type="button">Add commitment</button>
                </fieldset>
                <div class="ra-row">
                    <button id="raSavePrefs" class="ra-btn" type="button">Save preferences</button>
                </div>
                <p id="prefsMessage" class="ra-message" role="status" hidden></p>
            </div>
            <div class="ra-card">
                <h2>What the assistant uses now</h2>
                <ul id="prefsResolved" class="ra-sources"></ul>
                <h3>Seen in your tracked runs</h3>
                <ul id="prefsHistory" class="ra-plain"></ul>
            </div>
        </section>

        <!-- Review -->
        <section class="ra-panel" id="panel-review" role="tabpanel" aria-labelledby="tab-review" hidden>
            <div class="ra-card">
                <h2>Review My Routine</h2>
                <p class="ra-muted">Checks time conflicts, breaks, meals, your waking hours, the workload, lengths against your
                    measured runs and your goals. You choose which changes to apply.</p>
                <div class="ra-grid">
                    <label class="ra-field">Routine <select id="reviewRoutine" class="ra-input"></select></label>
                </div>
                <p id="reviewNoRoutines" class="ra-muted" hidden>You have no routines yet. Generate one first.</p>
                <div class="ra-row">
                    <button id="raReview" class="ra-btn" type="button">Review My Routine</button>
                </div>
            </div>
            <div id="reviewLoading" class="ra-state ra-loading" role="status" aria-live="polite" hidden>
                <span class="ra-spinner" aria-hidden="true"></span><p>Reviewing your routine…</p>
            </div>
            <div id="reviewError" class="ra-state ra-error" role="alert" hidden></div>
            <div id="reviewResult" hidden>
                <div class="ra-card">
                    <h2 id="reviewTitle"></h2>
                    <p class="ra-muted" id="reviewNote"></p>
                    <p class="ra-message ra-message--warn" id="reviewRunning" hidden>This routine is running now, so changes can be
                        applied after the run ends.</p>
                    <ul id="reviewIssues" class="ra-issues"></ul>
                    <details class="ra-details">
                        <summary>What was checked</summary>
                        <ul id="reviewChecked" class="ra-plain"></ul>
                        <ul id="reviewNotChecked" class="ra-plain ra-muted"></ul>
                    </details>
                    <div class="ra-row">
                        <button id="raPreview" class="ra-btn ra-btn--ghost" type="button">Preview Changes</button>
                        <button id="raApply" class="ra-btn" type="button">Apply Approved Changes</button>
                    </div>
                    <p id="reviewMessage" class="ra-message" role="status" hidden></p>
                </div>
                <div class="ra-card" id="previewBox" hidden>
                    <h2>Routine after the approved changes</h2>
                    <p class="ra-muted" id="previewNote"></p>
                    <ol id="previewTimeline" class="ra-timeline"></ol>
                </div>
            </div>
        </section>

        <!-- Durations -->
        <section class="ra-panel" id="panel-suggestions" role="tabpanel" aria-labelledby="tab-suggestions" hidden>
            <div class="ra-card">
                <h2>AI Suggestions: planned and actual time</h2>
                <p class="ra-muted" id="durNote"></p>
                <div class="ra-row">
                    <button id="raDurations" class="ra-btn ra-btn--ghost" type="button">Refresh</button>
                </div>
            </div>
            <div id="durLoading" class="ra-state ra-loading" role="status" aria-live="polite" hidden>
                <span class="ra-spinner" aria-hidden="true"></span><p>Comparing planned and measured times…</p>
            </div>
            <div id="durError" class="ra-state ra-error" role="alert" hidden></div>
            <div id="durEmpty" class="ra-state" hidden>
                <h2>No activities yet</h2>
                <p>Create a routine and track a few runs; the comparison appears here.</p>
            </div>
            <ul id="durList" class="ra-cards" hidden></ul>
        </section>

        <!-- Find Similar People (US-26), drawn by lifestyle-matches.js when first opened -->
        <section class="ra-panel" id="panel-matches" role="tabpanel" aria-labelledby="tab-matches" hidden>
            <div class="lm-page lm-page--embedded" id="lifestyleMatches"></div>
        </section>

        <!-- Summary -->
        <section class="ra-panel" id="panel-summary" role="tabpanel" aria-labelledby="tab-summary" hidden>
            <div class="ra-card">
                <h2>Daily Summary</h2>
                <div class="ra-grid">
                    <label class="ra-field">Day <input type="date" id="sumDate" class="ra-input"></label>
                </div>
                <div class="ra-row">
                    <button id="raSummary" class="ra-btn" type="button">Show summary</button>
                </div>
            </div>
            <div id="sumLoading" class="ra-state ra-loading" role="status" aria-live="polite" hidden>
                <span class="ra-spinner" aria-hidden="true"></span><p>Summarising your day…</p>
            </div>
            <div id="sumError" class="ra-state ra-error" role="alert" hidden></div>
            <div id="sumEmpty" class="ra-state" hidden>
                <h2>Nothing tracked that day</h2>
                <p id="sumEmptyText"></p>
                <a class="ra-btn ra-btn--ghost" href="profile.html#my-routines">Go to my routines</a>
            </div>
            <div id="sumResult" hidden>
                <div class="ra-card">
                    <ul id="sumCounts" class="ra-tiles"></ul>
                    <ul id="sumHighlights" class="ra-plain ra-highlights"></ul>
                    <p class="ra-muted" id="sumTiming"></p>
                </div>
                <div class="ra-card">
                    <h2>Habits</h2>
                    <div id="sumRuns"></div>
                </div>
                <div class="ra-card">
                    <h2>Progress and suggestions</h2>
                    <ul id="sumConsistent" class="ra-plain"></ul>
                    <p id="sumTrend" class="ra-muted"></p>
                    <h3>For your next routine</h3>
                    <ul id="sumSuggestions" class="ra-plain"></ul>
                    <ul id="sumNotes" class="ra-plain ra-muted"></ul>
                </div>
            </div>
        </section>`;

  function mount(root, options) {
    if (root.assistantApp) {
      return root.assistantApp;
    }
    token = options.token;
    onTab = options.onTab || (() => {});
    root.innerHTML = TEMPLATE;
    $ = (id) => root.querySelector("#" + id);

    const days = $("gDays");
    DAYS.forEach((day) => {
      const label = el("label", "ra-day");
      const box = el("input");
      box.type = "checkbox";
      box.id = "gDay" + day;
      box.checked = true;
      label.append(box, document.createTextNode(" " + DAY_NAMES[day]));
      days.append(label);
    });
    habitRow({title: "", minutes: 30});
    $("sumDate").value = today();
    $("sumDate").max = today();

    TABS.forEach((tab) => $("tab-" + tab).addEventListener("click", () => {
      openTab(tab);
      onTab(tab);
    }));
    $("raQuickFill").addEventListener("click", quickFill);
    $("gAddHabit").addEventListener("click", () => habitRow());
    $("gAddCommitment").addEventListener("click", () => commitmentRow($("gCommitments")));
    $("pAddCommitment").addEventListener("click", () => commitmentRow($("pCommitments")));
    $("raGenerate").addEventListener("click", generate);
    $("raSave").addEventListener("click", savePlan);
    $("raSavePrefs").addEventListener("click", savePreferences);
    $("raReview").addEventListener("click", review);
    $("raPreview").addEventListener("click", previewChanges);
    $("raApply").addEventListener("click", applyChanges);
    $("raDurations").addEventListener("click", loadDurations);
    $("raSummary").addEventListener("click", loadSummary);

    // routine.html?id=… links its owner to profile.html?review=<id>#ai-review.
    pendingReview = new URLSearchParams(location.search).get("review");
    loadPreferences();
    routinesReady = loadRoutines();

    root.assistantApp = {open: openTab, current: () => current};
    return root.assistantApp;
  }

  window.CopyMyLifeAssistant = {mount, tabs: TABS};
})();
