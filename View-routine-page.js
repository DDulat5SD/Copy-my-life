const USE_API = false;
const API_BASE = "/api";
const ROUTINE_ID = new URLSearchParams(location.search).get("id") || 1;

const API = {
  routine:     (id) => `${API_BASE}/routines/${id}`,
  comments:    (id) => `${API_BASE}/routines/${id}/comments`,
  likeComment: (id) => `${API_BASE}/comments/${id}/like`,
  likeRoutine: (id) => `${API_BASE}/routines/${id}/like`,
  saveRoutine: (id) => `${API_BASE}/routines/${id}/save`,
  saveHabit:   (id) => `${API_BASE}/habits/${id}/save`,
};

const currentUser = { id: 1, name: "Daulet" };

const daysAgo = (d) => new Date(Date.now() - d * 86400000).toISOString();

const MOCK_ROUTINE = {
  id: 1,
  title: "Morning Study Routine",
  tag: "Study routine",
  category: "Study",
  description:
    "A focused morning routine to start the day with clarity, energy and deep work. These habits help me stay consistent, plan better and make real progress on my goals.",
  imageUrl: "",
  author: { id: 1, name: "Daulet" },
  createdAt: daysAgo(14),
  likes: 243,
  saves: 128,
  likedByMe: false,
  savedByMe: false,
  habits: [
    { id: 1, title: "Drink water",       description: "Rehydrate and wake up your body.",   icon: "water", color: "blue",   startTime: "07:00", duration: 5, savedByMe: false },
    { id: 2, title: "Light stretching",  description: "Loosen up and get some energy flowing.", icon: "bolt", color: "orange", startTime: "07:05", duration: 10, savedByMe: false },
    { id: 3, title: "Plan the day",      description: "Set your priorities and write a short plan.", icon: "notes", color: "purple", startTime: "07:15", duration: 10, savedByMe: false },
    { id: 4, title: "Deep work session", description: "Focus on your most important task.", icon: "cap",   color: "yellow", startTime: "07:25", duration: 25, savedByMe: false },
    { id: 5, title: "Read notes",        description: "Review your notes and reinforce learning.", icon: "book", color: "purple", startTime: "07:50", duration: 45, savedByMe: false },
  ],
};

const MOCK_COMMENTS = [
  { id: 1, parentId: null, author: { id: 2, name: "Sarah Kim" }, text: "This routine has completely changed my mornings! I feel so much more focused and productive during the day. The structure is simple but really effective. Thank you for sharing this! 🙌", createdAt: daysAgo(14), likes: 42, likedByMe: false },
  { id: 2, parentId: 1,    author: { id: 1, name: "Daulet" },    text: "So glad to hear that, Sarah! Keep it up! 💪", createdAt: daysAgo(13), likes: 12, likedByMe: false },
  { id: 3, parentId: null, author: { id: 3, name: "Alex Carter" }, text: "Quick question — do you usually do the deep work session on a specific subject, or does it change daily? I'm trying to build a similar routine and wondering how you keep it consistent.", createdAt: daysAgo(7), likes: 18, likedByMe: false },
  { id: 4, parentId: 3,    author: { id: 1, name: "Daulet" },    text: "It changes depending on my current goals, but I always choose the most important task for the day. Usually it's study or a personal project. The key is to remove distractions and just focus 🙏", createdAt: daysAgo(7), likes: 26, likedByMe: false },
  { id: 5, parentId: null, author: { id: 4, name: "Emma Wilson" }, text: "I've been following this routine for a week now and it's amazing! The morning feels so much calmer and I actually get things done. The planning step makes a huge difference for me.", createdAt: daysAgo(3), likes: 9, likedByMe: false },
];

