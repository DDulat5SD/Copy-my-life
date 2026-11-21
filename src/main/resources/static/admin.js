
const adminPages = {
    "admin-dashboard.html": ["dashboard", "Dashboard", "Overview of CopyMyLife"],
    "admin-users.html": ["users", "Users", "Manage all registered accounts"],
    "admin-routines.html": ["routines", "Routines", "Manage user routines and their habits"],
    "admin-categories.html": ["categories", "Categories", "Manage the categories routines are filed under"],
    "admin-habits.html": ["habits", "Habits", "Manage users' habit libraries"],
    "admin-posts.html": ["posts", "Posts", "Edit, hide and remove posts"],
    "admin-comments.html": ["comments", "Comments", "Review and remove comments"],
    "admin-reports.html": ["reports", "Reports", "Review community reports"]
};

const currentFile = location.pathname.split("/").pop();
const currentPage = adminPages[currentFile] || adminPages["admin-dashboard.html"];
const adminToken = sessionStorage.getItem("adminToken");
const $ = id => document.getElementById(id);


function adminConfirm({
                          title,
                          message,
                          confirmText = "Delete",
                          tone = "danger"
                      }) {
    return new Promise(resolve => {
        const previousFocus = document.activeElement;
        const previousOverflow = document.body.style.overflow;

        const backdrop = document.createElement("div");
        backdrop.className = "admin-confirm-backdrop";

        backdrop.innerHTML = `
            <div class="admin-confirm-modal"
                 role="dialog"
                 aria-modal="true"
                 aria-labelledby="adminConfirmTitle"
                 aria-describedby="adminConfirmMessage">

                <div class="admin-confirm-icon">!</div>

                <h2 class="admin-confirm-title"
                    id="adminConfirmTitle"></h2>

                <p class="admin-confirm-message"
                   id="adminConfirmMessage"></p>

                <div class="admin-confirm-actions">
                    <button type="button"
                            class="admin-confirm-btn admin-confirm-cancel">
                        Cancel
                    </button>

                    <button type="button"
                            class="admin-confirm-btn admin-confirm-submit">
                    </button>
                </div>
            </div>
        `;

        const titleElement =
            backdrop.querySelector(".admin-confirm-title");

        const messageElement =
            backdrop.querySelector(".admin-confirm-message");

        const iconElement =
            backdrop.querySelector(".admin-confirm-icon");

        const cancelButton =
            backdrop.querySelector(".admin-confirm-cancel");

        const confirmButton =
            backdrop.querySelector(".admin-confirm-submit");

        titleElement.textContent = title;
        messageElement.textContent = message;
        confirmButton.textContent = confirmText;

        if (tone === "success" || tone === "primary") {
            iconElement.classList.add(tone);
            confirmButton.classList.add(tone);
        }

        function close(result) {
            document.removeEventListener("keydown", handleKeydown);
            document.body.style.overflow = previousOverflow;
            backdrop.remove();

            if (previousFocus && previousFocus.isConnected) {
                previousFocus.focus();
            }

            resolve(result);
        }

        function handleKeydown(event) {
            if (event.key === "Escape") {
                event.preventDefault();
                close(false);
            }

            if (event.key === "Tab") {
                if (event.shiftKey &&
                    document.activeElement === cancelButton) {
                    event.preventDefault();
                    confirmButton.focus();
                } else if (!event.shiftKey &&
                    document.activeElement === confirmButton) {
                    event.preventDefault();
                    cancelButton.focus();
                }
            }
        }

        cancelButton.addEventListener("click", () => close(false));
        confirmButton.addEventListener("click", () => close(true));

        backdrop.addEventListener("click", event => {
            if (event.target === backdrop) {
                close(false);
            }
        });

        document.body.appendChild(backdrop);
        document.body.style.overflow = "hidden";
        document.addEventListener("keydown", handleKeydown);

        cancelButton.focus();
    });
}


function adminEditHabit(habit) {
    return new Promise(resolve => {
        const previousFocus = document.activeElement;
        const previousOverflow = document.body.style.overflow;

        const backdrop = document.createElement("div");
        backdrop.className = "admin-confirm-backdrop";

        backdrop.innerHTML = `
            <div class="admin-confirm-modal admin-edit-modal"
                 role="dialog"
                 aria-modal="true"
                 aria-labelledby="editHabitHeading">

                <div class="admin-confirm-icon primary">✎</div>

                <h2 class="admin-confirm-title"
                    id="editHabitHeading">
                    Edit habit
                </h2>

                <p class="admin-confirm-message">
                    Update habit information
                </p>

                <form class="admin-edit-form" novalidate>
                    <label class="admin-edit-label">
                        Habit title
                    </label>
                    <input class="admin-edit-field"
                           name="title"
                           maxlength="255"
                           required>

                    <label class="admin-edit-label">
                        Description
                    </label>
                    <textarea class="admin-edit-field"
                              name="description"
                              maxlength="500"></textarea>

                    <label class="admin-edit-label">
                        Duration (minutes)
                    </label>
                    <input class="admin-edit-field"
                           name="duration"
                           type="number"
                           min="1"
                           step="1"
                           required>

                    <div class="admin-confirm-actions">
                        <button type="button"
                                class="admin-confirm-btn admin-confirm-cancel">
                            Cancel
                        </button>

                        <button type="submit"
                                class="admin-confirm-btn admin-confirm-submit primary">
                            Save
                        </button>
                    </div>
                </form>
            </div>
        `;

        const form = backdrop.querySelector("form");
        const title = form.elements.namedItem("title");
        const description = form.elements.namedItem("description");
        const duration = form.elements.namedItem("duration");
        const cancel = backdrop.querySelector(".admin-confirm-cancel");

        title.value = habit.title || "";
        description.value = habit.description || "";
        duration.value = habit.durationMinutes ?? "";

        function close(result) {
            document.removeEventListener("keydown", handleKeydown);
            document.body.style.overflow = previousOverflow;
            backdrop.remove();

            if (previousFocus && previousFocus.isConnected) {
                previousFocus.focus();
            }

            resolve(result);
        }

        function handleKeydown(event) {
            if (event.key === "Escape") {
                event.preventDefault();
                close(null);
            }

            if (event.key === "Tab") {
                const focusable = [
                    title,
                    description,
                    duration,
                    cancel,
                    form.querySelector('[type="submit"]')
                ];

                const first = focusable[0];
                const last = focusable[focusable.length - 1];

                if (event.shiftKey && document.activeElement === first) {
                    event.preventDefault();
                    last.focus();
                } else if (!event.shiftKey &&
                    document.activeElement === last) {
                    event.preventDefault();
                    first.focus();
                }
            }
        }

        cancel.addEventListener("click", () => close(null));

        backdrop.addEventListener("click", event => {
            if (event.target === backdrop) {
                close(null);
            }
        });

        form.addEventListener("submit", event => {
            event.preventDefault();

            if (!checkRequiredFields(form)) return;

            close({
                title: title.value.trim(),
                description: description.value.trim(),
                durationMinutes: Number(duration.value)
            });
        });

        document.body.appendChild(backdrop);
        document.body.style.overflow = "hidden";
        document.addEventListener("keydown", handleKeydown);

        title.focus();
    });
}



