// Signing out ends the session on the server too, so a copied or leftover token cannot be reused.
(function () {

    function forget() {
        localStorage.removeItem("token");
        localStorage.removeItem("user");
    }

    async function signOut() {
        const token = localStorage.getItem("token");

        forget();

        if (token) {
            try {
                await fetch("/auth/logout", {
                    method: "POST",
                    headers: {"Authorization": "Bearer " + token},
                    keepalive: true
                });
            } catch (error) {
                // Offline: the token is gone from this browser and expires on its own.
            }
        }

        window.location.href = "login.html?signedout=1";
    }

    // The server refused the token (expired, signed out elsewhere, or reset): sign in again.
    function sessionEnded() {
        forget();
        window.location.href = "login.html?session=expired";
    }

    window.CopyMyLifeSession = {
        signOut: signOut,
        sessionEnded: sessionEnded
    };
})();