const ICONS = {
  water: '<svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 3s6 7 6 11a6 6 0 0 1-12 0c0-4 6-11 6-11z"/></svg>',
  bolt:  '<svg width="28" height="28" viewBox="0 0 24 24" fill="currentColor"><path d="M13 2 4 14h7l-1 8 9-12h-7z"/></svg>',
  notes: '<svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="5" y="3" width="14" height="18" rx="2"/><path d="M9 8h6M9 12h6M9 16h4"/></svg>',
  cap:   '<svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="m2 9 10-5 10 5-10 5z"/><path d="M6 11v5c3 2 9 2 12 0v-5M22 9v5"/></svg>',
  book:  '<svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M2 5h7a3 3 0 0 1 3 3v12a2 2 0 0 0-2-2H2zM22 5h-7a3 3 0 0 0-3 3v12a2 2 0 0 1 2-2h8z"/></svg>',
  check: '<svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="9"/><path d="m8 12 3 3 5-6"/></svg>',
};
const CLOCK = '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/></svg>';
const ARROW = '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M5 12h14M13 6l6 6-6 6"/></svg>';
const HEART = '<svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor"><path d="M12 21s-7.5-4.6-9.5-9.3C1 8 3.4 4.5 7 4.5c2 0 3.4 1 5 2.8 1.6-1.8 3-2.8 5-2.8 3.6 0 6 3.5 4.5 7.2C19.5 16.4 12 21 12 21z"/></svg>';
const BOOKMARK = '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linejoin="round"><path d="M6 3h12v18l-6-4-6 4z"/></svg>';
const REPLY = '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M9 14 4 9l5-5"/><path d="M4 9h10a6 6 0 0 1 6 6v4"/></svg>';

let routine = null;
let comments = [];
let sortMode = "recent";
let nextLocalId = 1000;

const $ = (id) => document.getElementById(id);

function escapeHTML(str) {
  const div = document.createElement("div");
  div.textContent = str;
  return div.innerHTML;
}

function initials(name) {
  return name.split(" ").map((p) => p[0]).join("").slice(0, 2).toUpperCase();
}
function avatarColor(name) {
  const palette = [["#5b7cfa", "#2f5bea"], ["#f78ca0", "#e0457b"], ["#43c59e", "#1f9d74"], ["#f5a742", "#e07b1b"], ["#9b7bf7", "#6b4fe0"], ["#4fc3e8", "#1e94c4"]];
  let h = 0;
  for (const c of name) h = (h * 31 + c.charCodeAt(0)) >>> 0;
  const [a, b] = palette[h % palette.length];
  return `linear-gradient(135deg, ${a}, ${b})`;
}

function avatarHTML(name, size) {
  return `<span class="avatar avatar--${size}" style="background:${avatarColor(name)}">${escapeHTML(initials(name))}</span>`;
}

function timeAgo(iso) {
  const sec = Math.floor((Date.now() - new Date(iso)) / 1000);
  const units = [["year", 31536000], ["month", 2592000], ["week", 604800], ["day", 86400], ["hour", 3600], ["minute", 60]];
  for (const [name, s] of units) {
    const n = Math.floor(sec / s);
    if (n >= 1) return `${n} ${name}${n > 1 ? "s" : ""} ago`;
  }
  return "just now";
}

function toMinutes(hhmm) {
  const [h, m] = hhmm.split(":").map(Number);
  return h * 60 + m;
}

function formatTime(min) {
  const h24 = Math.floor(min / 60) % 24;
  const m = min % 60;
  const ampm = h24 < 12 ? "AM" : "PM";
  const h12 = h24 % 12 || 12;
  return `${h12}:${String(m).padStart(2, "0")} ${ampm}`;
}

function formatDuration(min) {
  const h = Math.floor(min / 60);
  const m = min % 60;
  return h ? `${h}h ${m ? m + "m" : ""}`.trim() : `${m} min`;
}

async function request(url, options = {}) {
  const res = await fetch(url, { headers: { "Content-Type": "application/json" }, ...options });
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  return res.json();
}

async function loadRoutine() {
  routine = USE_API ? await request(API.routine(ROUTINE_ID)) : structuredClone(MOCK_ROUTINE);
  renderRoutine();
}

function renderRoutine() {
  document.title = `${routine.title} — CopyMyLife`;
  $("routineTag").querySelector("span").textContent = routine.tag;
  $("routineTitle").textContent = routine.title;
  $("authorName").textContent = routine.author.name;
  $("authorAvatar").textContent = initials(routine.author.name)[0];
  $("routineCreated").textContent = `Created ${timeAgo(routine.createdAt)}`;
  $("routineDesc").textContent = routine.description;
  $("routineCategory").textContent = routine.category;

  if (routine.imageUrl) {
    const img = $("routineImage");
    img.style.backgroundImage = `url("${routine.imageUrl}")`;
    img.classList.add("has-photo");
  }

  const habits = sortedHabits();
  const total = habits.reduce((sum, h) => sum + h.duration, 0);
  $("habitsCount").textContent = `${habits.length} habit${habits.length === 1 ? "" : "s"}`;
  $("routineTotal").textContent = `Total ${formatDuration(total)}`;

  if (habits.length) {
    const start = toMinutes(habits[0].startTime);
    const last = habits[habits.length - 1];
    const end = toMinutes(last.startTime) + last.duration;
    $("routineTimeRange").textContent = `${formatTime(start)} - ${formatTime(end)}`;
  }

  renderRoutineStats();
  renderHabits(habits);
}

