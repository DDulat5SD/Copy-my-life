// The site header of main.html for pages that only need the header: logo, Home, Explore,
// Top Routines, then Sign in / Sign up for visitors or the user's avatar menu.
// Usage: <header class="header" data-site-header></header> followed by this script.
(function () {

  const PERSON = `
    <svg width="14" height="14" viewBox="0 0 24 24" fill="none">
      <circle cx="12" cy="8" r="3.5" stroke="currentColor" stroke-width="1.6"/>
      <path d="M5.5 19c.8-3.2 3.1-5 6.5-5s5.7 1.8 6.5 5" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"/>
    </svg>`;

  const GUEST = `
    <a href="login.html" class="signin-btn">${PERSON} Sign in</a>
    <a href="signin.html" class="signup-btn">${PERSON} Sign up</a>`;

  const USER_MENU = `
    <div class="user-menu">
      <button id="userTrigger" class="user-trigger" type="button" aria-haspopup="true" aria-expanded="false">
        <span id="userAvatar" class="user-avatar">
          <img id="userAvatarImage" alt="Profile avatar">
          <span id="userAvatarFallback"></span>
        </span>
        <span id="userName" class="user-name"></span>
        <svg class="chevron" width="16" height="16" viewBox="0 0 24 24" fill="none" aria-hidden="true">
          <path d="M6 9l6 6 6-6" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"/>
        </svg>
      </button>
      <div id="userDropdown" class="user-dropdown">
        <a href="profile.html" class="dropdown-item profile-item">
          <svg width="22" height="22" viewBox="0 0 24 24" fill="none" aria-hidden="true">
            <circle cx="12" cy="7" r="4" stroke="currentColor" stroke-width="2"/>
            <path d="M5 21v-2c0-3.3 2.7-6 6-6h2c3.3 0 6 2.7 6 6v2" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
          </svg>
          Profile
        </a>
        <a href="profile.html#settings" class="dropdown-item profile-item">
          <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
               stroke-linecap="round" aria-hidden="true">
            <circle cx="12" cy="12" r="3"/>
            <path d="M12 2v2M12 20v2M4.93 4.93l1.41 1.41M17.66 17.66l1.41 1.41M2 12h2M20 12h2M4.93 19.07l1.41-1.41M17.66 6.34l1.41-1.41"/>
          </svg>
          Settings
        </a>
        <button id="logoutBtn" type="button" class="dropdown-item logout-item">
          <svg width="22" height="22" viewBox="0 0 24 24" fill="none" aria-hidden="true">
            <path d="M10 5H6a2 2 0 0 0-2 2v10a2 2 0 0 0 2 2h4" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
            <path d="M14 8l4 4-4 4" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
            <path d="M9 12h9" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
          </svg>
          Log Out
        </button>
      </div>
    </div>`;

  function render(header) {
    const page = location.pathname.split("/").pop();
    const link = (href, text) =>
        `<a href="${href}" class="nav-link${page === href ? " nav-link-active" : ""}">${text}</a>`;

    header.innerHTML = `
      <div class="container header-inner">
        <a href="main.html" class="logo">
          <span class="logo-icon"><img src="https://i.ibb.co/chRY80xC/image.png" alt="CopyMyLife logo"></span>
          <span class="logo-text">CopyMyLife</span>
        </a>
        <nav class="nav">
          ${link("main.html", "Home")}
          ${link("Explore.html", "Explore")}
          ${link("top-routines.html", "Top Routines")}
          <div id="authArea">${GUEST}</div>
        </nav>
      </div>`;

    const token = localStorage.getItem("token");
    if (token) {
      showUser(header.querySelector("#authArea"), token);
    }
  }

  async function showUser(authArea, token) {
    let user;
    try {
      const response = await fetch("/users/me", {headers: {"Authorization": "Bearer " + token}});
      if (!response.ok) return;
      user = await response.json();
    } catch (error) {
      return;
    }

    authArea.innerHTML = USER_MENU;
    const part = (id) => authArea.querySelector("#" + id);
    const name = user.name || "User";
    const image = part("userAvatarImage");
    const fallback = part("userAvatarFallback");
    const showFallback = () => {
      image.style.display = "none";
      fallback.textContent = name.charAt(0).toUpperCase();
      fallback.style.display = "flex";
    };

    part("userName").textContent = name;

    const avatarUrl = SafeDom.safeImageUrl(user.avatarUrl);
    if (avatarUrl) {
      image.onerror = showFallback;
      image.src = avatarUrl;
      image.style.display = "block";
      fallback.style.display = "none";
    } else {
      showFallback();
    }

    const trigger = part("userTrigger");
    const dropdown = part("userDropdown");
    const setOpen = (open) => {
      dropdown.classList.toggle("show", open);
      trigger.classList.toggle("open", open);
      trigger.setAttribute("aria-expanded", String(open));
    };

    trigger.addEventListener("click", (event) => {
      event.stopPropagation();
      setOpen(!dropdown.classList.contains("show"));
    });
    document.addEventListener("click", (event) => {
      if (!event.target.closest(".user-menu")) setOpen(false);
    });
    document.addEventListener("keydown", (event) => {
      if (event.key === "Escape") setOpen(false);
    });
    part("logoutBtn").addEventListener("click", () => CopyMyLifeSession.signOut());
  }

  document.querySelectorAll("header[data-site-header]").forEach(render);
})();
