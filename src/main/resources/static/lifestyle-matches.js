// US-26 Find Similar People, as a module the profile's AI Assistant section mounts into its
// Matches tab. mount() draws into its root once; mounting again keeps what is shown. The matching
// itself is GET /users/me/lifestyle-matches; every value from it is written as text.
(function () {

  // Only fixed text here; people and routines are added with textContent below.
  const TEMPLATE = `
    <section class="lm-hero">
        <p class="lm-badge">AI lifestyle matching</p>
        <h2 class="lm-title">Find Similar People</h2>
        <p class="lm-intro">
            We compare your interests, goals, profile and routines with what other members share on their
            public profiles and public routines, and suggest the people whose days look most like yours.
        </p>
        <button id="findMatchesBtn" class="lm-btn" type="button">Find My Matches</button>
        <p class="lm-note">
            The percentage shows how alike what you and they have shared is. It is not a measure of how well
            you would get on.
        </p>
    </section>

    <div id="lmGuest" class="lm-state" hidden>
        <h2>Sign in to find similar people</h2>
        <p>Matching uses your own profile and routines, so it is only for signed-in members.</p>
        <a class="lm-btn" href="login.html">Sign in</a>
    </div>

    <div id="lmLoading" class="lm-state lm-loading" role="status" aria-live="polite" hidden>
        <span class="lm-spinner" aria-hidden="true"></span>
        <p>Finding people with a lifestyle like yours…</p>
    </div>

    <div id="lmEmpty" class="lm-state" role="status" hidden>
        <h2 id="lmEmptyTitle"></h2>
        <p id="lmEmptyText"></p>
        <a id="lmEmptyAction" class="lm-btn lm-btn--ghost" href="profile.html">Go to my profile</a>
    </div>

    <div id="lmError" class="lm-state lm-error" role="alert" hidden>
        <h2>Matching did not work this time</h2>
        <p id="lmErrorText"></p>
        <button id="lmRetry" class="lm-btn lm-btn--ghost" type="button">Try again</button>
    </div>

    <ol id="lmResults" class="lm-results" aria-label="People with a similar lifestyle" hidden></ol>

    <details class="lm-how">
        <summary>How the percentage is worked out</summary>
        <ul>
            <li><strong>60% descriptions:</strong> a multilingual AI language model reads your interests, goals,
                profile and routines and theirs (English, Kazakh and Russian work) and measures how close the meanings are.</li>
            <li><strong>25% schedule:</strong> on which days and at which part of the day (morning, afternoon,
                evening, night) your timed activities and theirs take place.</li>
            <li><strong>15% interests:</strong> how many of your listed interests have a close counterpart in theirs.</li>
        </ul>
        <p>If one of the three is missing on either side (for example nobody has set activity times), it is left
            out and the other two count for more. These weights are starting values, not scientifically validated.
            Only public profiles and public routines of other members are used, and results are worked out again
            every time you press the button.</p>
    </details>`;

  const PART_NAMES = {morning: "morning", afternoon: "afternoon", evening: "evening", night: "night"};
  const DAY_NAMES = {MON: "Mon", TUE: "Tue", WED: "Wed", THU: "Thu", FRI: "Fri", SAT: "Sat", SUN: "Sun"};
  const EMPTY = {
    NEEDS_DATA: {title: "Tell us a little about your days first", action: true},
    NO_CANDIDATES: {title: "No one to compare with yet", action: false},
    NO_MATCHES: {title: "No similar lifestyles found yet", action: true}
  };

  function element(tag, className, text) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text !== undefined) node.textContent = text;
    return node;
  }

  function pairsText(pairs) {
    return pairs.map(pair => pair.mine.toLowerCase() === pair.theirs.toLowerCase()
        ? pair.theirs : pair.theirs + " (like your " + pair.mine + ")").join(", ");
  }

  function reasons(match) {
    const list = [];
    if (match.sharedInterests.length) list.push("Shared interests: " + pairsText(match.sharedInterests));
    if (match.similarActivities.length) list.push("Similar activities: " + pairsText(match.similarActivities));
    if (match.sharedTimesOfDay.length) {
      const days = match.sharedDays.length === 7 ? "every day"
          : "on " + match.sharedDays.map(day => DAY_NAMES[day]).join(", ");
      list.push("Active in the " + match.sharedTimesOfDay.map(part => PART_NAMES[part]).join(" and ") + " like you, " + days);
    }
    if (!list.length) list.push("Your profiles and routines are described in a similar way.");
    return list;
  }

  function partsText(components) {
    const label = value => value === null ? "not compared" : value + "%";
    return "Descriptions " + label(components.semantic) + " · Schedule " + label(components.schedule)
        + " · Interests " + label(components.interests);
  }

  function card(match) {
    const item = element("li", "lm-card");
    item.dataset.userId = match.userId;

    const person = element("div", "lm-person");
    const avatarUrl = SafeDom.safeImageUrl(match.avatarUrl);
    const name = match.name || match.username || "Member";
    if (avatarUrl) {
      const image = element("img", "lm-avatar");
      image.src = avatarUrl;
      image.alt = name;
      image.addEventListener("error", () => image.replaceWith(element("span", "lm-avatar lm-avatar-fallback", name.charAt(0).toUpperCase())));
      person.append(image);
    } else {
      person.append(element("span", "lm-avatar lm-avatar-fallback", name.charAt(0).toUpperCase()));
    }
    const who = element("div", "lm-who");
    who.append(element("p", "lm-name", name));
    if (match.username) who.append(element("p", "lm-username", "@" + match.username));
    if (match.profession) who.append(element("p", "lm-profession", match.profession));
    person.append(who);

    const reasonList = element("ul", "lm-reasons");
    reasons(match).forEach(text => reasonList.append(element("li", null, text)));

    item.append(person, element("span", "lm-score", match.score + "% similar lifestyle"), reasonList,
        element("p", "lm-parts", partsText(match.components)));

    if (match.publicRoutines.length) {
      const routines = element("ul", "lm-routines");
      routines.setAttribute("aria-label", "Public routines of " + name);
      match.publicRoutines.forEach(routine => {
        const link = element("a", null, routine.title || "Routine");
        link.href = "routine.html?id=" + encodeURIComponent(routine.id);
        const entry = element("li");
        entry.append(link);
        routines.append(entry);
      });
      item.append(routines);
    }

    const actions = element("div", "lm-actions");
    const profile = element("a", "lm-btn", "View Profile");
    profile.href = "user-profile.html?id=" + encodeURIComponent(match.userId);
    const routinesLink = element("a", "lm-btn lm-btn--ghost", "View Public Routines");
    routinesLink.href = "user-profile.html?id=" + encodeURIComponent(match.userId) + "#publicRoutinesSection";
    actions.append(profile, routinesLink);
    item.append(actions);
    return item;
  }

  function mount(root, options) {
    if (root.matchesApp) {
      return root.matchesApp;
    }

    const token = options.token;
    root.innerHTML = TEMPLATE;
    const $ = (id) => root.querySelector("#" + id);
    const button = $("findMatchesBtn");
    const views = {
      guest: $("lmGuest"),
      loading: $("lmLoading"),
      empty: $("lmEmpty"),
      error: $("lmError"),
      results: $("lmResults")
    };

    function show(name) {
      Object.entries(views).forEach(([key, view]) => {
        view.hidden = key !== name;
      });
    }

    function showError(message) {
      $("lmErrorText").textContent = message;
      show("error");
    }

    async function findMatches() {
      button.disabled = true;
      show("loading");
      try {
        const response = await fetch("/users/me/lifestyle-matches", {
          headers: {"Authorization": "Bearer " + token},
          cache: "no-store"
        });
        const body = await response.json().catch(() => null);

        if (response.status === 401 || (response.status === 403 && !(body && body.message))) {
          window.CopyMyLifeSession.sessionEnded();
          return;
        }
        if (!response.ok || !body) {
          showError((body && body.message) || "Something went wrong. Please try again.");
          return;
        }

        if (body.status === "OK") {
          views.results.replaceChildren(...body.matches.map(card));
          show("results");
        } else {
          const empty = EMPTY[body.status] || EMPTY.NO_MATCHES;
          $("lmEmptyTitle").textContent = empty.title;
          $("lmEmptyText").textContent = body.message || "";
          $("lmEmptyAction").hidden = !empty.action;
          show("empty");
        }
      } catch (error) {
        showError("Could not reach CopyMyLife. Check your connection and try again.");
      } finally {
        button.disabled = false;
        button.textContent = "Find My Matches Again";
      }
    }

    if (!token) {
      button.hidden = true;
      show("guest");
    } else {
      button.addEventListener("click", findMatches);
      $("lmRetry").addEventListener("click", findMatches);
    }

    root.matchesApp = {findMatches};
    return root.matchesApp;
  }

  window.CopyMyLifeMatches = {mount};
})();
