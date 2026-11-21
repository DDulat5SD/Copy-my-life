// US-24: the one Report dialog behind every Report button (a user, a routine, a post, or a
// comment under a routine or a post). CopyMyLifeReport.open({type, id, token}) asks for a reason
// and an optional explanation and sends them to the matching /reports endpoint. The server
// decides everything; this only explains the answer. Built with DOM calls, so nothing a user
// typed is ever read as HTML.
(function () {

  const ENDPOINTS = {
    USER: "/reports/users/",
    ROUTINE: "/reports/routines/",
    POST: "/reports/posts/",
    COMMENT: "/reports/comments/",
    POST_COMMENT: "/reports/post-comments/"
  };

  const TITLES = {
    USER: "Report user",
    ROUTINE: "Report routine",
    POST: "Report post",
    COMMENT: "Report comment",
    POST_COMMENT: "Report comment"
  };

  // Which field of a report in /reports/mine names its target.
  const TARGET_FIELDS = {
    USER: "reportedUserId",
    ROUTINE: "reportedRoutineId",
    POST: "reportedPostId",
    COMMENT: "reportedCommentId",
    POST_COMMENT: "reportedPostCommentId"
  };

  // Keep in step with ReportService.Reason.
  const REASONS = [
    ["SPAM", "Spam", "Advertising, scams or the same thing posted again and again"],
    ["HARASSMENT", "Harassment", "Bullying, threats or attacks on someone"],
    ["INAPPROPRIATE_CONTENT", "Inappropriate content", "Offensive, violent or sexual content"],
    ["MISINFORMATION", "Misinformation", "Harmful or misleading health or safety claims"],
    ["OTHER", "Something else", "Explain below what is wrong"]
  ];

  const MAX_DETAILS = 1000;
  const SUBMITTED = "Your report has been submitted.";
  const ALREADY = "You have already reported this content.";

  let parts = null;
  let current = null;
  let busy = false;
  let returnFocus = null;

  function element(tag, attributes, text) {
    const node = document.createElement(tag);
    Object.entries(attributes || {}).forEach(([name, value]) => node.setAttribute(name, value));
    if (text != null) node.textContent = text;
    return node;
  }

  function build() {
    const backdrop = element("div", {class: "cml-report-backdrop", hidden: ""});
    const dialog = element("div", {
      class: "cml-report", role: "dialog", "aria-modal": "true", tabindex: "-1",
      "aria-labelledby": "cmlReportTitle", "aria-describedby": "cmlReportIntro"
    });

    const close = element("button", {type: "button", class: "cml-report__close", "aria-label": "Close"}, "×");
    const title = element("h2", {class: "cml-report__title", id: "cmlReportTitle"}, "Report");
    const intro = element("p", {class: "cml-report__intro", id: "cmlReportIntro"},
        "Tell our moderators what is wrong. They review every report; nothing changes until they decide.");

    const form = element("form", {class: "cml-report__form", novalidate: ""});
    const reasons = element("fieldset", {class: "cml-report__reasons"});
    reasons.appendChild(element("legend", {class: "cml-report__label"}, "Why are you reporting this?"));

    REASONS.forEach(([value, label, hint]) => {
      const option = element("label", {class: "cml-report__reason"});
      const radio = element("input", {type: "radio", name: "reason", value});
      const words = element("span", {class: "cml-report__reason-text"});
      words.appendChild(element("strong", {}, label));
      words.appendChild(element("span", {class: "cml-report__hint"}, hint));
      option.appendChild(radio);
      option.appendChild(words);
      reasons.appendChild(option);
    });

    const reasonError = element("p", {class: "cml-report__error", id: "cmlReportReasonError", role: "alert", hidden: ""});
    const detailsLabel = element("label", {class: "cml-report__label", for: "cmlReportDetails"}, "Explanation (optional)");
    const details = element("textarea", {
      class: "cml-report__details", id: "cmlReportDetails", name: "details", rows: "4",
      maxlength: String(MAX_DETAILS), placeholder: "Add anything that helps a moderator understand"
    });
    const count = element("span", {class: "cml-report__count", "aria-live": "polite"}, "0 / " + MAX_DETAILS);
    const error = element("p", {class: "cml-report__error", role: "alert", hidden: ""});

    const actions = element("div", {class: "cml-report__actions"});
    const cancel = element("button", {type: "button", class: "cml-report__btn cml-report__btn--ghost"}, "Cancel");
    const submit = element("button", {type: "submit", class: "cml-report__btn cml-report__btn--primary"}, "Submit Report");
    actions.appendChild(cancel);
    actions.appendChild(submit);

    form.appendChild(reasons);
    form.appendChild(reasonError);
    form.appendChild(detailsLabel);
    form.appendChild(details);
    form.appendChild(count);
    form.appendChild(error);
    form.appendChild(actions);

    const result = element("div", {class: "cml-report__result", role: "status", hidden: ""});
    const resultIcon = element("span", {class: "cml-report__result-icon", "aria-hidden": "true"});
    const resultTitle = element("p", {class: "cml-report__result-title"});
    const resultText = element("p", {class: "cml-report__result-text"});
    const done = element("button", {type: "button", class: "cml-report__btn cml-report__btn--primary"}, "Close");
    result.appendChild(resultIcon);
    result.appendChild(resultTitle);
    result.appendChild(resultText);
    result.appendChild(done);

    const checking = element("p", {class: "cml-report__checking", role: "status", hidden: ""}, "Checking…");

    dialog.appendChild(close);
    dialog.appendChild(title);
    dialog.appendChild(intro);
    dialog.appendChild(checking);
    dialog.appendChild(form);
    dialog.appendChild(result);
    backdrop.appendChild(dialog);
    document.body.appendChild(backdrop);

    close.addEventListener("click", hide);
    cancel.addEventListener("click", hide);
    done.addEventListener("click", hide);
    backdrop.addEventListener("mousedown", (event) => {
      if (event.target === backdrop && !busy) hide();
    });
    details.addEventListener("input", () => {
      count.textContent = details.value.length + " / " + MAX_DETAILS;
    });
    reasons.addEventListener("change", () => showReasonError(""));
    form.addEventListener("submit", (event) => {
      event.preventDefault();
      send();
    });
    document.addEventListener("keydown", onKey, true);

    return {backdrop, dialog, title, intro, checking, form, reasons, reasonError, details, count, error, cancel, submit,
      close, result, resultIcon, resultTitle, resultText, done};
  }

  function focusable() {
    return [...parts.dialog.querySelectorAll("button, input, textarea, [href], [tabindex]:not([tabindex='-1'])")]
        .filter((node) => !node.disabled && node.offsetParent !== null);
  }

  // Escape closes; Tab stays inside the dialog while it is open.
  function onKey(event) {
    if (!parts || parts.backdrop.hidden) return;

    if (event.key === "Escape") {
      event.preventDefault();
      event.stopPropagation();
      if (!busy) hide();
      return;
    }

    if (event.key === "Tab") {
      const nodes = focusable();
      if (!nodes.length) return;
      const first = nodes[0];
      const last = nodes[nodes.length - 1];
      if (event.shiftKey && (document.activeElement === first || !parts.dialog.contains(document.activeElement))) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && (document.activeElement === last || !parts.dialog.contains(document.activeElement))) {
        event.preventDefault();
        first.focus();
      }
    }
  }

  function showReasonError(text) {
    parts.reasonError.textContent = text;
    parts.reasonError.hidden = !text;
    parts.reasons.setAttribute("aria-invalid", String(Boolean(text)));
    if (text) {
      parts.reasons.setAttribute("aria-describedby", "cmlReportReasonError");
    } else {
      parts.reasons.removeAttribute("aria-describedby");
    }
  }

  function showError(text) {
    parts.error.textContent = text || "";
    parts.error.hidden = !text;
  }

  function setBusy(value) {
    busy = value;
    parts.submit.disabled = value;
    parts.cancel.disabled = value;
    parts.close.disabled = value;
    parts.form.setAttribute("aria-busy", String(value));
    parts.submit.textContent = value ? "Submitting…" : "Submit Report";
    parts.reasons.disabled = value;
    parts.details.readOnly = value;
  }

  function showResult(kind) {
    const already = kind === "already";
    parts.form.hidden = true;
    parts.checking.hidden = true;
    parts.intro.hidden = true;
    parts.result.hidden = false;
    parts.result.className = "cml-report__result cml-report__result--" + kind;
    parts.resultIcon.textContent = already ? "!" : "✓";
    parts.resultTitle.textContent = already ? ALREADY : SUBMITTED;
    parts.resultText.textContent = already
        ? "A moderator is still reviewing your earlier report. You can report it again once that review is closed."
        : "Thank you. A moderator will review it.";
    parts.done.focus();
  }

  function reset() {
    parts.form.reset();
    parts.form.hidden = false;
    parts.intro.hidden = false;
    parts.result.hidden = true;
    parts.checking.hidden = true;
    parts.count.textContent = "0 / " + MAX_DETAILS;
    showReasonError("");
    showError("");
    setBusy(false);
  }

  async function request(url, options) {
    const response = await fetch(url, {
      ...options,
      headers: {
        "Authorization": "Bearer " + current.token,
        ...(options && options.body ? {"Content-Type": "application/json"} : {})
      }
    });
    const text = await response.text();
    let data = null;
    try {
      data = text ? JSON.parse(text) : null;
    } catch (ignored) {
      data = null;
    }
    return {status: response.status, ok: response.ok, data};
  }

  // An active report the person already sent shows "already reported" before they fill anything in.
  async function checkEarlier(target) {
    parts.checking.hidden = false;
    parts.form.hidden = true;
    try {
      const answer = await request("/reports/mine");
      if (current !== target) return;
      const field = TARGET_FIELDS[target.type];
      const earlier = answer.ok && Array.isArray(answer.data) && answer.data.some((report) =>
          report.type === target.type && Number(report[field]) === target.id
          && (report.status === "OPEN" || report.status === "IN_REVIEW"));
      if (earlier) {
        showResult("already");
        return;
      }
    } catch (ignored) {
      // The form still works; the server refuses a second report anyway.
    }
    if (current !== target) return;
    parts.checking.hidden = true;
    parts.form.hidden = false;
    const first = parts.reasons.querySelector("input");
    if (first) first.focus();
  }

  async function send() {
    if (busy || !current) return;

    const chosen = parts.reasons.querySelector("input[name=reason]:checked");
    const details = parts.details.value.trim();

    if (!chosen) {
      showReasonError("Please select a reason.");
      parts.reasons.querySelector("input").focus();
      return;
    }

    if (details.length > MAX_DETAILS) {
      showError("Your explanation can be at most " + MAX_DETAILS + " characters.");
      parts.details.focus();
      return;
    }

    const target = current;
    setBusy(true);
    showError("");

    try {
      const answer = await request(ENDPOINTS[target.type] + encodeURIComponent(target.id), {
        method: "POST",
        body: JSON.stringify({reason: chosen.value, details: details || null})
      });

      if (current !== target) return;

      if (answer.ok) {
        setBusy(false);
        showResult("done");
        if (typeof target.onReported === "function") target.onReported();
        return;
      }

      const code = answer.data && answer.data.code;
      setBusy(false);

      if (answer.status === 409 && code === "ALREADY_REPORTED") {
        showResult("already");
        return;
      }

      if (answer.status === 401) {
        showError("Your session has ended. Please sign in again to report.");
        return;
      }

      if (answer.status === 404) {
        showError("This content is no longer available.");
        return;
      }

      showError((answer.data && answer.data.message) || "Your report could not be sent. Please try again.");
    } catch (failure) {
      if (current !== target) return;
      setBusy(false);
      showError("Your report could not be sent. Check your connection and try again.");
    }
  }

  function open(options) {
    const type = String(options && options.type || "").toUpperCase();
    const id = Number(options && options.id);

    if (!ENDPOINTS[type] || !Number.isInteger(id) || id <= 0) {
      throw new Error("Unknown report target");
    }

    if (!parts) parts = build();

    returnFocus = document.activeElement;
    current = {type, id, token: options.token || localStorage.getItem("token"), onReported: options.onReported};
    reset();
    parts.title.textContent = TITLES[type];
    parts.backdrop.dataset.type = type;
    parts.backdrop.dataset.id = String(id);
    parts.backdrop.hidden = false;
    document.documentElement.classList.add("cml-report-open");
    parts.dialog.focus();
    checkEarlier(current);
  }

  function hide() {
    if (!parts || busy) return;
    parts.backdrop.hidden = true;
    current = null;
    document.documentElement.classList.remove("cml-report-open");
    if (returnFocus && typeof returnFocus.focus === "function" && document.contains(returnFocus)) {
      returnFocus.focus();
    }
    returnFocus = null;
  }

  window.CopyMyLifeReport = Object.freeze({open, close: hide});
})();