// US-25: a form in the same dialog as the habit editor. fields: [{name, label, type, value,
// required, maxlength, options}], type "text", "textarea" or "select". Resolves with the values
// by name, or null when cancelled. Values are set as properties, never written as HTML.
function adminForm({heading, message, fields, submitText = "Save"}) {
    return new Promise(resolve => {
        const previousFocus = document.activeElement;
        const previousOverflow = document.body.style.overflow;

        const backdrop = document.createElement("div");
        backdrop.className = "admin-confirm-backdrop";
        backdrop.innerHTML = `
            <div class="admin-confirm-modal admin-edit-modal"
                 role="dialog"
                 aria-modal="true"
                 aria-labelledby="adminFormHeading">
                <div class="admin-confirm-icon primary">✎</div>
                <h2 class="admin-confirm-title" id="adminFormHeading"></h2>
                <p class="admin-confirm-message"></p>
                <form class="admin-edit-form" novalidate>
                    <div class="admin-form-fields"></div>
                    <div class="admin-confirm-actions">
                        <button type="button" class="admin-confirm-btn admin-confirm-cancel">Cancel</button>
                        <button type="submit" class="admin-confirm-btn admin-confirm-submit primary"></button>
                    </div>
                </form>
            </div>
        `;

        backdrop.querySelector(".admin-confirm-title").textContent = heading;
        backdrop.querySelector(".admin-confirm-message").textContent = message || "";
        backdrop.querySelector(".admin-confirm-submit").textContent = submitText;

        const form = backdrop.querySelector("form");
        const holder = backdrop.querySelector(".admin-form-fields");

        fields.forEach((field, index) => {
            const id = "adminFormField" + index;
            const label = document.createElement("label");
            label.className = "admin-edit-label";
            label.htmlFor = id;
            label.textContent = field.label;

            const input = document.createElement(field.type === "textarea" || field.type === "select" ? field.type : "input");
            input.className = "admin-edit-field";
            input.id = id;
            input.name = field.name;

            if (field.type === "select") {
                (field.options || []).forEach(option => input.add(new Option(option, option)));
            }

            if (field.maxlength) input.maxLength = field.maxlength;
            if (field.required) input.required = true;
            input.value = field.value ?? "";

            holder.append(label, input);
        });

        const cancel = backdrop.querySelector(".admin-confirm-cancel");

        function close(result) {
            document.removeEventListener("keydown", handleKeydown);
            document.body.style.overflow = previousOverflow;
            backdrop.remove();

            if (previousFocus && previousFocus.isConnected) {
                previousFocus.focus();
            }

            resolve(result);
        }

        function handleKeydown(event) {
            if (event.key === "Escape") {
                event.preventDefault();
                close(null);
            }
        }

        cancel.addEventListener("click", () => close(null));
        backdrop.addEventListener("click", event => {
            if (event.target === backdrop) close(null);
        });

        form.addEventListener("submit", event => {
            event.preventDefault();

            if (!checkRequiredFields(form)) return;

            const values = {};
            fields.forEach(field => {
                values[field.name] = form.elements.namedItem(field.name).value.trim();
            });
            close(values);
        });

        document.body.appendChild(backdrop);
        document.body.style.overflow = "hidden";
        document.addEventListener("keydown", handleKeydown);

        holder.querySelector(".admin-edit-field")?.focus();
    });
}

// Explains each empty or out-of-range required field right under it, instead of a browser bubble.
function checkRequiredFields(form) {
    form.querySelectorAll(".admin-field-error").forEach(error => error.remove());

    const invalid = [...form.querySelectorAll("[required]")].filter(field => {
        const value = field.value.trim();
        const min = Number(field.min || 1);
        let message = "";

        if (!value) {
            message = "This field is required.";
        } else if (field.type === "number" &&
            (!Number.isInteger(Number(value)) || Number(value) < min)) {
            message = `Enter a whole number of at least ${min}.`;
        }

        field.setAttribute("aria-invalid", String(Boolean(message)));

        if (message) {
            const error = document.createElement("p");
            error.className = "admin-field-error";
            error.setAttribute("role", "alert");
            error.textContent = message;
            field.insertAdjacentElement("afterend", error);
        }

        return Boolean(message);
    });

    if (invalid.length) {
        invalid[0].focus();
    }

    return invalid.length === 0;
}

