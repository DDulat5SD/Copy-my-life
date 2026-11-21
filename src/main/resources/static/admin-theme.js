
(function () {
    const KEY = "copymylife-theme";
    const OPTIONS = ["original", "autumn"];

    function currentTheme() {
        return localStorage.getItem(KEY) === "autumn"
            ? "autumn"
            : "original";
    }

    function updateButtons() {
        document.querySelectorAll("[data-admin-theme]").forEach(button => {
            const selected = button.dataset.adminTheme === currentTheme();
            button.classList.toggle("active", selected);
            button.setAttribute("aria-pressed", String(selected));
        });
    }

    function updateLeaves() {
        let wrapper = document.getElementById("adminAutumnLeaves");

        if (currentTheme() !== "autumn") {
            if (wrapper) wrapper.remove();
            return;
        }

        if (wrapper) return;

        wrapper = document.createElement("div");
        wrapper.id = "adminAutumnLeaves";
        wrapper.setAttribute("aria-hidden", "true");

        const images = [
            ["top-left", "/img/themes/autumn-top-left.png"],
            ["top-right", "/img/themes/autumn-top-right.png"],
            ["bottom-left", "/img/themes/autumn-bottom-left.png"],
            ["bottom-right", "/img/themes/autumn-bottom-right.png"]
        ];

        images.forEach(([position, src]) => {
            const image = document.createElement("img");
            image.className = "admin-leaf " + position;
            image.src = src;
            image.alt = "";
            image.draggable = false;
            wrapper.appendChild(image);
        });

        document.body.prepend(wrapper);
    }

    function applyTheme(theme) {
        const selected = OPTIONS.includes(theme) ? theme : "original";

        localStorage.setItem(KEY, selected);
        document.documentElement.dataset.adminTheme = selected;
        document.body.dataset.adminTheme = selected;

        updateButtons();
        updateLeaves();
    }

    function createSwitcher() {
        if (document.getElementById("adminThemeSwitcher")) return;

        const switcher = document.createElement("div");
        switcher.id = "adminThemeSwitcher";
        switcher.setAttribute("role", "group");
        switcher.setAttribute("aria-label", "Choose admin theme");

        OPTIONS.forEach(theme => {
            const button = document.createElement("button");
            button.type = "button";
            button.dataset.adminTheme = theme;
            button.textContent = theme === "autumn" ? "🍂 Autumn" : "Original";

            button.addEventListener("click", () => {
                applyTheme(theme);
            });

            switcher.appendChild(button);
        });

        document.body.appendChild(switcher);
        updateButtons();
    }

    document.documentElement.dataset.adminTheme = currentTheme();

    document.addEventListener("DOMContentLoaded", () => {
        createSwitcher();
        applyTheme(currentTheme());
    });

    window.addEventListener("storage", event => {
        if (event.key === KEY) {
            applyTheme(event.newValue);
        }
    });

    window.AdminTheme = { applyTheme };
})();