function sortedHabits() {
  return [...routine.habits].sort((a, b) => toMinutes(a.startTime) - toMinutes(b.startTime));
}

function renderRoutineStats() {
  $("likesCount").textContent = routine.likes;
  $("savedCount").textContent = routine.saves;
  $("likeBtn").classList.toggle("is-active", routine.likedByMe);
  $("saveBtn").classList.toggle("is-active", routine.savedByMe);
}

function renderHabits(habits) {
  $("habitsList").innerHTML = habits.map((h, i) => {
    const start = toMinutes(h.startTime);
    return `
      <li class="habit" data-id="${h.id}">
        <span class="habit__num">${i + 1}</span>
        <span class="habit__icon icon--${h.color || "blue"}">${ICONS[h.icon] || ICONS.check}</span>
        <div class="habit__info">
          <div class="habit__title">${escapeHTML(h.title)}</div>
          <div class="habit__desc">${escapeHTML(h.description)}</div>
        </div>
        <div class="habit__time">
          ${CLOCK}<span>${formatTime(start)}</span>${ARROW}<span>${formatTime(start + h.duration)}</span>
        </div>
        <span class="habit__dur">${h.duration} min</span>
        <button class="habit__save ${h.savedByMe ? "is-active" : ""}" data-id="${h.id}" title="${h.savedByMe ? "Saved" : "Save habit"}" aria-label="Save habit">${BOOKMARK}</button>
      </li>`;
  }).join("");
}

async function toggleRoutine(field) {
  const flag = field === "like" ? "likedByMe" : "savedByMe";
  const count = field === "like" ? "likes" : "saves";
  routine[flag] = !routine[flag];
  routine[count] += routine[flag] ? 1 : -1;
  renderRoutineStats();
  if (USE_API) {
    try {
      await request(field === "like" ? API.likeRoutine(routine.id) : API.saveRoutine(routine.id), { method: "POST" });
    } catch (e) {
      routine[flag] = !routine[flag];
      routine[count] += routine[flag] ? 1 : -1;
      renderRoutineStats();
    }
  }
}

async function toggleHabitSave(id) {
  const h = routine.habits.find((x) => x.id === id);
  if (!h) return;
  h.savedByMe = !h.savedByMe;
  renderHabits(sortedHabits());
  if (USE_API) {
    try { await request(API.saveHabit(id), { method: "POST" }); }
    catch (e) { h.savedByMe = !h.savedByMe; renderHabits(sortedHabits()); }
  }
}

async function loadComments() {
  comments = USE_API ? await request(API.comments(ROUTINE_ID)) : structuredClone(MOCK_COMMENTS);
  renderComments();
}

function sortTopLevel(list) {
  const copy = [...list];
  if (sortMode === "recent")  copy.sort((a, b) => new Date(b.createdAt) - new Date(a.createdAt));
  if (sortMode === "oldest")  copy.sort((a, b) => new Date(a.createdAt) - new Date(b.createdAt));
  if (sortMode === "popular") copy.sort((a, b) => b.likes - a.likes);
  return copy;
}

function commentHTML(c, isReply) {
  return `
    <div class="comment" data-id="${c.id}">
      ${avatarHTML(c.author.name, isReply ? "xs" : "lg")}
      <div class="comment__main">
        <div class="comment__head">
          <span class="comment__author">${escapeHTML(c.author.name)}</span>
          <span class="comment__date">${timeAgo(c.createdAt)}</span>
          ${isReply ? "" : '<button class="comment__more" title="More">···</button>'}
        </div>
        <p class="comment__text">${escapeHTML(c.text)}</p>
        <div class="comment__actions">
          <button class="action action--like ${c.likedByMe ? "is-active" : ""}" data-action="like" data-id="${c.id}">
            ${HEART}<span>${c.likes}</span>
          </button>
          <button class="action" data-action="reply" data-id="${isReply ? c.parentId : c.id}" data-name="${escapeHTML(c.author.name)}">
            ${REPLY}<span>Reply</span>
          </button>
        </div>
      </div>
    </div>`;
}

