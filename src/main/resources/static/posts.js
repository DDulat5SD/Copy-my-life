// Posts: the one implementation behind posts.html and the profile's Posts section.
// mount() draws into its root once; mounting the same root again only reloads the posts.
(function () {

  // prefix keeps element ids unique when the profile shows this next to the to-do section.
  function template(prefix, heading) {
    const id = (name) => `id="${prefix}${name}" data-part="${name}"`;

    return `
      ${heading ? `
      <div class="page-head">
        <h1 ${id("pageTitle")}>My posts</h1>
        <p ${id("pageSubtitle")}>Share a moment of your day. Each post is private until you make it public.</p>
      </div>` : ""}

      <p class="page-message" ${id("pageMessage")} role="alert" hidden></p>

      <div class="post-tabs" ${id("postTabs")} role="tablist" aria-label="Posts" hidden>
        <button type="button" class="mc-btn mc-btn--ghost post-tab" data-view="mine" role="tab" aria-selected="true">My posts</button>
        <button type="button" class="mc-btn mc-btn--ghost post-tab" data-view="saved" role="tab" aria-selected="false">Saved posts</button>
      </div>

      <form class="mc-card mc-form" ${id("newPostForm")} novalidate hidden>
        <textarea class="mc-textarea" ${id("newPostText")} maxlength="2000"
                  placeholder="What did you do today?" aria-label="Post text"></textarea>
        <input type="file" ${id("newPostImage")} accept="image/jpeg,image/png,image/webp,image/gif" aria-label="Photo">
        <label class="mc-check"><input type="checkbox" ${id("newPostPublic")}> Public</label>
        <button class="mc-btn" type="submit">Post</button>
        <p class="mc-hint">Photos: JPEG, PNG, WebP or GIF up to 5 MB. Video is not supported.</p>
      </form>

      <p class="mc-status" ${id("postsLoading")} role="status">Loading your posts…</p>
      <p class="mc-empty" ${id("postsEmpty")} hidden></p>

      <div ${id("posts")}></div>`;
  }

  function mount(root, options) {
    if (root.postsApp) {
      root.postsApp.reload();
      return root.postsApp;
    }

    const token = options.token;
    // A viewed user shows that user's public posts, read-only.
    const viewedUserId = options.viewedUserId == null ? null : Number(options.viewedUserId);
    // itemId shows that one post only, as the server allows it (its owner, or anyone when public),
    // with its comments open.
    const itemId = options.itemId == null ? null : Number(options.itemId);
    const owner = viewedUserId === null && itemId === null;

    root.innerHTML = template(options.idPrefix || "", options.heading !== false);

    const $ = (name) => root.querySelector(`[data-part="${name}"]`);
    const esc = SafeDom.escapeHtml;

    let posts = [];
    // US-16: the owner's own posts ("mine") or the posts they saved ("saved").
    let view = "mine";
    let pendingDeleteId = null;
    let editingId = null;

    // US-15: likes and comments. Kept per post id across re-renders.
    const likeBusy = new Set();
    const saveBusy = new Set();
    const openComments = new Set();
    const comments = new Map();        // id -> {state: "loading" | "ready" | "error", items, error}
    const drafts = new Map();          // id -> comment being typed
    const commentErrors = new Map();   // id -> why the last comment was refused
    const sending = new Set();
    let pendingCommentDelete = null;

    // body is FormData for writes (the browser sets its multipart boundary), or a JSON string.
    async function request(url, method = "GET", body) {
      const headers = {"Authorization": "Bearer " + token};

      if (typeof body === "string") {
        headers["Content-Type"] = "application/json";
      }

      const response = await fetch(url, {method, headers, body});

      // Without an accepted token the server answers 401 or 403: the visitor signs in again.
      if (response.status === 401 || response.status === 403) {
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

    function form(fields) {
      const data = new FormData();
      Object.entries(fields).forEach(([key, value]) => {
        if (value !== undefined && value !== null) data.append(key, value);
      });
      return data;
    }

    function showMessage(text) {
      $("pageMessage").textContent = text || "";
      $("pageMessage").hidden = !text;
    }

    function replace(updated) {
      posts = posts.map((post) => (post.id === updated.id ? updated : post));
    }

    async function load() {
      $("postsLoading").hidden = false;
      try {
        posts = itemId !== null
            ? [await request("/posts/" + itemId)]
            : await request(!owner ? "/users/" + viewedUserId + "/posts"
                : view === "saved" ? "/saved-posts" : "/posts");
        showMessage("");
      } catch (error) {
        posts = [];
        showMessage(owner ? error.message
            : itemId !== null ? "This post is not available." : "This user's posts are not available.");
      }
      $("postsLoading").hidden = true;
      render();
    }

    function when(value) {
      const date = new Date(value);
      return isNaN(date) ? "" : date.toLocaleDateString(undefined, {year: "numeric", month: "short", day: "numeric"});
    }

    function badge(post) {
      // US-25: only the author still sees a post an administrator hid.
      const hidden = post.hiddenByAdmin
          ? `<span class="visibility-badge post-hidden-badge">Hidden by an administrator</span>`
          : "";
      return (post.isPublic
          ? `<span class="visibility-badge visibility-badge--public">Public</span>`
          : `<span class="visibility-badge visibility-badge--private">Private</span>`) + hidden;
    }

    // The picture is filled in by loadPhotos(); photo files are never public.
    function photo(post) {
      return post.imageUrl
          ? `<div class="post__image"><img data-photo="${Number(post.id)}" alt="Photo shared by ${esc(post.authorName)}"></div>`
          : "";
    }

    // Each photo is asked of its post with the token, so only those who may see the post get it.
    // Kept per post version, so a re-render does not fetch it again.
    const photoUrls = new Map();

    function photoUrl(post) {
      const key = post.id + ":" + post.updatedAt;

      if (!photoUrls.has(key)) {
        photoUrls.set(key, fetch("/posts/" + Number(post.id) + "/image", {
          headers: {"Authorization": "Bearer " + token}
        })
            .then((response) => (response.ok ? response.blob() : null))
            .then((blob) => (blob && blob.type.startsWith("image/") ? URL.createObjectURL(blob) : ""))
            .catch(() => ""));
      }

      return photoUrls.get(key);
    }

    async function loadPhotos() {
      for (const img of $("posts").querySelectorAll("img[data-photo]")) {
        const post = posts.find((item) => item.id === Number(img.dataset.photo));

        if (!post || !post.imageUrl) continue;

        const url = await photoUrl(post);

        if (url && img.isConnected) {
          img.src = url;
        }
      }
    }

    function body(post) {
      if (owner && editingId === Number(post.id)) {
        return `
          <form class="mc-form edit-form" novalidate>
            <textarea class="mc-textarea" name="text" maxlength="2000" aria-label="Post text">${esc(post.text || "")}</textarea>
            <input type="file" name="image" accept="image/jpeg,image/png,image/webp,image/gif" aria-label="Replace photo">
            ${post.imageUrl ? `<label class="mc-check"><input type="checkbox" name="removeImage"> Remove photo</label>` : ""}
            <button class="mc-btn" type="button" data-action="save">Save</button>
            <button class="mc-btn mc-btn--ghost" type="button" data-action="cancel-edit">Cancel</button>
          </form>`;
      }

      return `${post.text ? `<p class="post__text">${esc(post.text)}</p>` : ""}${photo(post)}`;
    }

    function ownerControls(post) {
      if (pendingDeleteId === Number(post.id)) {
        return `
          <div class="confirm" role="group" aria-label="Confirm delete">
            <span>Delete this post${post.imageUrl ? " and its photo" : ""}? This cannot be undone.</span>
            <button type="button" class="mc-btn mc-btn--danger-solid" data-action="confirm-delete">Delete</button>
            <button type="button" class="mc-btn mc-btn--ghost" data-action="cancel-delete">Cancel</button>
          </div>`;
      }

      if (editingId === Number(post.id)) {
        return "";
      }

      return `
        <div class="mc-actions">
          <button type="button" class="mc-btn mc-btn--ghost" data-action="toggle-visibility">
            ${post.isPublic ? "Make private" : "Make public"}
          </button>
          <button type="button" class="mc-btn mc-btn--ghost" data-action="edit">Edit</button>
          <button type="button" class="mc-btn mc-btn--danger" data-action="delete">Delete</button>
        </div>`;
    }

    function render() {
      const mine = owner && view === "mine";

      $("newPostForm").hidden = !mine;
      $("postTabs").hidden = !owner;
      $("postTabs").querySelectorAll("[data-view]").forEach((tab) => {
        tab.setAttribute("aria-selected", String(tab.dataset.view === view));
      });

      if (!owner && $("pageTitle")) {
        const author = posts[0] && posts[0].authorUsername ? "@" + posts[0].authorUsername : "this user";
        $("pageTitle").textContent = "Posts";
        $("pageSubtitle").textContent = "Public posts by " + author + ".";
      }

      $("postsEmpty").textContent = mine
          ? "You have not posted anything yet. Share your first post above."
          : owner
              ? "You have not saved any posts yet. Use Save on a public post to keep it here."
              : "No public posts yet.";
      $("postsEmpty").hidden = posts.length > 0;

      $("posts").innerHTML = posts.map((post) => `
        <article class="mc-card post" data-id="${Number(post.id)}">
          <div class="mc-head">
            <span class="post__meta">${esc(post.authorName)}${post.authorUsername ? " · @" + esc(post.authorUsername) : ""} · ${esc(when(post.createdAt))}</span>
            ${badge(post)}
          </div>
          ${body(post)}
          ${mine ? ownerControls(post) : ""}
          ${social(post)}
        </article>`).join("");

      loadPhotos();
    }

    // US-24: someone else's post or comment can be reported, on pages that load report-dialog.js.
    function reportable(item) {
      return item.ownedByMe !== true && Boolean(window.CopyMyLifeReport);
    }

    // ---- likes and comments (US-15) ----

    function social(post) {
      const id = Number(post.id);
      const liked = post.likedByMe === true;
      const likes = Number(post.likeCount) || 0;
      const count = Number(post.commentCount) || 0;
      const open = openComments.has(id);

      return `
        <div class="post-social">
          <button type="button" class="mc-btn mc-btn--ghost post-like${liked ? " post-like--on" : ""}" data-social="like"
                  aria-pressed="${liked}" ${likeBusy.has(id) ? "disabled" : ""}>
            <span aria-hidden="true">${liked ? "♥" : "♡"}</span>
            <span class="post-like__label">${liked ? "Liked" : "Like"}</span>
            <span class="post-like__count">${likes}</span>
          </button>
          <button type="button" class="mc-btn mc-btn--ghost post-comments-toggle" data-social="toggle-comments"
                  aria-expanded="${open}">
            ${open ? "Hide comments" : "Comments"} (<span class="post-comments__count">${count}</span>)
          </button>
          ${savable(post) ? saveButton(post) : ""}
          ${reportable(post) ? `<button type="button" class="mc-btn mc-btn--ghost post-report" data-social="report-post">Report</button>` : ""}
        </div>
        ${open ? commentSection(id) : ""}`;
    }

    function commentSection(id) {
      const thread = comments.get(id) || {state: "loading", items: []};
      let content;

      if (thread.state === "loading") {
        content = `<p class="post-comments__status">Loading comments…</p>`;
      } else if (thread.state === "error") {
        content = `
          <p class="post-comments__status post-comments__status--error" role="alert">${esc(thread.error)}</p>
          <button type="button" class="mc-btn mc-btn--ghost" data-social="retry-comments">Try again</button>`;
      } else if (thread.items.length === 0) {
        content = `<p class="post-comments__status post-comments__empty">No comments yet. Be the first to comment.</p>`;
      } else {
        content = `<ul class="post-comments__list">${thread.items.map(comment).join("")}</ul>`;
      }

      const error = commentErrors.get(id);

      return `
        <section class="post-comments" aria-label="Comments">
          ${content}
          <form class="mc-form post-comment-form" novalidate>
            <input class="mc-input" name="comment" maxlength="1000" autocomplete="off"
                   placeholder="Write a comment…" aria-label="Write a comment" value="${esc(drafts.get(id) || "")}">
            <button class="mc-btn" type="submit" ${sending.has(id) ? "disabled" : ""}>
              ${sending.has(id) ? "Sending…" : "Comment"}
            </button>
          </form>
          <p class="post-comments__status post-comments__status--error post-comment-error" role="alert" ${error ? "" : "hidden"}>${esc(error || "")}</p>
        </section>`;
    }

    function comment(item) {
      const id = Number(item.id);
      let controls = "";

      if (item.ownedByMe && pendingCommentDelete === id) {
        controls = `
          <div class="confirm" role="group" aria-label="Confirm delete">
            <span>Delete your comment?</span>
            <button type="button" class="mc-btn mc-btn--danger-solid" data-social="confirm-delete-comment" data-comment="${id}">Delete</button>
            <button type="button" class="mc-btn mc-btn--ghost" data-social="cancel-delete-comment">Cancel</button>
          </div>`;
      } else if (item.ownedByMe) {
        controls = `<button type="button" class="mc-btn mc-btn--danger post-comment__delete" data-social="delete-comment" data-comment="${id}">Delete</button>`;
      } else if (reportable(item)) {
        controls = `<button type="button" class="mc-btn mc-btn--ghost post-comment__report" data-social="report-comment" data-comment="${id}">Report</button>`;
      }

      return `
        <li class="post-comment" data-comment="${id}">
          <div class="post-comment__head">
            <span class="post-comment__author">${esc(item.authorName || "User")}</span>
            <span class="post-comment__meta">${item.authorUsername ? "@" + esc(item.authorUsername) + " · " : ""}${esc(when(item.createdAt))}</span>
          </div>
          <p class="post-comment__text">${esc(item.text)}</p>
          ${controls}
        </li>`;
    }

    // ---- bookmarks (US-16) ----

    function savable(post) {
      return post.ownedByMe !== true && post.isPublic === true;
    }

    function saveButton(post) {
      const id = Number(post.id);
      const saved = post.savedByMe === true;
      return `
          <button type="button" class="mc-btn mc-btn--ghost post-save${saved ? " post-save--on" : ""}" data-social="save"
                  aria-pressed="${saved}" ${saveBusy.has(id) ? "disabled" : ""}>${saved ? "Saved" : "Save"}</button>`;
    }

    async function toggleSave(id) {
      const post = posts.find((item) => item.id === id);

      if (!post || saveBusy.has(id)) return;

      saveBusy.add(id);
      render();

      try {
        if (post.savedByMe) {
          await request("/saved-posts/" + id, "DELETE");
          setPost(id, {savedByMe: false});
          if (owner && view === "saved") {
            posts = posts.filter((item) => item.id !== id);
          }
        } else {
          replace(await request("/saved-posts/" + id, "POST"));
        }
        showMessage("");
      } catch (error) {
        showMessage(error.message);
      } finally {
        saveBusy.delete(id);
        render();
      }
    }

    function setPost(id, changes) {
      posts = posts.map((post) => (post.id === id ? {...post, ...changes} : post));
    }

    async function toggleLike(id) {
      const post = posts.find((item) => item.id === id);

      if (!post || likeBusy.has(id)) return;

      likeBusy.add(id);
      render();

      try {
        // The count shown is the one the server stored, never a guess.
        const result = await request("/posts/" + id + "/like", post.likedByMe ? "DELETE" : "POST");
        setPost(id, {likeCount: result.likeCount, likedByMe: result.likedByMe});
        showMessage("");
      } catch (error) {
        showMessage(error.message);
      } finally {
        likeBusy.delete(id);
        render();
      }
    }

    async function loadComments(id) {
      comments.set(id, {state: "loading", items: []});
      render();

      try {
        const items = await request("/posts/" + id + "/comments");
        comments.set(id, {state: "ready", items});
        setPost(id, {commentCount: items.length});
      } catch (error) {
        comments.set(id, {state: "error", items: [], error: "Comments could not be loaded. " + error.message});
      }
      render();
    }

    async function sendComment(id) {
      const text = (drafts.get(id) || "").trim();

      if (!text) {
        commentErrors.set(id, "Write a comment first.");
        render();
        return;
      }

      sending.add(id);
      commentErrors.delete(id);
      render();

      try {
        const created = await request("/posts/" + id + "/comments", "POST", JSON.stringify({text}));
        const thread = comments.get(id);
        if (thread && thread.state === "ready") {
          thread.items = [...thread.items, created];
        }
        drafts.delete(id);
        const post = posts.find((item) => item.id === id);
        setPost(id, {commentCount: (Number(post && post.commentCount) || 0) + 1});
      } catch (error) {
        commentErrors.set(id, error.message);
      } finally {
        sending.delete(id);
        render();
      }
    }

    async function deleteComment(id, commentId) {
      try {
        await request("/posts/" + id + "/comments/" + commentId, "DELETE");
        const thread = comments.get(id);
        if (thread) {
          thread.items = thread.items.filter((item) => Number(item.id) !== commentId);
        }
        const post = posts.find((item) => item.id === id);
        setPost(id, {commentCount: Math.max(0, (Number(post && post.commentCount) || 0) - 1)});
        commentErrors.delete(id);
      } catch (error) {
        commentErrors.set(id, error.message);
      }
      pendingCommentDelete = null;
      render();
    }

    function onSocial(button, id) {
      switch (button.dataset.social) {
        case "like":
          toggleLike(id);
          break;
        case "save":
          toggleSave(id);
          break;
        case "toggle-comments":
          if (openComments.has(id)) {
            openComments.delete(id);
            render();
          } else {
            openComments.add(id);
            loadComments(id);
          }
          break;
        case "retry-comments":
          loadComments(id);
          break;
        case "delete-comment":
          pendingCommentDelete = Number(button.dataset.comment);
          render();
          break;
        case "cancel-delete-comment":
          pendingCommentDelete = null;
          render();
          break;
        case "confirm-delete-comment":
          deleteComment(id, Number(button.dataset.comment));
          break;
        case "report-post":
          window.CopyMyLifeReport.open({type: "POST", id, token});
          break;
        case "report-comment":
          window.CopyMyLifeReport.open({type: "POST_COMMENT", id: Number(button.dataset.comment), token});
          break;
      }
    }

    $("posts").addEventListener("input", (event) => {
      const field = event.target.closest(".post-comment-form [name=comment]");
      if (field) {
        drafts.set(Number(field.closest(".post").dataset.id), field.value);
      }
    });

    $("posts").addEventListener("submit", (event) => {
      const formElement = event.target.closest(".post-comment-form");
      if (!formElement) return;
      event.preventDefault();
      const id = Number(formElement.closest(".post").dataset.id);
      drafts.set(id, formElement.querySelector("[name=comment]").value);
      sendComment(id);
    });

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
        if (input.classList.contains("mc-textarea")) {
          input.insertAdjacentElement("afterend", error);
        } else {
          formElement.appendChild(error);
        }
      }

      error.textContent = text;
      error.hidden = !text;
      input.setAttribute("aria-invalid", String(Boolean(text)));

      if (text) {
        input.focus();
      }

      return !text;
    }

    $("newPostForm").addEventListener("submit", (event) => {
      event.preventDefault();
      const file = $("newPostImage").files[0];
      if (!fieldError(event.target, $("newPostText").value.trim() || file ? "" : "Write something or add a photo.")) return;
      run(async () => {
        const created = await request("/posts", "POST", form({
          text: $("newPostText").value,
          isPublic: $("newPostPublic").checked,
          image: file
        }));
        posts = [created, ...posts];
        $("newPostForm").reset();
      });
    });

    $("posts").addEventListener("click", (event) => {
      const socialButton = event.target.closest("button[data-social]");
      if (socialButton) {
        onSocial(socialButton, Number(socialButton.closest(".post").dataset.id));
        return;
      }

      const button = event.target.closest("button[data-action]");
      if (!button) return;
      const card = button.closest(".post");
      const id = Number(card.dataset.id);
      const post = posts.find((item) => item.id === id);

      switch (button.dataset.action) {
        case "toggle-visibility":
          run(async () => replace(await request("/posts/" + id, "PUT", form({isPublic: !post.isPublic}))));
          break;
        case "edit":
          editingId = id;
          render();
          break;
        case "cancel-edit":
          editingId = null;
          render();
          break;
        case "save": {
          const editor = card.querySelector(".edit-form");
          const removeImage = editor.querySelector('[name="removeImage"]');
          run(async () => {
            replace(await request("/posts/" + id, "PUT", form({
              text: editor.querySelector('[name="text"]').value,
              image: editor.querySelector('[name="image"]').files[0],
              removeImage: removeImage ? removeImage.checked : false
            })));
            editingId = null;
          });
          break;
        }
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
            await request("/posts/" + id, "DELETE");
            posts = posts.filter((item) => item.id !== id);
            pendingDeleteId = null;
          });
          break;
      }
    });

    $("postTabs").addEventListener("click", (event) => {
      const tab = event.target.closest("[data-view]");
      if (!tab || tab.dataset.view === view) return;
      view = tab.dataset.view;
      pendingDeleteId = null;
      editingId = null;
      load();
    });

    root.postsApp = {reload: load};
    load().then(() => {
      if (itemId !== null && posts.length === 1 && !openComments.has(itemId)) {
        openComments.add(itemId);
        loadComments(itemId);
      }
    });
    return root.postsApp;
  }

  window.CopyMyLifePosts = {mount};
})();
