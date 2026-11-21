// Creator Statistics (US-12): the profile section with the signed-in member's followers and how
// often each of their published routines has been copied (GET /users/me/creator-stats).
// mount() draws into its root once; mounting the same root again only reloads the numbers.
// Everything is built with DOM calls and text, so a routine title is never read as HTML.
(function () {

  const NUMBER = new Intl.NumberFormat("en-US");

  function element(tag, attributes, children) {
    const node = document.createElement(tag);

    Object.entries(attributes || {}).forEach(([name, value]) => {
      if (name === "text") {
        node.textContent = value;
      } else if (value !== null && value !== undefined && value !== false) {
        node.setAttribute(name, value === true ? "" : String(value));
      }
    });

    (children || []).forEach((child) => node.append(child));
    return node;
  }

  function count(value) {
    const number = Number(value);
    return NUMBER.format(Number.isFinite(number) ? number : 0);
  }

  function plural(value, one, many) {
    return Number(value) === 1 ? one : many;
  }

  // One number of the overview; the hint says what a 0 means.
  function overviewItem(label, value, zeroHint) {
    const item = element("div", {class: "cs-stat"}, [
      element("dt", {class: "cs-stat__label", text: label}),
      element("dd", {class: "cs-stat__value", text: count(value)})
    ]);

    if (Number(value) === 0 && zeroHint) {
      item.append(element("dd", {class: "cs-stat__hint", text: zeroHint}));
    }

    return item;
  }

  function metric(label, value) {
    return element("div", {class: "cs-metric"}, [
      element("dt", {text: label}),
      element("dd", {text: value})
    ]);
  }

  function rating(routine) {
    const ratings = Number(routine.ratingCount) || 0;

    if (ratings === 0) {
      return "No ratings";
    }

    return Number(routine.averageRating || 0).toFixed(1) + " (" + count(ratings) + ")";
  }

  function routineItem(routine) {
    const copies = Number(routine.copiesCount) || 0;

    return element("li", {class: "cs-routine mc-card", "data-routine-id": Number(routine.routineId)}, [
      element("div", {class: "cs-routine__head"}, [
        element("a", {
          class: "cs-routine__title",
          href: "routine.html?id=" + encodeURIComponent(Number(routine.routineId)),
          text: routine.title || "Untitled routine"
        }),
        element("p", {
          class: "cs-routine__copies",
          "data-copies": copies,
          text: count(copies) + " " + plural(copies, "copy", "copies")
        })
      ]),
      element("dl", {class: "cs-routine__metrics"}, [
        metric("Likes", count(routine.likesCount)),
        metric("Saves", count(routine.savesCount)),
        metric("Rating", rating(routine))
      ])
    ]);
  }

  function mount(root, options) {
    if (root.creatorStatsApp) {
      root.creatorStatsApp.reload();
      return root.creatorStatsApp;
    }

    const token = options.token;

    const status = element("p", {class: "mc-status cs-status", role: "status", text: "Loading your statistics…"});
    const errorText = element("p", {class: "cs-error__text"});
    const retry = element("button", {class: "mc-btn mc-btn--ghost", type: "button", text: "Try again"});
    const error = element("div", {class: "cs-error", role: "alert", hidden: true}, [errorText, retry]);
    const body = element("div", {class: "cs-body", hidden: true});

    root.replaceChildren(status, error, body);

    let loading = null;

    function render(stats) {
      const routines = Array.isArray(stats.routines) ? stats.routines : [];
      const totalCopies = Number(stats.totalCopies) || 0;

      const overview = element("dl", {class: "cs-overview", "aria-label": "Overview"}, [
        overviewItem("Followers", stats.followersCount, "No followers yet"),
        overviewItem("Copies of your routines", totalCopies, "No copies yet"),
        overviewItem("Public routines", stats.totalPublicRoutines, "None published yet")
      ]);

      const heading = element("h2", {class: "cs-heading", id: "csRoutinesHeading", text: "Your public routines"});
      const parts = [overview, heading];

      if (routines.length === 0) {
        parts.push(element("div", {class: "mc-empty cs-empty", "data-empty": "routines"}, [
          element("p", {text: "You have no public routines yet. Make one of your routines public to see how often it is copied."}),
          element("a", {class: "mc-btn", href: "#my-routines", text: "Go to My Routines"})
        ]));
      } else {
        if (totalCopies === 0) {
          parts.push(element("p", {
            class: "cs-note",
            "data-empty": "copies",
            text: "None of your public routines has been copied yet."
          }));
        }

        parts.push(element("ul", {class: "cs-routines", "aria-labelledby": "csRoutinesHeading"},
            routines.map(routineItem)));
      }

      parts.push(element("p", {
        class: "mc-hint cs-footnote",
        text: "A copy is counted when another member copies or adapts one of your public routines. "
            + "Your own copies are not counted, and copies made before copy counting started were not recorded."
      }));

      body.replaceChildren(...parts);
      body.hidden = false;
    }

    function showError(message) {
      errorText.textContent = message;
      error.hidden = false;
      body.hidden = true;
    }

    async function load() {
      if (loading) {
        return loading;
      }

      status.hidden = false;
      error.hidden = true;
      root.setAttribute("aria-busy", "true");

      loading = (async () => {
        try {
          const response = await fetch("/users/me/creator-stats", {
            headers: {"Authorization": "Bearer " + token}
          });

          if (response.status === 401 || response.status === 403) {
            showError("Your session has ended. Please sign in again.");
            return;
          }

          if (!response.ok) {
            showError("Your statistics could not be loaded. Please try again.");
            return;
          }

          render(await response.json());
        } catch (failure) {
          showError("Your statistics could not be loaded. Please check your connection and try again.");
        } finally {
          status.hidden = true;
          root.removeAttribute("aria-busy");
          loading = null;
        }
      })();

      return loading;
    }

    retry.addEventListener("click", load);

    root.creatorStatsApp = {reload: load};
    load();

    return root.creatorStatsApp;
  }

  window.CopyMyLifeCreatorStats = Object.freeze({mount});
})();
