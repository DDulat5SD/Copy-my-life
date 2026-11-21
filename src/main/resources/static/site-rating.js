(function () {
    const token = localStorage.getItem("token");

    let selectedSiteRating = 0;
    let currentSiteRating = 0;

    document.body.insertAdjacentHTML(
        "beforeend",
        `
    <div class="site-rating-overlay" id="siteRatingAskModal">
      <div class="site-rating-modal">
        <div class="site-rating-icon">★</div>

        <h2 class="site-rating-title">
          Enjoying CopyMyLife?
        </h2>

        <p class="site-rating-text">
          Your feedback helps us make CopyMyLife better.
          Would you like to rate your experience?
        </p>

        <div class="site-rating-actions">
          <button type="button"
                  class="site-rating-btn"
                  id="siteRatingLaterBtn">
            Later
          </button>

          <button type="button"
                  class="site-rating-btn site-rating-btn-primary"
                  id="siteRatingNowBtn">
            Rate now
          </button>
        </div>
      </div>
    </div>

    <div class="site-rating-overlay" id="siteRatingModal">
      <div class="site-rating-modal">
        <div class="site-rating-icon">★</div>

        <h2 class="site-rating-title">
          Rate CopyMyLife
        </h2>

        <p class="site-rating-text">
          How would you rate your experience with CopyMyLife?
        </p>

        <div class="site-rating-stars" id="siteRatingStars">
          <button type="button" class="site-rating-star" data-value="1">★</button>
          <button type="button" class="site-rating-star" data-value="2">★</button>
          <button type="button" class="site-rating-star" data-value="3">★</button>
          <button type="button" class="site-rating-star" data-value="4">★</button>
          <button type="button" class="site-rating-star" data-value="5">★</button>
        </div>

        <div class="site-rating-actions">
          <button type="button"
                  class="site-rating-btn"
                  id="siteRatingCancelBtn">
            Cancel
          </button>

          <button type="button"
                  class="site-rating-btn site-rating-btn-primary"
                  id="siteRatingSubmitBtn"
                  disabled>
            Submit rating
          </button>
        </div>

        <div class="site-rating-summary" id="siteRatingSummary"></div>
      </div>
    </div>
  `
    );

    const askModal =
        document.getElementById("siteRatingAskModal");

    const ratingModal =
        document.getElementById("siteRatingModal");

    const submitBtn =
        document.getElementById("siteRatingSubmitBtn");

    const stars =
        document.querySelectorAll(".site-rating-star");

    function renderStars() {
        stars.forEach(function (star) {
            const value = Number(star.dataset.value);

            star.classList.toggle(
                "active",
                value <= selectedSiteRating
            );
        });

        submitBtn.disabled =
            selectedSiteRating === 0;
    }

    function openRatingModal() {
        askModal.classList.remove("show");

        selectedSiteRating =
            currentSiteRating;

        renderStars();

        ratingModal.classList.add("show");
    }

    function closeRatingModal() {
        ratingModal.classList.remove("show");
    }
    function showRatingThankYou() {
        const thankYouModal = document.createElement("div");

        thankYouModal.className = "site-rating-overlay show";

        thankYouModal.innerHTML = `
      <div class="site-rating-modal">
        <div class="site-rating-icon">✓</div>

        <h2 class="site-rating-title">
          Thank you!
        </h2>

        <p class="site-rating-text">
          Your feedback helps us improve CopyMyLife.
        </p>
      </div>
    `;

        document.body.appendChild(thankYouModal);

        setTimeout(function () {
            thankYouModal.remove();
        }, 1500);
    }

    function openAskModal() {
        if (!token) return;

        askModal.classList.add("show");
    }

    async function loadCurrentSiteRating() {
        if (!token) return;

        try {
            const response =
                await fetch("/site-ratings", {
                    headers: {
                        "Authorization": "Bearer " + token
                    }
                });

            if (!response.ok) return;

            const data =
                await response.json();

            currentSiteRating =
                Number(data.userRating || 0);

            document.getElementById("siteRatingSummary").textContent =
                data.ratingCount > 0
                    ? `${Number(data.averageRating || 0).toFixed(1)} average from ${data.ratingCount} ratings`
                    : "No ratings yet";

            if (currentSiteRating > 0) {
                return;
            }

            const laterUntil =
                Number(
                    localStorage.getItem(
                        "siteRatingLaterUntil"
                    ) || 0
                );

            if (Date.now() < laterUntil) {
                return;
            }

            const delay =
                600000 + Math.floor(Math.random() * 600000);

            setTimeout(function () {
                openAskModal();
            }, delay);

        } catch (error) {
            console.error("SITE RATING ERROR:", error);
        }
    }

    stars.forEach(function (star) {
        star.addEventListener("click", function () {
            selectedSiteRating =
                Number(star.dataset.value);

            renderStars();
        });
    });

    document.getElementById("siteRatingNowBtn")
        .addEventListener("click", function () {
            openRatingModal();
        });

    document.getElementById("siteRatingLaterBtn")
        .addEventListener("click", function () {
            askModal.classList.remove("show");

            const oneDay =
                24 * 60 * 60 * 1000;

            localStorage.setItem(
                "siteRatingLaterUntil",
                Date.now() + oneDay
            );
        });

    document.getElementById("siteRatingCancelBtn")
        .addEventListener("click", function () {
            closeRatingModal();
        });

    submitBtn.addEventListener("click", async function () {
        if (!token || selectedSiteRating === 0) return;

        try {
            const response =
                await fetch("/site-ratings", {
                    method: "POST",

                    headers: {
                        "Content-Type": "application/json",
                        "Authorization": "Bearer " + token
                    },

                    body: JSON.stringify({
                        value: selectedSiteRating
                    })
                });

            if (!response.ok) {
                throw new Error("Failed to rate site");
            }

            const data =
                await response.json();

            currentSiteRating =
                Number(data.userRating || selectedSiteRating);

            localStorage.removeItem(
                "siteRatingLaterUntil"
            );

            const mainRating =
                document.getElementById("homeRating");

            if (mainRating) {
                mainRating.textContent =
                    Number(
                        data.averageRating || 0
                    ).toFixed(1);
            }

            closeRatingModal();
            showRatingThankYou();

        } catch (error) {
            console.error(error);
        }
    });

    const footerRateButton =
        document.getElementById("footerRateSiteBtn");

    if (footerRateButton) {
        footerRateButton.addEventListener(
            "click",
            function () {
                if (!token) {
                    window.location.href =
                        "login.html";
                    return;
                }

                openRatingModal();
            }
        );
    }


    loadCurrentSiteRating();
})();