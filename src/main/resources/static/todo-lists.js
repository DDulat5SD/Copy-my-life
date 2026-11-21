// To-do lists: the one implementation behind todo-lists.html and the profile's To-do Lists section.
// mount() draws into its root once; mounting the same root again only reloads the lists.
(function () {

  // prefix keeps element ids unique when the profile shows this next to the posts section.
  function template(prefix, heading) {
    const id = (name) => `id="${prefix}${name}" data-part="${name}"`;

    return `
      ${heading ? `
      <div class="page-head">
        <h1 ${id("pageTitle")}>My to-do lists</h1>
        <p ${id("pageSubtitle")}>Plan your day. Each list is private until you make it public.</p>
      </div>` : ""}

      <p class="page-message" ${id("pageMessage")} role="alert" hidden></p>

      <form class="mc-card mc-form" ${id("newListForm")} novalidate hidden>
        <input class="mc-input" ${id("newListTitle")} name="title" maxlength="100" required
               placeholder="New list, e.g. Weekend errands" aria-label="List title">
        <label class="mc-check"><input type="checkbox" ${id("newListPublic")}> Public</label>
        <button class="mc-btn" type="submit">Create list</button>
      </form>

      <p class="mc-status" ${id("todoLoading")} role="status">Loading your lists…</p>
      <p class="mc-empty" ${id("todoEmpty")} hidden></p>

      <div ${id("todoLists")}></div>`;
  }

  function mount(root, options) {
    if (root.todoListsApp) {
      root.todoListsApp.reload();
      return root.todoListsApp;
    }

    const token = options.token;
    // A viewed user shows that user's public lists, read-only.
    const viewedUserId = options.viewedUserId == null ? null : Number(options.viewedUserId);
    // itemId shows that one list only, as the server allows it (its owner, or anyone when public).
    const itemId = options.itemId == null ? null : Number(options.itemId);
    const owner = viewedUserId === null && itemId === null;

    root.innerHTML = template(options.idPrefix || "", options.heading !== false);

    const $ = (name) => root.querySelector(`[data-part="${name}"]`);
    const esc = SafeDom.escapeHtml;

    let lists = [];
    let pendingDeleteId = null;
    let renamingId = null;

    async function request(url, requestOptions = {}) {
      const headers = {"Authorization": "Bearer " + token};

      if (requestOptions.body !== undefined) {
        headers["Content-Type"] = "application/json";
      }

      const response = await fetch(url, {
        method: requestOptions.method || "GET",
        headers,
        body: requestOptions.body === undefined ? undefined : JSON.stringify(requestOptions.body)
      });

      if (response.status === 401) {
        CopyMyLifeSession.sessionEnded();
        throw new Error("Your session has ended.");
      }

      const text = await response.text();
      const data = text ? JSON.parse(text) : null;

      if (!response.ok) {
        throw new Error((data && data.message) || "Something went wrong. Please try again.");
      }

      return data;
    }

    function showMessage(text) {
      $("pageMessage").textContent = text || "";
      $("pageMessage").hidden = !text;
    }

    function replace(updated) {
      lists = lists.map((list) => (list.id === updated.id ? updated : list));
    }

    async function load() {
      $("todoLoading").hidden = false;
      try {
        lists = itemId !== null
            ? [await request("/todo-lists/" + itemId)]
            : await request(owner ? "/todo-lists" : "/users/" + viewedUserId + "/todo-lists");
        showMessage("");
      } catch (error) {
        lists = [];
        showMessage(owner ? error.message
            : itemId !== null ? "This list is not available." : "This user's lists are not available.");
      }
      $("todoLoading").hidden = true;
      render();
    }

    function badge(list) {
      return list.isPublic
          ? `<span class="visibility-badge visibility-badge--public">Public</span>`
          : `<span class="visibility-badge visibility-badge--private">Private</span>`;
    }

    function task(list, item) {
      const id = Number(item.id);
      const box = owner
          ? `<input type="checkbox" data-action="toggle-task" data-task="${id}" ${item.done ? "checked" : ""}>`
          : `<input type="checkbox" disabled ${item.done ? "checked" : ""}>`;

      return `
        <li class="todo-task${item.done ? " todo-task--done" : ""}">
          <label>${box}<span class="todo-task__text">${esc(item.text)}</span></label>
          ${owner ? `<button type="button" class="mc-btn mc-btn--danger" data-action="delete-task" data-task="${id}"
                aria-label="Remove task ${esc(item.text)}">Remove</button>` : ""}
        </li>`;
    }

    function ownerControls(list) {
      const id = Number(list.id);

      if (pendingDeleteId === id) {
        return `
          <div class="confirm" role="group" aria-label="Confirm delete">
            <span>Delete “${esc(list.title)}” and all its tasks? This cannot be undone.</span>
            <button type="button" class="mc-btn mc-btn--danger-solid" data-action="confirm-delete">Delete</button>
            <button type="button" class="mc-btn mc-btn--ghost" data-action="cancel-delete">Cancel</button>
          </div>`;
      }

      return `
        <form class="mc-form add-task-form" novalidate>
          <input class="mc-input" name="text" maxlength="200" required placeholder="Add a task" aria-label="New task">
          <button class="mc-btn mc-btn--ghost" type="submit">Add task</button>
        </form>
        <div class="mc-actions">
          <button type="button" class="mc-btn mc-btn--ghost" data-action="toggle-visibility">
            ${list.isPublic ? "Make private" : "Make public"}
          </button>
          <button type="button" class="mc-btn mc-btn--ghost" data-action="rename">Rename</button>
          <button type="button" class="mc-btn mc-btn--danger" data-action="delete">Delete</button>
        </div>`;
    }

    function title(list) {
      if (owner && renamingId === Number(list.id)) {
        return `
          <form class="mc-form rename-form" novalidate>
            <input class="mc-input" name="title" maxlength="100" required value="${esc(list.title)}" aria-label="List title">
            <button class="mc-btn" type="submit">Save</button>
            <button class="mc-btn mc-btn--ghost" type="button" data-action="cancel-rename">Cancel</button>
          </form>`;
      }

      return `<h2 class="todo-list__title">${esc(list.title)}</h2>`;
    }

    function render() {
      $("newListForm").hidden = !owner;

      if (!owner && $("pageTitle")) {
        const author = lists[0] && lists[0].authorUsername ? "@" + lists[0].authorUsername : "this user";
        $("pageTitle").textContent = "Public to-do lists";
        $("pageSubtitle").textContent = "Lists shared by " + author + ". You can read them but not change them.";
      }

      $("todoEmpty").textContent = owner
          ? "You have no to-do lists yet. Create your first list above."
          : "No public to-do lists yet.";
      $("todoEmpty").hidden = lists.length > 0;

      $("todoLists").innerHTML = lists.map((list) => `
        <article class="mc-card todo-list" data-id="${Number(list.id)}">
          <div class="mc-head">
            ${title(list)}
            ${badge(list)}
          </div>
          ${list.tasks.length
              ? `<ul class="todo-tasks">${list.tasks.map((item) => task(list, item)).join("")}</ul>`
              : `<p class="mc-hint">No tasks yet.</p>`}
          ${owner ? ownerControls(list) : ""}
        </article>`).join("");
    }

    async function run(action) {
      try {
        await action();
        showMessage("");
      } catch (error) {
        showMessage(error.message);
      }
      render();
    }

    // An empty field is explained right under it, never in a browser bubble.
    function fieldError(formElement, text) {
      const input = formElement.querySelector(".mc-input, .mc-textarea");
      let error = formElement.querySelector(".mc-field-error");

      if (!error) {
        error = document.createElement("p");
        error.className = "mc-field-error";
        error.setAttribute("role", "alert");
        formElement.appendChild(error);
      }

      error.textContent = text;
      error.hidden = !text;
      input.setAttribute("aria-invalid", String(Boolean(text)));

      if (text) {
        input.focus();
      }

      return !text;
    }

    $("newListForm").addEventListener("submit", (event) => {
      event.preventDefault();
      if (!fieldError(event.target, $("newListTitle").value.trim() ? "" : "Give the list a title.")) return;
      run(async () => {
        const created = await request("/todo-lists", {
          method: "POST",
          body: {title: $("newListTitle").value, isPublic: $("newListPublic").checked}
        });
        lists = [created, ...lists];
        $("newListTitle").value = "";
        $("newListPublic").checked = false;
      });
    });

    $("todoLists").addEventListener("submit", (event) => {
      event.preventDefault();
      const card = event.target.closest(".todo-list");
      const id = Number(card.dataset.id);
      const input = event.target.querySelector("input");

      if (!fieldError(event.target, input.value.trim() ? ""
          : event.target.classList.contains("add-task-form") ? "Write what the task is." : "Give the list a title.")) {
        return;
      }

      if (event.target.classList.contains("add-task-form")) {
        run(async () => replace(await request("/todo-lists/" + id + "/tasks", {method: "POST", body: {text: input.value}})));
      } else if (event.target.classList.contains("rename-form")) {
        run(async () => {
          replace(await request("/todo-lists/" + id, {method: "PUT", body: {title: input.value}}));
          renamingId = null;
        });
      }
    });

    $("todoLists").addEventListener("change", (event) => {
      const box = event.target.closest('[data-action="toggle-task"]');
      if (!box) return;
      const id = Number(box.closest(".todo-list").dataset.id);
      run(async () => replace(await request("/todo-lists/" + id + "/tasks/" + Number(box.dataset.task),
          {method: "PUT", body: {done: box.checked}})));
    });

    $("todoLists").addEventListener("click", (event) => {
      const button = event.target.closest("button[data-action]");
      if (!button) return;
      const id = Number(button.closest(".todo-list").dataset.id);
      const list = lists.find((item) => item.id === id);

      switch (button.dataset.action) {
        case "toggle-visibility":
          run(async () => replace(await request("/todo-lists/" + id, {method: "PUT", body: {isPublic: !list.isPublic}})));
          break;
        case "rename":
          renamingId = id;
          render();
          break;
        case "cancel-rename":
          renamingId = null;
          render();
          break;
        case "delete":
          pendingDeleteId = id;
          render();
          break;
        case "cancel-delete":
          pendingDeleteId = null;
          render();
          break;
        case "confirm-delete":
          run(async () => {
            await request("/todo-lists/" + id, {method: "DELETE"});
            lists = lists.filter((item) => item.id !== id);
            pendingDeleteId = null;
          });
          break;
        case "delete-task":
          run(async () => replace(await request("/todo-lists/" + id + "/tasks/" + Number(button.dataset.task), {method: "DELETE"})));
          break;
      }
    });

    root.todoListsApp = {reload: load};
    load();
    return root.todoListsApp;
  }

  window.CopyMyLifeTodoLists = {mount};
})();