function renderComments() {
  const topLevel = sortTopLevel(comments.filter((c) => c.parentId === null));
  const repliesOf = (id) => comments
    .filter((c) => c.parentId === id)
    .sort((a, b) => new Date(a.createdAt) - new Date(b.createdAt));

  $("commentsCount").textContent = comments.length;
  $("commentsCountTop").textContent = comments.length;

  if (!topLevel.length) {
    $("commentList").innerHTML = '<li class="empty">No comments yet. Be the first!</li>';
    return;
  }

  $("commentList").innerHTML = topLevel.map((c) => `
    <li class="comment-thread" data-thread="${c.id}">
      ${commentHTML(c, false)}
      <ul class="replies">${repliesOf(c.id).map((r) => `<li>${commentHTML(r, true)}</li>`).join("")}</ul>
    </li>`).join("");
}

async function addComment(text, parentId = null) {
  text = text.trim();
  if (!text) return;

  let newComment;
  if (USE_API) {
    newComment = await request(API.comments(ROUTINE_ID), {
      method: "POST",
      body: JSON.stringify({ text, parentId }),
    });
  } else {
    newComment = {
      id: nextLocalId++,
      parentId,
      author: { id: currentUser.id, name: currentUser.name },
      text,
      createdAt: new Date().toISOString(),
      likes: 0,
      likedByMe: false,
    };
  }
  comments.push(newComment);
  renderComments();
}

async function toggleCommentLike(id) {
  const c = comments.find((x) => x.id === id);
  if (!c) return;
  c.likedByMe = !c.likedByMe;
  c.likes += c.likedByMe ? 1 : -1;
  renderComments();
  if (USE_API) {
    try { await request(API.likeComment(id), { method: "POST" }); }
    catch (e) { c.likedByMe = !c.likedByMe; c.likes += c.likedByMe ? 1 : -1; renderComments(); }
  }
}

function openReplyForm(parentId, name) {
  document.querySelectorAll(".reply-form").forEach((f) => f.remove());
  const thread = document.querySelector(`[data-thread="${parentId}"]`);
  const form = document.createElement("form");
  form.className = "reply-form";
  form.innerHTML = `<input type="text" maxlength="500" placeholder="Reply to ${name}..." /><button type="submit">Reply</button>`;
  thread.querySelector(".comment__main").appendChild(form);
  const input = form.querySelector("input");
  input.focus();
  form.addEventListener("submit", (e) => {
    e.preventDefault();
    addComment(input.value, parentId);
  });
}

function bindEvents() {
  const input = $("commentInput");
  input.addEventListener("input", () => { $("postBtn").disabled = !input.value.trim(); });

  $("commentForm").addEventListener("submit", async (e) => {
    e.preventDefault();
    await addComment(input.value);
    input.value = "";
    $("postBtn").disabled = true;
  });

  $("sortSelect").addEventListener("change", (e) => { sortMode = e.target.value; renderComments(); });

  $("commentList").addEventListener("click", (e) => {
    const btn = e.target.closest("[data-action]");
    if (!btn) return;
    const id = Number(btn.dataset.id);
    if (btn.dataset.action === "like")  toggleCommentLike(id);
    if (btn.dataset.action === "reply") openReplyForm(id, btn.dataset.name);
  });

  $("habitsList").addEventListener("click", (e) => {
    const btn = e.target.closest(".habit__save");
    if (btn) toggleHabitSave(Number(btn.dataset.id));
  });

  $("likeBtn").addEventListener("click", () => toggleRoutine("like"));
  $("saveBtn").addEventListener("click", () => toggleRoutine("save"));
  $("startBtn").addEventListener("click", () => alert("Routine started! 🚀"));
}

document.addEventListener("DOMContentLoaded", async () => {
  $("currentUserName").textContent = currentUser.name;
  $("currentUserAvatar").textContent = initials(currentUser.name)[0];
  $("formAvatar").textContent = initials(currentUser.name)[0];
  bindEvents();
  try {
    await Promise.all([loadRoutine(), loadComments()]);
  } catch (err) {
    console.error("Не удалось загрузить данные:", err);
  }
});
