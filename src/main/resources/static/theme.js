
(function () {
    const STORAGE_KEY = "copymylife-theme";
    const THEMES = {
        ORIGINAL: "original",
        AUTUMN: "autumn"
    };

    function getSavedTheme() {
        const savedTheme = localStorage.getItem(STORAGE_KEY);
        if (savedTheme === THEMES.AUTUMN) {
            return THEMES.AUTUMN;
        }
        return THEMES.ORIGINAL;
    }

    function buildLeaves() {
        const existing = document.querySelector(".theme-leaves");
        if (existing) {
            existing.remove();
        }

        if (document.documentElement.getAttribute("data-theme") !== THEMES.AUTUMN) {
            return;
        }

        const container = document.createElement("div");
        container.className = "theme-leaves";

        const images = [
            ["top-left", "/img/themes/autumn-top-left.png"],
            ["top-right", "/img/themes/autumn-top-right.png"],
            ["bottom-left", "/img/themes/autumn-bottom-left.png"],
            ["bottom-right", "/img/themes/autumn-bottom-right.png"]
        ];

        images.forEach(([position, src]) => {
            const leaf = document.createElement("img");
            leaf.src = src;
            leaf.alt = "";
            leaf.setAttribute("aria-hidden", "true");
            leaf.className = `theme-leaf ${position}`;
            container.appendChild(leaf);
        });

        document.body.prepend(container);
    }

    function syncThemeButtons(theme) {
        document.querySelectorAll("[data-theme-select]").forEach(button => {
            const isActive = button.getAttribute("data-theme-select") === theme;
            button.classList.toggle("active", isActive);
        });
    }

    function applyTheme(theme) {
        const safeTheme = theme === THEMES.AUTUMN ? THEMES.AUTUMN : THEMES.ORIGINAL;
        document.documentElement.setAttribute("data-theme", safeTheme);
        document.body.setAttribute("data-theme", safeTheme);
        localStorage.setItem(STORAGE_KEY, safeTheme);
        buildLeaves();
        syncThemeButtons(safeTheme);
    }

    function initThemeSelectors() {
        document.querySelectorAll("[data-theme-select]").forEach(button => {
            button.addEventListener("click", function () {
                const selectedTheme = this.getAttribute("data-theme-select");
                applyTheme(selectedTheme);
            });
        });
    }

    document.addEventListener("DOMContentLoaded", function () {
        applyTheme(getSavedTheme());
        initThemeSelectors();
    });

    window.CopyMyLifeTheme = {
        applyTheme
    };
})();