const safe = value => String(value ?? "").replace(
    /[&<>"']/g,
    char => ({
        "&": "&amp;",
        "<": "&lt;",
        ">": "&gt;",
        '"': "&quot;",
        "'": "&#39;"
    })[char]
);

const icons = {
    dashboard: '<rect x="3" y="3" width="7" height="7" rx="1"/><rect x="14" y="3" width="7" height="7" rx="1"/><rect x="3" y="14" width="7" height="7" rx="1"/><rect x="14" y="14" width="7" height="7" rx="1"/>',
    users: '<circle cx="9" cy="7" r="4"/><path d="M2 21v-2a6 6 0 0 1 6-6h2a6 6 0 0 1 6 6v2"/><path d="M17 4a4 4 0 0 1 0 8M19 16a5 5 0 0 1 3 5"/>',
    routines: '<rect x="3" y="5" width="18" height="16" rx="2"/><path d="M8 3v4M16 3v4M3 10h18"/>',
    habits: '<path d="M20 6 9 17l-5-5"/>',
    comments: '<path d="M21 11.5a8.5 8.5 0 0 1-8.5 8.5 9 9 0 0 1-4-.9L3 21l1.9-5.5A9 9 0 1 1 21 11.5Z"/>',
    posts: '<rect x="3" y="3" width="18" height="18" rx="2"/><circle cx="8.5" cy="8.5" r="1.5"/><path d="m21 15-5-5L5 21"/>',
    categories: '<path d="M20.6 13.4 13.4 20.6a2 2 0 0 1-2.8 0L3 13V3h10l7.6 7.6a2 2 0 0 1 0 2.8Z"/><path d="M7.5 7.5h.01"/>',
    reports: '<path d="m12 3 10 18H2L12 3Z"/><path d="M12 9v5M12 18h.01"/>'
};

const icon = name => `
    <svg viewBox="0 0 24 24"
         fill="none"
         stroke="currentColor"
         stroke-width="2"
         stroke-linecap="round"
         stroke-linejoin="round">
        ${icons[name]}
    </svg>
`;

const linkFor = name => `admin-${name}.html`;

function renderShell() {
    const links = Object.values(adminPages).map(([key, label]) => `
        <a class="side-link ${currentPage[0] === key ? "active" : ""}"
           href="${linkFor(key)}">
            <span class="side-icon ico-${key}">${icon(key)}</span>
            <span>${label}</span>
            <span class="side-arrow">›</span>
        </a>
    `).join("");

    $("adminApp").innerHTML = `
        <header class="topbar">
            <a class="brand" href="admin-dashboard.html">
                <img src="https://i.ibb.co/chRY80xC/image.png"
                     alt="CopyMyLife">
                <span>CopyMyLife</span>
            </a>
            <span class="portal-label">Admin Portal</span>
        </header>

        <div class="page">
            <aside class="sidebar">
                <div class="admin-profile">
                    <div class="admin-avatar">A</div>
                    <h2>Administrator</h2>
                    <p id="adminEmail"></p>
                    <button type="button"
                            class="logout"
                            id="logoutBtn">
                        ⎋ &nbsp; Log out
                    </button>
                </div>

                ${links}
            </aside>

            <main class="content">
                <div class="page-heading">
                    <div>
                        <h1>${currentPage[1]}</h1>
                        <p>${currentPage[2]}</p>
                    </div>
                </div>
                <div id="notice" class="message" role="status"></div>
                <div id="pageContent">
                    <div id="loading">Loading...</div>
                </div>
            </main>
        </div>
    `;

    $("logoutBtn").addEventListener("click", () => {
        sessionStorage.removeItem("adminToken");
        location.replace("admin-login.html");
    });
}

function notice(message, success = false) {
    $("notice").textContent = message;
    $("notice").className = success ? "message success" : "message";
}

async function api(path, options = {}) {
    const response = await fetch(`/admin-api/${path}`, {
        ...options,
        headers: {
            "Authorization": `Bearer ${adminToken}`,
            ...(options.body ? {"Content-Type": "application/json"} : {}),
            ...(options.headers || {})
        }
    });

    if (response.status === 401 || response.status === 403) {
        sessionStorage.removeItem("adminToken");
        location.replace("admin-login.html");
        throw new Error("Administrator access required");
    }

    if (!response.ok) {
        const text = await response.text();
        let message = `Request failed (${response.status})`;

        try {
            message = JSON.parse(text).message || message;
        } catch (_) {}

        throw new Error(message);
    }

    if (response.status === 204) return null;

    return response.json();
}

function table(headers, rows) {
    if (!rows.length) {
        return `<div class="empty">No records found</div>`;
    }

    return `
        <div class="table-wrap">
            <table>
                <thead>
                    <tr>
                        ${headers.map(h => `<th>${h}</th>`).join("")}
                    </tr>
                </thead>
                <tbody>${rows.join("")}</tbody>
            </table>
        </div>
    `;
}

function status(value) {
    const ok = value === "Active" || value === "RESOLVED" || value === "Public";
    const bad = value === "Banned" || value === "REJECTED" || value === "Hidden";

    return `<span class="tag ${ok ? "ok" : bad ? "bad" : ""}">
        ${safe(value)}
    </span>`;
}

function searchInput(placeholder) {
    return `
        <div class="tools">
            <input class="field search"
                   id="searchInput"
                   type="search"
                   placeholder="${safe(placeholder)}">
        </div>
    `;
}

function attachSearch(draw) {
    $("searchInput").addEventListener("input", draw);
}

function matches(row, query) {
    return JSON.stringify(row)
        .toLowerCase()
        .includes(query.toLowerCase());
}

function button(label, action, id, className = "") {
    return `
        <button type="button"
                class="action ${className}"
                data-action="${action}"
                data-id="${id}">
            ${label}
        </button>
    `;
}

function jsonBody(data) {
    return {body: JSON.stringify(data)};
}

async function dashboardPage() {
    const users = await api("users");

    const data = await Promise.allSettled([
        api("routines"),
        api("habits"),
        api("comments"),
        api("reports"),
        api("posts")
    ]);

    const count = index =>
        data[index].status === "fulfilled"
            ? data[index].value.length
            : "—";

    const stats = [
        ["Total accounts", users.length],
        ["Active accounts", users.filter(user => !user.banned).length],
        ["Banned accounts", users.filter(user => user.banned).length],
        ["Routines", count(0)],
        ["Habits", count(1)],
        ["Comments", count(2)],
        ["Reports", count(3)],
        ["Posts", count(4)]
    ];

    $("pageContent").innerHTML = `
        <div class="stats">
            ${stats.map(([label, value]) => `
                <div class="card">
                    <div class="stat-label">${label}</div>
                    <div class="stat-number">${value}</div>
                </div>
            `).join("")}
        </div>

        <div class="card">
            <h2>Management</h2>
            <p class="muted">
                Choose a section on the left to manage your site.
            </p>
        </div>
    `;
}

async function usersPage() {
    let users = await api("users");

    $("pageContent").innerHTML = `
        <div class="card">
            ${searchInput("Search by name, username or email...")}
            <div id="userTable"></div>
        </div>
    `;

    function draw() {
        const query = $("searchInput").value;

        const rows = users
            .filter(user => matches([
                user.name,
                user.username,
                user.email,
                user.id
            ], query))
            .map(user => `
                <tr>
                    <td>${user.id}</td>
                    <td>
                        <a href="admin-profile.html?id=${user.id}" class="admin-user-link">
                            ${safe(user.name || "Unnamed user")}
                        </a>
                    </td>
                    <td>${safe(user.username)}</td>
                    <td>${safe(user.email)}</td>
                    <td>${safe(user.role)}</td>
                    <td>${status(user.banned ? "Banned" : "Active")}</td>
                    <td>
                        ${user.role === "ADMIN"
                ? "Protected"
                : button(
                    user.banned ? "Unban" : "Ban",
                    user.banned ? "unban" : "ban",
                    user.id,
                    user.banned ? "green" : "danger"
                )}
                    </td>
                </tr>
            `);

        $("userTable").innerHTML = table(
            ["ID", "Name", "Username", "Email", "Role", "Status", "Action"],
            rows
        );
    }

    attachSearch(draw);

    $("userTable").addEventListener("click", async event => {
        const target = event.target.closest("[data-action]");
        if (!target) return;

        const user = users.find(
            item => String(item.id) === target.dataset.id
        );

        if (!user) return;

        const isBan = target.dataset.action === "ban";

        const confirmed = await adminConfirm({
            title: isBan ? "Ban user?" : "Unban user?",
            message: isBan
                ? `Are you sure you want to ban "${user.username || user.email}"?`
                : `Are you sure you want to unban "${user.username || user.email}"?`,
            confirmText: isBan ? "Ban" : "Unban",
            tone: isBan ? "danger" : "success"
        });

        if (!confirmed) return;

        target.disabled = true;

        try {
            const updated = await api(
                `users/${user.id}/${target.dataset.action}`,
                {method: "PUT"}
            );

            users = users.map(item =>
                item.id === updated.id ? updated : item
            );

            draw();
            notice("Account updated", true);
        } catch (error) {
            target.disabled = false;
            notice(error.message);
        }
    });

    draw();
}

async function routinesPage() {
    let routines = await api("routines");

    $("pageContent").innerHTML = `
        <div class="card">
            ${searchInput("Search routine, owner or email...")}
            <div id="routinesTable"></div>
        </div>
        <div id="routineDetails"></div>
    `;

    function draw() {
        const query = $("searchInput").value;

        const rows = routines
            .filter(r => matches([
                r.id, r.title, r.ownerName,
                r.ownerEmail, r.ownerUsername
            ], query))
            .map(r => `
                <tr data-routine="${r.id}">
                    <td>${r.id}</td>
                    <td>
                        ${safe(r.title)}
                        <div class="muted">${safe(r.category)}</div>
                    </td>
                    <td>
                        ${safe(r.ownerName)}
                        <div class="muted">${safe(r.ownerEmail)}</div>
                    </td>
                    <td>${safe(r.durationMinutes)} min</td>
                    <td>${r.usedMinutes} min / ${r.habitCount} habits</td>
                    <td class="routine-status">${status(r.hidden ? "Hidden" : r.publicRoutine || r.builtIn ? "Public" : "Private")}</td>
                    <td>
                        <div class="actions">
                            ${button("Manage", "manage", r.id)}
                            ${button("Edit", "edit", r.id)}
                            ${r.hidden
                ? button("Unhide", "unhide", r.id, "green")
                : button("Hide", "hide", r.id)}
                            ${button("Delete", "delete", r.id, "danger")}
                        </div>
                    </td>
                </tr>
            `);

        $("routinesTable").innerHTML = table(
            ["ID", "Routine", "Owner", "Duration", "Usage", "Status", "Action"],
            rows
        );
    }

    attachSearch(draw);

    $("routinesTable").addEventListener("click", async event => {
        const btn = event.target.closest("[data-action]");
        if (!btn) return;

        const routine = routines.find(
            r => String(r.id) === btn.dataset.id
        );

        if (!routine) return;

        if (btn.dataset.action === "manage") {
            return openRoutine(routine.id);
        }

        if (btn.dataset.action === "edit") {
            return editRoutine(routine);
        }

        if (btn.dataset.action === "hide" || btn.dataset.action === "unhide") {
            return setHidden(routine, btn);
        }

        const confirmed = await adminConfirm({
            title: "Delete routine?",
            message: `Are you sure you want to delete "${routine.title}"?`,
            confirmText: "Delete",
            tone: "danger"
        });

        if (!confirmed) return;

        btn.disabled = true;

        try {
            await api(`routines/${routine.id}`, {method: "DELETE"});

            routines = routines.filter(r => r.id !== routine.id);
            $("routineDetails").replaceChildren();

            draw();
            notice("Routine removed from the site", true);
        } catch (error) {
            btn.disabled = false;
            notice(error.message);
        }
    });

    // US-25: the title, description and category the public pages show.
    async function editRoutine(routine) {
        let categories = [];

        try {
            categories = (await api("categories")).map(category => category.name);
        } catch (error) {
            notice(error.message);
            return;
        }

        // A routine may keep a category the list no longer offers, such as Halloween.
        if (routine.category && !categories.includes(routine.category)) {
            categories.push(routine.category);
        }

        const values = await adminForm({
            heading: "Edit routine",
            message: "Changes show on the public pages straight away.",
            fields: [
                {name: "title", label: "Title", value: routine.title, required: true, maxlength: 100},
                {name: "description", label: "Description", type: "textarea", value: routine.description, maxlength: 500},
                {name: "category", label: "Category", type: "select", value: routine.category, options: categories}
            ]
        });

        if (!values) return;

        try {
            const updated = await api(`routines/${routine.id}`, {method: "PUT", ...jsonBody(values)});
            routines = routines.map(r => r.id === updated.id ? updated : r);
            draw();
            notice("Routine updated", true);
        } catch (error) {
            notice(error.message);
        }
    }

    // US-25: hidden routines leave every public page; the owner keeps them.
    async function setHidden(routine, btn) {
        const hide = btn.dataset.action === "hide";
        const confirmed = await adminConfirm({
            title: hide ? "Hide routine?" : "Show routine again?",
            message: hide
                ? `"${routine.title}" will no longer be shown to anyone but its owner.`
                : `"${routine.title}" will be shown again as its owner set it.`,
            confirmText: hide ? "Hide" : "Show",
            tone: hide ? "danger" : "success"
        });

        if (!confirmed) return;

        btn.disabled = true;

        try {
            const updated = await api(`routines/${routine.id}/${btn.dataset.action}`, {method: "PUT"});
            routines = routines.map(r => r.id === updated.id ? updated : r);
            draw();
            notice(hide ? "Routine hidden from the public pages" : "Routine shown again", true);
        } catch (error) {
            btn.disabled = false;
            notice(error.message);
        }
    }

    async function openRoutine(id) {
        try {
            const [links, available] = await Promise.all([
                api(`routines/${id}/habits`),
                api(`routines/${id}/available-habits`)
            ]);

            const routine = routines.find(r => r.id === id);

            const options = available.map(h => `
                <option value="${h.id}">
                    ${safe(h.title)} (${h.durationMinutes} min)
                </option>
            `).join("");

            const rows = links.map(link => `
                <tr>
                    <td>${link.routineHabitId}</td>
                    <td>${safe(link.title)}</td>
                    <td>${link.durationMinutes} min</td>
                    <td>
                        ${button(
                "Remove", "remove",
                link.routineHabitId, "danger"
            )}
                    </td>
                </tr>
            `);

            $("routineDetails").innerHTML = `
                <div class="card">
                    <h2>${safe(routine.title)} — Habits</h2>
                    <p class="muted">
                        Owner: ${safe(routine.ownerEmail)}
                    </p>

                    <div class="detail">
                        <form id="attachForm" class="form-row" novalidate>
                            <div class="form-group">
                                <label>Existing habit</label>
                                <select id="existingHabit"
                                        class="field" required>
                                    ${options}
                                </select>
                            </div>
                            <button class="action"
                                    ${options ? "" : "disabled"}>
                                Add habit
                            </button>
                        </form>

                        <form id="createHabitForm" class="form-row" novalidate>
                            <div class="form-group">
                                <label>New habit title</label>
                                <input id="newHabitTitle"
                                       class="field" required>
                            </div>
                            <div class="form-group">
                                <label>Minutes</label>
                                <input id="newHabitMinutes"
                                       class="field"
                                       type="number"
                                       min="1" required>
                            </div>
                            <button class="action">
                                Create & add
                            </button>
                        </form>

                        ${table(
                ["Occurrence ID", "Habit", "Duration", "Action"],
                rows
            )}
                    </div>
                </div>
            `;

            $("attachForm").addEventListener("submit", async event => {
                event.preventDefault();

                if (!checkRequiredFields(event.target)) return;

                await changeRoutine(
                    () => api(
                        `routines/${id}/habits/${$("existingHabit").value}`,
                        {method: "POST"}
                    ),
                    id
                );
            });

            $("createHabitForm").addEventListener("submit", async event => {
                event.preventDefault();

                if (!checkRequiredFields(event.target)) return;

                const data = {
                    title: $("newHabitTitle").value,
                    description: "",
                    durationMinutes: Number($("newHabitMinutes").value)
                };

                await changeRoutine(
                    () => api(
                        `routines/${id}/habits/create`,
                        {method: "POST", ...jsonBody(data)}
                    ),
                    id
                );
            });

            $("routineDetails")
                .querySelectorAll("[data-action='remove']")
                .forEach(btn => btn.addEventListener("click", async () => {
                    const confirmed = await adminConfirm({
                        title: "Remove habit?",
                        message: "Are you sure you want to remove this habit from the routine?",
                        confirmText: "Remove",
                        tone: "danger"
                    });

                    if (!confirmed) return;

                    await changeRoutine(
                        () => api(
                            `routines/${id}/habits/${btn.dataset.id}`,
                            {method: "DELETE"}
                        ),
                        id
                    );
                }));

            $("routineDetails").scrollIntoView({
                behavior: "smooth",
                block: "start"
            });

        } catch (error) {
            notice(error.message);
        }
    }

    async function changeRoutine(work, id) {
        try {
            await work();

            const updated = await api(`routines/${id}`);
            routines = routines.map(r => r.id === id ? updated : r);

            draw();
            await openRoutine(id);

            notice("Routine updated", true);
        } catch (error) {
            notice(error.message);
        }
    }

    draw();
}

async function habitsPage() {
    let habits = await api("habits");

    $("pageContent").innerHTML = `
        <div class="card">
            <h2>Create habit for a user</h2>

            <form id="createForm" class="form-row" novalidate>
                <div class="form-group">
                    <label>User ID</label>
                    <input id="userId" type="number" min="1"
                           required class="field">
                </div>
                <div class="form-group">
                    <label>Title</label>
                    <input id="title" required class="field">
                </div>
                <div class="form-group">
                    <label>Description</label>
                    <input id="description" class="field">
                </div>
                <div class="form-group">
                    <label>Minutes</label>
                    <input id="duration" type="number" min="1"
                           required class="field">
                </div>
                <button class="action">Create habit</button>
            </form>
        </div>

        <div class="card">
            ${searchInput("Search habit or owner...")}
            <div id="habitTable"></div>
        </div>
    `;

    function draw() {
        const query = $("searchInput").value;

        const rows = habits
            .filter(h => matches([
                h.id, h.title, h.ownerName,
                h.ownerUsername, h.ownerEmail
            ], query))
            .map(h => `
                <tr>
                    <td>${h.id}</td>
                    <td>${safe(h.title)}</td>
                    <td>
                        ${safe(h.ownerName)}
                        <div class="muted">${safe(h.ownerEmail)}</div>
                    </td>
                    <td>${h.durationMinutes} min</td>
                    <td>${h.linkedOccurrences}</td>
                    <td>
                        <div class="actions">
                            ${button("Edit", "edit", h.id, "light")}
                            ${button("Delete", "delete", h.id, "danger")}
                        </div>
                    </td>
                </tr>
            `);

        $("habitTable").innerHTML = table(
            ["ID", "Habit", "Owner", "Duration", "Linked", "Actions"],
            rows
        );
    }

    attachSearch(draw);

    $("createForm").addEventListener("submit", async event => {
        event.preventDefault();

        if (!checkRequiredFields(event.target)) return;

        const body = {
            userId: Number($("userId").value),
            title: $("title").value,
            description: $("description").value,
            durationMinutes: Number($("duration").value)
        };

        try {
            await api("habits", {
                method: "POST",
                ...jsonBody(body)
            });

            habits = await api("habits");
            draw();
            event.target.reset();
            notice("Habit created", true);
        } catch (error) {
            notice(error.message);
        }
    });

    $("habitTable").addEventListener("click", async event => {
        const btn = event.target.closest("[data-action]");
        if (!btn) return;

        const habit = habits.find(
            h => String(h.id) === btn.dataset.id
        );

        if (!habit) return;

        try {
            if (btn.dataset.action === "delete") {
                const confirmed = await adminConfirm({
                    title: "Delete habit?",
                    message: `Are you sure you want to delete "${habit.title}"?`,
                    confirmText: "Delete",
                    tone: "danger"
                });

                if (!confirmed) return;

                await api(`habits/${habit.id}`, {
                    method: "DELETE"
                });
            } else {
                const changes = await adminEditHabit(habit);

                if (!changes) return;

                await api(`habits/${habit.id}`, {
                    method: "PUT",
                    ...jsonBody(changes)
                });
            }

            habits = await api("habits");
            draw();
            notice("Habit updated", true);
        } catch (error) {
            notice(error.message);
        }
    });

    draw();
}

async function commentsPage() {
    let comments = await api("comments");

    $("pageContent").innerHTML = `
        <div class="card">
            ${searchInput("Search text, routine or user...")}
            <div id="commentsTable"></div>
        </div>
    `;

    function draw() {
        const query = $("searchInput").value;

        const rows = comments
            .filter(c => matches([
                c.id, c.text, c.routineTitle,
                c.username, c.userEmail
            ], query))
            .map(c => `
                <tr>
                    <td>${c.id}</td>
                    <td>${safe(c.text)}</td>
                    <td>
                        ${safe(c.userName)}
                        <div class="muted">${safe(c.userEmail)}</div>
                    </td>
                    <td>${safe(c.routineTitle)}</td>
                    <td>${status(c.deleted ? "Removed" : "Visible")}</td>
                    <td>
                        ${c.deleted
                ? "Removed"
                : button("Remove", "remove", c.id, "danger")}
                    </td>
                </tr>
            `);

        $("commentsTable").innerHTML = table(
            ["ID", "Comment", "Author", "Routine", "Status", "Action"],
            rows
        );
    }

    attachSearch(draw);

    $("commentsTable").addEventListener("click", async event => {
        const btn = event.target.closest("[data-action]");

        if (!btn) return;

        const confirmed = await adminConfirm({
            title: "Remove comment?",
            message: "Are you sure you want to remove this comment?",
            confirmText: "Remove",
            tone: "danger"
        });

        if (!confirmed) return;

        try {
            const updated = await api(
                `comments/${btn.dataset.id}`,
                {method: "DELETE"}
            );

            comments = comments.map(c =>
                c.id === updated.id ? updated : c
            );

            draw();
            notice("Comment removed", true);
        } catch (error) {
            notice(error.message);
        }
    });

    draw();
}

// US-24: what a member reported, why, and what an administrator can do about it. Every value
// from a report goes through safe(); the actions ask first and only ever touch the one target.
const REPORT_TYPES = {
    USER: "User",
    COMMENT: "Routine comment",
    ROUTINE: "Routine",
    POST: "Post",
    POST_COMMENT: "Post comment"
};

const REPORT_REASONS = {
    SPAM: "Spam",
    HARASSMENT: "Harassment",
    INAPPROPRIATE_CONTENT: "Inappropriate content",
    MISINFORMATION: "Misinformation",
    OTHER: "Something else"
};

function reportExcerpt(value) {
    const text = String(value ?? "").trim();
    return text.length > 140 ? text.slice(0, 140) + "…" : text;
}

function reportTarget(r) {
    const gone = `<span class="tag bad">Deleted</span>`;
    const hidden = r.targetHidden ? ` <span class="tag bad">${r.type === "COMMENT" ? "Removed" : "Hidden"}</span>` : "";
    const user = `<a href="admin-profile.html?id=${Number(r.reportedUserId)}" class="admin-user-link">@${safe(r.reportedUsername)}</a>`;
    const by = `<div class="muted">by ${user}</div>`;

    switch (r.type) {
        case "USER":
            return `${user}${r.reportedUserBanned ? ` <span class="tag bad">Banned</span>` : ""}${hidden}`;
        case "COMMENT":
            return r.targetAvailable
                ? `<a href="admin-routine-view.html?id=${Number(r.contextRoutineId)}&commentId=${Number(r.reportedCommentId)}&userId=${Number(r.reportedUserId)}">Comment #${Number(r.reportedCommentId)}</a>${hidden}
                   <div class="muted">${safe(reportExcerpt(r.commentText))}</div>${by}`
                : `${gone}${by}`;
        case "ROUTINE":
            return r.targetAvailable
                ? `<a href="admin-routine-view.html?id=${Number(r.reportedRoutineId)}&userId=${Number(r.reportedUserId)}">${safe(r.routineTitle)}</a>${hidden}${by}`
                : `${gone}${by}`;
        case "POST":
            return r.targetAvailable
                ? `Post #${Number(r.reportedPostId)}${hidden}<div class="muted">${safe(reportExcerpt(r.postText) || "(photo only)")}</div>${by}`
                : `${gone}${by}`;
        case "POST_COMMENT":
            return r.targetAvailable
                ? `Comment #${Number(r.reportedPostCommentId)} on post #${Number(r.contextPostId)}
                   <div class="muted">${safe(reportExcerpt(r.postCommentText))}</div>${by}`
                : `${gone}${by}`;
        default:
            return user;
    }
}

// The moderation actions each type allows, besides the status changes.
function reportContentActions(r) {
    const actions = [];

    if (!r.reportedUserBanned) {
        actions.push(button("Ban user", "ban", r.id, "danger"));
    }

    if (!r.targetAvailable) {
        return actions;
    }

    if (r.type === "COMMENT" && !r.targetHidden) {
        actions.push(button("Remove comment", "remove", r.id, "danger"));
    }

    if (r.type === "ROUTINE" && !r.targetHidden) {
        actions.push(button("Hide routine", "hide-routine", r.id, "danger"));
    }

    if (r.type === "POST") {
        if (!r.targetHidden) {
            actions.push(button("Hide post", "hide-post", r.id, "danger"));
        }
        actions.push(button("Remove post", "remove-post", r.id, "danger"));
    }

    if (r.type === "POST_COMMENT") {
        actions.push(button("Remove comment", "remove-post-comment", r.id, "danger"));
    }

    return actions;
}

function reportDate(value) {
    const date = new Date(value);
    return Number.isNaN(date.getTime()) ? "" : date.toLocaleString();
}

const REPORT_ACTIONS = {
    review: {
        title: "Review report?",
        message: "Move this report to In Review?",
        text: "Review",
        tone: "primary",
        status: "IN_REVIEW"
    },
    reject: {
        title: "Reject report?",
        message: "Are you sure you want to reject this report?",
        text: "Reject",
        tone: "danger",
        status: "REJECTED"
    },
    resolve: {
        title: "Resolve report?",
        message: "Mark this report as resolved?",
        text: "Resolve",
        tone: "success",
        status: "RESOLVED"
    },
    ban: {
        title: "Ban user?",
        message: "Are you sure you want to ban the reported user?",
        text: "Ban",
        tone: "danger",
        path: "ban-user"
    },
    remove: {
        title: "Remove comment?",
        message: "Are you sure you want to remove the reported comment?",
        text: "Remove",
        tone: "danger",
        path: "remove-comment"
    },
    "hide-routine": {
        title: "Hide routine?",
        message: "The routine leaves every public page. Its owner keeps it, and you can show it again from Routines.",
        text: "Hide",
        tone: "danger",
        path: "hide-routine"
    },
    "hide-post": {
        title: "Hide post?",
        message: "The post leaves every public page. Its author keeps it, and you can show it again from Posts.",
        text: "Hide",
        tone: "danger",
        path: "hide-post"
    },
    "remove-post": {
        title: "Remove post?",
        message: "The post, its photo, likes and comments are deleted for good. The report stays.",
        text: "Remove",
        tone: "danger",
        path: "remove-post"
    },
    "remove-post-comment": {
        title: "Remove comment?",
        message: "The comment is deleted for good. The report stays.",
        text: "Remove",
        tone: "danger",
        path: "remove-post-comment"
    }
};

async function reportsPage() {
    let reports = await api("reports");

    $("pageContent").innerHTML = `
        <div class="card">
            <div class="tools">
                <input class="field search"
                       id="searchInput"
                       type="search"
                       aria-label="Search reports"
                       placeholder="Search report reason or username...">
                <select class="field" id="reportTypeFilter" aria-label="Report type">
                    <option value="">All types</option>
                    ${Object.entries(REPORT_TYPES).map(([value, label]) =>
                        `<option value="${value}">${safe(label)}</option>`).join("")}
                </select>
                <select class="field" id="reportStatusFilter" aria-label="Report status">
                    <option value="">All statuses</option>
                    <option value="OPEN">Open</option>
                    <option value="IN_REVIEW">In review</option>
                    <option value="RESOLVED">Resolved</option>
                    <option value="REJECTED">Rejected</option>
                </select>
            </div>
            <div id="reportsTable"></div>
        </div>
    `;

    function draw() {
        const query = $("searchInput").value;
        const type = $("reportTypeFilter").value;
        const state = $("reportStatusFilter").value;

        const rows = reports
            .filter(r => !type || r.type === type)
            .filter(r => !state || r.status === state)
            .filter(r => matches([
                r.id, r.reason, REPORT_REASONS[r.reason], r.details, r.type, REPORT_TYPES[r.type],
                r.reporterUsername, r.reportedUsername,
                r.status
            ], query))
            .map(r => `
                <tr data-report="${Number(r.id)}" data-type="${safe(r.type)}">
                    <td>${Number(r.id)}</td>
                    <td>${safe(REPORT_TYPES[r.type] || r.type)}</td>
                    <td class="report-target">${reportTarget(r)}</td>
                    <td>
                        <a href="admin-profile.html?id=${Number(r.reporterId)}" class="admin-user-link">@${safe(r.reporterUsername)}</a>
                    </td>
                    <td>
                        <strong>${safe(REPORT_REASONS[r.reason] || r.reason)}</strong>
                        <div class="muted report-details">${safe(r.details)}</div>
                    </td>
                    <td>${safe(reportDate(r.createdAt))}</td>
                    <td>
                        ${status(r.status)}
                        ${r.actionTaken && r.actionTaken !== "NONE"
                            ? `<div class="muted">${safe(r.actionTaken)}</div>` : ""}
                    </td>
                    <td>
                        <div class="actions">
                            ${r.status === "RESOLVED" ||
                r.status === "REJECTED"
                ? "Closed"
                : [
                    ...(r.status === "OPEN" ? [button("Review", "review", r.id, "light")] : []),
                    button("Reject", "reject", r.id, "light"),
                    button("Resolve", "resolve", r.id),
                    ...reportContentActions(r)
                ].join("")
            }
                        </div>
                    </td>
                </tr>
            `);

        $("reportsTable").innerHTML = table(
            ["ID", "Type", "Reported", "Reporter", "Reason", "Created", "Status", "Actions"],
            rows
        );
    }

    attachSearch(draw);
    $("reportTypeFilter").addEventListener("change", draw);
    $("reportStatusFilter").addEventListener("change", draw);

    $("reportsTable").addEventListener("click", async event => {
        const btn = event.target.closest("[data-action]");
        if (!btn || btn.disabled) return;

        const config = REPORT_ACTIONS[btn.dataset.action];

        if (!config) return;

        const confirmed = await adminConfirm({
            title: config.title,
            message: config.message,
            confirmText: config.text,
            tone: config.tone
        });

        if (!confirmed) return;

        // One decision at a time; the server refuses a second one on a closed report anyway.
        btn.closest(".actions").querySelectorAll("button").forEach(item => {
            item.disabled = true;
        });

        const id = Number(btn.dataset.id);

        try {
            if (config.status) {
                await api(`reports/${id}/status`, {
                    method: "PUT",
                    ...jsonBody({
                        status: config.status,
                        note: `Admin action: ${btn.dataset.action}`
                    })
                });
            } else {
                await api(`reports/${id}/${config.path}`, {method: "POST"});
            }

            notice("Report updated", true);
        } catch (error) {
            notice(error.message);
        }

        reports = await api("reports").catch(() => reports);
        draw();
    });

    draw();
}

// US-25: every post, with its author; edit the caption, hide, show again or remove.
async function postsPage() {
    let posts = await api("posts");

    $("pageContent").innerHTML = `
        <div class="card">
            ${searchInput("Search text, author or email...")}
            <div id="postsTable"></div>
        </div>
    `;

    const label = post => post.hidden ? "Hidden" : post.publicPost ? "Public" : "Private";

    function draw() {
        const query = $("searchInput").value;

        const rows = posts
            .filter(post => matches([post.id, post.text, post.authorName, post.authorUsername, post.authorEmail], query))
            .map(post => `
                <tr data-post="${post.id}">
                    <td>${post.id}</td>
                    <td class="post-text">
                        ${safe(post.text || "")}
                        ${post.hasPhoto ? `<div class="muted">With a photo</div>` : ""}
                    </td>
                    <td>
                        ${safe(post.authorName)}
                        <div class="muted">${safe(post.authorEmail)}</div>
                    </td>
                    <td>${post.likeCount} likes / ${post.commentCount} comments</td>
                    <td class="post-status">${status(label(post))}</td>
                    <td>
                        <div class="actions">
                            ${button("Edit", "edit", post.id)}
                            ${post.hidden
                ? button("Unhide", "unhide", post.id, "green")
                : button("Hide", "hide", post.id)}
                            ${button("Remove", "delete", post.id, "danger")}
                        </div>
                    </td>
                </tr>
            `);

        $("postsTable").innerHTML = table(["ID", "Post", "Author", "Reactions", "Status", "Action"], rows);
    }

    attachSearch(draw);

    $("postsTable").addEventListener("click", async event => {
        const btn = event.target.closest("[data-action]");
        if (!btn) return;

        const post = posts.find(item => String(item.id) === btn.dataset.id);
        if (!post) return;

        const action = btn.dataset.action;

        if (action === "edit") {
            const values = await adminForm({
                heading: "Edit post",
                message: "The new caption shows wherever the post is shown.",
                fields: [{name: "text", label: "Caption", type: "textarea", value: post.text, maxlength: 2000}]
            });

            if (!values) return;

            return change(() => api(`posts/${post.id}`, {method: "PUT", ...jsonBody(values)}), "Post updated");
        }

        const confirmed = await adminConfirm({
            title: action === "delete" ? "Remove post?" : action === "hide" ? "Hide post?" : "Show post again?",
            message: action === "delete"
                ? "The post, its likes and its comments will be removed for good."
                : action === "hide"
                    ? "The post will no longer be shown to anyone but its author."
                    : "The post will be shown again as its author set it.",
            confirmText: action === "delete" ? "Remove" : action === "hide" ? "Hide" : "Show",
            tone: action === "unhide" ? "success" : "danger"
        });

        if (!confirmed) return;

        btn.disabled = true;

        if (action === "delete") {
            try {
                await api(`posts/${post.id}`, {method: "DELETE"});
                posts = posts.filter(item => item.id !== post.id);
                draw();
                notice("Post removed", true);
            } catch (error) {
                btn.disabled = false;
                notice(error.message);
            }
            return;
        }

        await change(() => api(`posts/${post.id}/${action}`, {method: "PUT"}),
            action === "hide" ? "Post hidden from the public pages" : "Post shown again");
    });

    async function change(work, done) {
        try {
            const updated = await work();
            posts = posts.map(item => item.id === updated.id ? updated : item);
            draw();
            notice(done, true);
        } catch (error) {
            draw();
            notice(error.message);
        }
    }

    draw();
}

// US-25: the categories Explore and the routine forms offer.
async function categoriesPage() {
    let categories = await api("categories");

    $("pageContent").innerHTML = `
        <div class="card">
            <h2>Add a category</h2>
            <form id="categoryForm" class="form-row" novalidate>
                <div class="form-group">
                    <label for="categoryName">Name</label>
                    <input id="categoryName" class="field" maxlength="40" required>
                </div>
                <button type="submit" class="action">Add category</button>
            </form>
        </div>
        <div class="card">
            <p class="muted">Renaming a category moves its routines with it. A category with routines in it can't be removed.</p>
            <div id="categoriesTable"></div>
        </div>
    `;

    function draw() {
        const rows = categories.map(category => `
            <tr data-category="${category.id}">
                <td>${category.position + 1}</td>
                <td class="category-name">${safe(category.name)}</td>
                <td>${category.routineCount}</td>
                <td>
                    <div class="actions">
                        ${button("Rename", "rename", category.id)}
                        ${button("Remove", "delete", category.id, "danger")}
                    </div>
                </td>
            </tr>
        `);

        $("categoriesTable").innerHTML = table(["#", "Category", "Routines", "Action"], rows);
    }

    async function reload(done) {
        categories = await api("categories");
        draw();
        notice(done, true);
    }

    $("categoryForm").addEventListener("submit", async event => {
        event.preventDefault();

        if (!checkRequiredFields(event.target)) return;

        const submit = event.target.querySelector("[type=submit]");
        submit.disabled = true;

        try {
            await api("categories", {method: "POST", ...jsonBody({name: $("categoryName").value})});
            $("categoryName").value = "";
            await reload("Category added");
        } catch (error) {
            notice(error.message);
        } finally {
            submit.disabled = false;
        }
    });

    $("categoriesTable").addEventListener("click", async event => {
        const btn = event.target.closest("[data-action]");
        if (!btn) return;

        const category = categories.find(item => String(item.id) === btn.dataset.id);
        if (!category) return;

        if (btn.dataset.action === "rename") {
            const values = await adminForm({
                heading: "Rename category",
                message: `Routines filed under "${category.name}" move with it.`,
                fields: [{name: "name", label: "Name", value: category.name, required: true, maxlength: 40}]
            });

            if (!values) return;

            try {
                await api(`categories/${category.id}`, {method: "PUT", ...jsonBody(values)});
                await reload("Category renamed");
            } catch (error) {
                notice(error.message);
            }
            return;
        }

        const confirmed = await adminConfirm({
            title: "Remove category?",
            message: `"${category.name}" will no longer be offered on Explore or in the routine forms.`,
            confirmText: "Remove",
            tone: "danger"
        });

        if (!confirmed) return;

        try {
            await api(`categories/${category.id}`, {method: "DELETE"});
            await reload("Category removed");
        } catch (error) {
            notice(error.message);
        }
    });

    draw();
}

async function boot() {
    if (!adminToken) {
        location.replace("admin-login.html");
        return;
    }

    renderShell();

    try {
        const admin = await api("users/me");

        if (admin.role !== "ADMIN" || admin.banned) {
            throw new Error("Administrator access required");
        }

        $("adminEmail").textContent = admin.email;

        const pages = {
            dashboard: dashboardPage,
            users: usersPage,
            routines: routinesPage,
            categories: categoriesPage,
            habits: habitsPage,
            posts: postsPage,
            comments: commentsPage,
            reports: reportsPage
        };

        await pages[currentPage[0]]();

    } catch (error) {
        $("pageContent").innerHTML = "";
        notice(error.message);
    }
}

boot();
