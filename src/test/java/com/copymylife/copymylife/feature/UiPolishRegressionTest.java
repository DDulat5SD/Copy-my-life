package com.copymylife.copymylife.feature;

import com.copymylife.copymylife.service.EmailService;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * UI fixes without a new story: Sign In and Sign Up share one look and switch instantly,
 * forms explain mistakes under the field instead of the browser's own bubble, the main
 * footer links to every real page, Halloween Explore has its own heading, no sideways
 * scrollbar, working Like, Save and Rating, and Top Routines can show Halloween only.
 * Needs TEST_DB_URL (a local *_test database); the browser part also needs node and Chrome.
 */
@EnabledIfEnvironmentVariable(
        named = "TEST_DB_URL",
        matches = "jdbc:postgresql://(127\\.0\\.0\\.1|localhost):\\d+/\\w+_test"
)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=${TEST_DB_URL}",
                "spring.datasource.username=${TEST_DB_USERNAME:test}",
                "spring.datasource.password=${TEST_DB_PASSWORD:test}",
                "jwt.secret=dGVzdC1vbmx5LWtleS10ZXN0LW9ubHkta2V5LXRlc3Qtb25seS1rZXktdGVzdC1vbmx5",
                "spring.mail.username=test",
                "spring.mail.password=test",
                "MAILJET_API_KEY=test",
                "MAILJET_SECRET_KEY=test",
                "MAILJET_SENDER_EMAIL=test@invalid.test"
        }
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UiPolishRegressionTest {

    private static final Path STATIC = Path.of("src/main/resources/static");
    private static final String PASSWORD = "Audit#12345";
    private static final String BOUNDARY = "UiPolishBoundary";
    private static final String BILLY = "The Puzzle Master's Schedule";
    private static final String HALLOWEEN_TITLE = "Halloween Explore Routines";
    private static final String HALLOWEEN_SUBTITLE =
            "Discover spooky routines, haunting habits, and eerie daily rituals to make your Halloween unforgettable.";
    private static final List<String> WIDTHS = List.of("360", "390", "1280", "1366", "1536", "1920");
    private static final List<String> CATEGORIES = List.of(
            "All", "Morning", "Fitness", "Study", "Wellness", "Productivity", "Creators", "Halloween");

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private EmailService emailService;

    private JsonNode browser;
    private final Map<String, Long> halloween = new LinkedHashMap<>();
    private long fanId;
    private long morningId;
    private JsonNode billyBuiltIn;

    private record Reply(int status, String body) {
    }

    // ---- 1. Sign In and Sign Up ----

    @Test
    void signInAndSignUpSwitchWithoutAnExitAnimation() throws Exception {
        for (String page : List.of("login.html", "signin.html", "login.css", "signin.css")) {
            String source = read(page);
            assertFalse(source.contains("page-exit"), page + " still fades the page out before leaving");
        }
        JsonNode view = page("switching");
        assertFalse(view.path("signUpToSignInDelayed").asBoolean(), view.toString());
        assertFalse(view.path("signInToSignUpDelayed").asBoolean(), view.toString());
        assertTrue(view.path("arrivedAtSignIn").asBoolean(), view.toString());
        assertTrue(view.path("arrivedAtSignUp").asBoolean(), view.toString());
        String transition = view.path("bodyTransition").asString();
        assertFalse(transition.contains("opacity") || transition.contains("transform") || transition.contains("all"),
                "no fade or slide when a page is left " + view);
    }

    @Test
    void signInKeepsItsOwnLayoutWithTheFormLeftAndTheMountainRight() {
        List<String> problems = new ArrayList<>();
        for (String layout : List.of("brand1280", "brand1366", "brandAutumn1280")) {
            JsonNode login = page(layout).path("login");
            JsonNode form = login.path("form");
            JsonNode photo = login.path("photo");
            JsonNode image = login.path("photoImage");
            JsonNode card = login.path("card");
            if (form.isNull() || photo.isNull() || image.isNull()) {
                problems.add(layout + " panels missing " + login);
                continue;
            }
            if (form.path("right").asInt() > photo.path("left").asInt() + 1) {
                problems.add(layout + " form is not left of the photo " + login);
            }
            if (photo.path("width").asInt() < card.path("width").asInt() * 0.45) {
                problems.add(layout + " photo panel is not the large right half " + login);
            }
            if (Math.abs(image.path("width").asInt() - photo.path("width").asInt()) > 2
                    || Math.abs(image.path("height").asInt() - photo.path("height").asInt()) > 2) {
                problems.add(layout + " the mountain photo does not fill its panel " + login);
            }
            if (!"24px".equals(login.path("cardRadius").asString())) {
                problems.add(layout + " card lost its rounded corners " + login);
            }
            if (login.path("logoLeft").asInt() > form.path("left").asInt() + 100) {
                problems.add(layout + " logo is not top left " + login);
            }
        }
        assertEquals(List.of(), problems);
    }

    @Test
    void signUpKeepsThePhotoLeftAndTheFormRight() {
        List<String> problems = new ArrayList<>();
        for (String layout : List.of("brand1280", "brand1366", "brandAutumn1280")) {
            JsonNode signup = page(layout).path("signup");
            if (signup.path("photo").isNull()
                    || signup.path("photo").path("right").asInt() > signup.path("form").path("left").asInt() + 1) {
                problems.add(layout + " photo is not left of the form " + signup);
            }
        }
        assertEquals(List.of(), problems);
    }

    @Test
    void signInAndSignUpUseTheHeaderLogo() {
        List<String> problems = new ArrayList<>();
        for (String layout : List.of("brand1280", "brand1366", "brand390", "brandAutumn1280", "brandAutumn390")) {
            JsonNode header = page(layout).path("main");
            for (String page : List.of("login", "signup")) {
                JsonNode brand = page(layout).path(page);
                for (String key : List.of("iconWidth", "iconHeight", "iconRadius", "iconBackground", "imageSrc",
                        "nameFamily", "nameSize", "nameWeight", "nameColor", "nameText", "gap")) {
                    if (!header.path(key).asString().equals(brand.path(key).asString())) {
                        problems.add(layout + " " + page + " " + key + ": header " + header.path(key) + ", page " + brand.path(key));
                    }
                }
                if (brand.path("overflow").asInt() > 0) {
                    problems.add(layout + " " + page + " scrolls sideways " + brand);
                }
            }
        }
        assertEquals(List.of(), problems);
        assertEquals("https://i.ibb.co/chRY80xC/image.png", page("brand1280").path("main").path("imageSrc").asString());
    }

    @Test
    void bothAuthUrlsStillWorkWithoutRedirects() throws Exception {
        for (String page : List.of("/login.html", "/signin.html", "/login.html?signedout=1",
                "/login.html?session=expired", "/forgot-password.html", "/reset-password.html")) {
            Reply reply = get(page);
            assertEquals(200, reply.status(), page);
        }
        assertTrue(read("login.html").contains("href=\"signin.html\""), "Sign In links to Sign Up");
        assertTrue(read("signin.html").contains("href=\"login.html\""), "Sign Up links to Sign In");
        assertEquals("You have signed out. See you soon!", page("loginNotices").path("signedOut").asString());
        assertEquals("Your session has ended. Please sign in again.", page("loginNotices").path("expired").asString());
    }

    // ---- 2. Mistakes under the field, never in a browser bubble ----

    @Test
    void noFormFallsBackToTheBrowsersValidationBubble() throws Exception {
        Pattern formTag = Pattern.compile("<form\\b[^>]*>");
        List<String> problems = new ArrayList<>();
        try (Stream<Path> files = Files.list(STATIC)) {
            for (Path file : files.filter(this::sitePage).toList()) {
                String source = Files.readString(file);
                Matcher tag = formTag.matcher(source);
                while (tag.find()) {
                    if (!tag.group().contains("novalidate")) {
                        problems.add(file.getFileName() + ": " + tag.group());
                    }
                }
                for (String call : List.of("reportValidity(", "checkValidity(")) {
                    if (source.contains(call)) {
                        problems.add(file.getFileName() + " calls " + call);
                    }
                }
            }
        }
        assertEquals(List.of(), problems);
    }

    @Test
    void requiredFieldsStayRequired() throws Exception {
        String signUp = read("signin.html");
        for (String id : List.of("id=\"fullname\"", "id=\"email\"", "id=\"password\"")) {
            String field = signUp.substring(signUp.indexOf(id), signUp.indexOf(">", signUp.indexOf(id)));
            assertTrue(field.contains("required"), id + " lost required");
        }
        assertEquals(400, post("/auth/register", Map.of("name", "", "email", "new_" + UUID.randomUUID() + "@audit.test",
                "password", PASSWORD), null).status(), "the server still refuses an empty name");
        assertEquals(400, post("/auth/register", Map.of("name", "Ada Lovelace", "email", "not-an-email",
                "password", PASSWORD), null).status(), "the server still refuses a bad email");
        assertEquals(400, post("/auth/register", Map.of("name", "Ada Lovelace", "email", "new_" + UUID.randomUUID() + "@audit.test",
                "password", "short"), null).status(), "the server still refuses a weak password");
    }

    @Test
    void signUpExplainsEveryMistakeUnderItsField() {
        JsonNode empty = page("signUpEmpty");
        assertTrue(empty.path("noValidate").asBoolean(), empty.toString());
        assertEquals("Please enter your full name.", empty.path("name").asString(), empty.toString());
        assertEquals("Please enter your email address.", empty.path("email").asString(), empty.toString());
        assertEquals("Please create a password.", empty.path("password").asString(), empty.toString());
        assertEquals("fullname", empty.path("focused").asString(), empty.toString());
        assertEquals("true", empty.path("nameInvalid").asString(), empty.toString());
        assertEquals(1, empty.path("messagesUnderName").asInt(), "one message per field " + empty);
        assertEquals(0, empty.path("requests").asInt(), "nothing is sent while the form is wrong " + empty);

        JsonNode invalid = page("signUpInvalidEmail");
        assertEquals("", invalid.path("name").asString(), invalid.toString());
        assertEquals("Please enter a valid email address.", invalid.path("email").asString(), invalid.toString());

        JsonNode weak = page("signUpWeakPassword");
        assertEquals("Your password must meet all 4 requirements.", weak.path("password").asString(), weak.toString());
        assertEquals(4, weak.path("rules").asInt(), "the password requirements are listed " + weak);
        assertTrue(weak.path("rulesPassed").asInt() < 4, weak.toString());

        JsonNode valid = page("signUpValidBeforeSubmit");
        assertEquals("", valid.path("name").asString() + valid.path("email").asString() + valid.path("password").asString(),
                valid.toString());

        JsonNode duplicate = page("signUpDuplicate");
        assertEquals("This email is already registered.", duplicate.path("email").asString(), duplicate.toString());
        assertEquals("", duplicate.path("form").asString(), "not repeated under the button " + duplicate);

        JsonNode down = page("signUpServerError");
        assertEquals("Server error. Please try again later.", down.path("form").asString(), down.toString());
        assertEquals("", down.path("email").asString(), down.toString());

        JsonNode created = page("signUpSuccess");
        assertTrue(created.path("url").asString().endsWith("/main.html"), created.toString());
        assertTrue(created.path("hasToken").asBoolean(), created.toString());
    }

    @Test
    void signInExplainsEveryMistakeUnderItsField() {
        JsonNode empty = page("loginEmpty");
        assertTrue(empty.path("noValidate").asBoolean(), empty.toString());
        assertEquals("Please enter your email address.", empty.path("email").asString(), empty.toString());
        assertEquals("Please enter your password.", empty.path("password").asString(), empty.toString());
        assertEquals("email", empty.path("focused").asString(), empty.toString());
        assertEquals(0, empty.path("requests").asInt(), empty.toString());

        JsonNode invalid = page("loginInvalidEmail");
        assertEquals("Please enter a valid email address.", invalid.path("email").asString(), invalid.toString());
        assertEquals("", invalid.path("password").asString(), invalid.toString());

        JsonNode wrong = page("loginWrongPassword");
        assertEquals("Invalid email or password. Please try again.", wrong.path("form").asString(), wrong.toString());
        assertEquals("", wrong.path("email").asString() + wrong.path("password").asString(), wrong.toString());

        JsonNode signedIn = page("loginSuccess");
        assertTrue(signedIn.path("url").asString().endsWith("/main.html"), signedIn.toString());
        assertTrue(signedIn.path("hasToken").asBoolean(), signedIn.toString());
    }

    @Test
    void otherFormsExplainEmptyFieldsInline() {
        assertEquals("Please enter your email address.", page("forgotEmpty").path("error").asString());
        assertEquals("Please enter a valid email address.", page("forgotInvalid").path("error").asString());
        assertTrue(page("forgotSent").path("message").asString().contains("Check your email"), page("forgotSent").toString());
        assertEquals("Give the list a title.", page("todoEmpty").path("error").asString(), page("todoEmpty").toString());
        assertEquals("Write something or add a photo.", page("postEmpty").path("error").asString(), page("postEmpty").toString());
        assertEquals("Habit name is required", page("habitEmpty").path("title").asString(), page("habitEmpty").toString());
        assertEquals("Enter a valid duration", page("habitEmpty").path("duration").asString(), page("habitEmpty").toString());
        assertEquals("Please enter your admin email.", page("adminLoginEmpty").path("email").asString(), page("adminLoginEmpty").toString());
        assertEquals("Please enter your password.", page("adminLoginEmpty").path("password").asString(), page("adminLoginEmpty").toString());
    }

    @Test
    void formsNeverOpenABrowserDialogOrThrow() {
        assertEquals("[]", page("dialogs").toString(), "every message is shown on the page");
        assertEquals("[]", page("errors").toString(), "no page throws");
    }

    // ---- 3. Footer ----

    @Test
    void footerLinksOnlyToPagesThatExist() throws Exception {
        JsonNode footer = page("footer");
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("Home", "main.html");
        expected.put("Explore Routines", "Explore.html");
        expected.put("Top Routines", "top-routines.html");
        expected.put("Create Routine", "profile.html#create-routine");
        expected.put("My Routines", "profile.html#my-routines");
        expected.put("Weekly Calendar", "profile.html#calendar");
        expected.put("Progress", "profile.html#progress");
        expected.put("My Profile", "profile.html");
        expected.put("To-do Lists", "profile.html#todo-lists");
        expected.put("Posts", "profile.html#posts");

        Map<String, String> links = new LinkedHashMap<>();
        footer.path("links").forEach(link -> links.put(link.path("text").asString(), link.path("href").asString()));
        assertEquals(expected, links);
        for (String href : new HashSet<>(links.values())) {
            assertEquals(200, get("/" + href.replaceAll("#.*", "")).status(), href);
        }
        assertTrue(footer.path("copyright").asString().contains("© 2026 CopyMyLife"), footer.toString());
        assertTrue(footer.path("rateButton").asBoolean(), footer.toString());
        assertTrue(page("footerRate").path("ratingModalOpen").asBoolean(), "Rate CopyMyLife still opens " + page("footerRate"));
    }

    @Test
    void footerLinksOpenTheRightPartOfTheProfile() {
        JsonNode view = page("footerSections");
        assertTrue(view.path("calendar").asBoolean(), view.toString());
        assertTrue(view.path("myRoutines").asBoolean(), view.toString());
        assertTrue(view.path("createRoutine").asBoolean(), view.toString());
        assertTrue(view.path("progress").asBoolean(), view.toString());
        assertTrue(page("footerGuest").path("url").asString().endsWith("/login.html"),
                "a visitor is still asked to sign in " + page("footerGuest"));
    }

    @Test
    void footerFitsEveryScreenAndTheme() {
        List<String> problems = new ArrayList<>();
        for (String theme : List.of("original", "autumn")) {
            for (String width : WIDTHS) {
                JsonNode view = page("footer_" + theme + "_" + width);
                if (view.path("overflow").asInt() > 0 || view.path("rightmost").asInt() > view.path("width").asInt()) {
                    problems.add(theme + " " + width + " scrolls sideways " + view);
                }
                if (view.path("height").asInt() > (Integer.parseInt(width) < 600 ? 1150 : 520)) {
                    problems.add(theme + " " + width + " footer too tall " + view);
                }
                if (view.path("minContrast").asDouble() < 4.5) {
                    problems.add(theme + " " + width + " hard to read " + view);
                }
            }
        }
        assertEquals(List.of(), problems);
    }

    // ---- 4. Halloween heading ----

    @Test
    void halloweenExploreHasItsOwnHeadingAndTheOthersComeBack() {
        JsonNode view = page("heading");
        assertEquals(HALLOWEEN_TITLE, view.path("halloween").path("title").asString(), view.toString());
        assertEquals(HALLOWEEN_SUBTITLE, view.path("halloween").path("subtitle").asString(), view.toString());
        assertEquals("Explore routines", view.path("all").path("title").asString(), view.toString());
        assertEquals("Find routines, habits, and daily systems you can adapt to your own life.",
                view.path("all").path("subtitle").asString(), view.toString());
        assertEquals("Explore morning routines", view.path("morning").path("title").asString(), view.toString());
        assertEquals(HALLOWEEN_TITLE, view.path("returning").path("title").asString(), "Explore.html?category=halloween " + view);
    }

    @Test
    void halloweenHeadingIsFullyReadableOnEveryScreen() {
        List<String> problems = new ArrayList<>();
        for (String theme : List.of("original", "autumn")) {
            for (String width : WIDTHS) {
                JsonNode view = page("explore_" + theme + "_halloween_" + width);
                if (!HALLOWEEN_TITLE.equals(view.path("title").asString())) {
                    problems.add(theme + " " + width + " wrong title " + view);
                }
                if (view.path("titleClipped").asBoolean() || view.path("titleLeft").asInt() < 0
                        || view.path("titleRight").asInt() > view.path("width").asInt()) {
                    problems.add(theme + " " + width + " title cut off " + view);
                }
                if (view.path("titleContrast").asDouble() < 4.5) {
                    problems.add(theme + " " + width + " title hard to read " + view);
                }
            }
        }
        assertEquals(List.of(), problems);
    }

    // ---- 5. No sideways scrollbar ----

    @Test
    void exploreNeverScrollsSideways() {
        List<String> problems = new ArrayList<>();
        for (String theme : List.of("original", "autumn")) {
            for (String filter : List.of("all", "halloween", "creators")) {
                for (String width : WIDTHS) {
                    String name = theme + " " + filter + " " + width;
                    JsonNode closed = page("explore_" + theme + "_" + filter + "_" + width);
                    for (JsonNode view : List.of(closed, closed.path("viewAllOpen"))) {
                        String state = view == closed ? "" : " (View all open)";
                        if (view.path("scrollWidth").asInt() > view.path("clientWidth").asInt()) {
                            problems.add(name + state + " scrolls sideways " + view);
                        }
                        if (view.path("cardsOutside").asInt() > 0) {
                            problems.add(name + state + " cards cut off " + view);
                        }
                        if (!view.path("viewAllVisible").asBoolean() || view.path("viewAllOffCenter").asInt() > 2) {
                            problems.add(name + state + " View all missing or off centre " + view);
                        }
                    }
                    if ("halloween".equals(filter) && closed.path("filtersScroll").asBoolean()
                            && "auto".equals(closed.path("filtersScrollbarColor").asString())) {
                        problems.add(name + " white scrollbar under the Halloween filters " + closed);
                    }
                }
            }
        }
        assertEquals(List.of(), problems);
    }

    @Test
    void desktopShowsAllEightCategoriesInOneRow() {
        List<String> problems = new ArrayList<>();
        for (String theme : List.of("original", "autumn")) {
            for (String filter : List.of("all", "halloween", "creators")) {
                for (String width : List.of("1280", "1366", "1536", "1920")) {
                    String name = theme + " " + filter + " " + width;
                    JsonNode view = page("explore_" + theme + "_" + filter + "_" + width);
                    List<String> chips = new ArrayList<>();
                    view.path("chips").forEach(chip -> chips.add(chip.asString()));
                    if (!CATEGORIES.equals(chips)) {
                        problems.add(name + " categories " + chips);
                    }
                    if (view.path("rows").asInt() != 1) {
                        problems.add(name + " categories in " + view.path("rows") + " rows " + view);
                    }
                    if (view.path("filtersScroll").asBoolean() || view.path("chipsClipped").asInt() > 0) {
                        problems.add(name + " categories do not fit " + view);
                    }
                }
            }
        }
        assertEquals(List.of(), problems);
    }

    @Test
    void phonesScrollTheCategoriesInsideTheirRowOnly() {
        for (String width : List.of("360", "390")) {
            JsonNode view = page("explore_original_halloween_" + width);
            assertEquals(1, view.path("rows").asInt(), view.toString());
            assertTrue(view.path("filtersScroll").asBoolean(), "the row scrolls on its own " + view);
            assertTrue(view.path("scrollWidth").asInt() <= view.path("clientWidth").asInt(), "the page does not " + view);
        }
    }

    // ---- 6. Halloween Like, Save and Rating ----

    @Test
    void everyHalloweenCardUsesItsOwnRealRoutine() {
        Set<Long> ids = new HashSet<>(halloween.values());
        JsonNode view = page("halloweenCards");
        assertEquals(6, view.path("cards").size(), view.toString());
        Set<Long> seen = new HashSet<>();
        view.path("cards").forEach(card -> {
            long id = card.path("saveId").asLong();
            assertTrue(ids.contains(id), "a Halloween routine " + card);
            assertEquals(id, card.path("likeId").asLong(), card.toString());
            assertEquals(id, card.path("linkId").asLong(), card.toString());
            assertTrue(card.path("rating").asBoolean(), "rating shown " + card);
            seen.add(id);
        });
        assertEquals(6, seen.size(), "no two cards share a routine " + view);
        assertEquals(halloween.get(BILLY), view.path("cards").get(0).path("saveId").asLong(), view.toString());

        List<JsonNode> builtIn = new ArrayList<>();
        view.path("viewAll").forEach(card -> {
            if (!card.path("community").asBoolean()) {
                builtIn.add(card);
            }
        });
        assertEquals(4, builtIn.size(), view.toString());
        view.path("viewAll").forEach(card -> {
            assertTrue(card.path("saveId").asLong() > 0, card.toString());
            assertEquals(card.path("saveId").asLong(), card.path("likeId").asLong(), card.toString());
        });
        builtIn.forEach(card -> {
            assertTrue(ids.contains(card.path("saveId").asLong()), card.toString());
            assertEquals(card.path("saveId").asLong(), card.path("likeId").asLong(), card.toString());
            assertEquals(card.path("saveId").asLong(), card.path("linkId").asLong(), card.toString());
            seen.add(card.path("saveId").asLong());
        });
        assertEquals(10, seen.size(), view.toString());
        assertEquals(0, view.path("overlaps").asInt(), "buttons never cover each other " + view);
    }

    @Test
    void halloweenRatingShowsTheRealAverage() {
        JsonNode featured = page("halloweenCards").path("cards").get(0);
        assertEquals(String.format(java.util.Locale.ROOT, "%.1f", billyBuiltIn.path("averageRating").asDouble()),
                featured.path("ratingValue").asString(), featured.toString());
        assertEquals("(" + billyBuiltIn.path("ratingCount").asLong() + ")", featured.path("ratingCount").asString(),
                featured.toString());
    }

    @Test
    void halloweenLikeAndSaveAreStoredOnceAndSurviveARefresh() {
        long billy = halloween.get(BILLY);
        JsonNode view = page("halloweenSocial");
        assertTrue(view.path("likedAfterReload").asBoolean(), view.toString());
        assertTrue(view.path("savedAfterReload").asBoolean(), view.toString());
        assertEquals(view.path("saveCountBefore").asLong() + 1, view.path("saveCountAfterReload").asLong(), view.toString());
        assertEquals(1, count("select count(*) from liked_routines where user_id = ? and routine_id = ?", fanId, billy));
        assertEquals(1, count("select count(*) from saved_routines where user_id = ? and routine_id = ?", fanId, billy));

        JsonNode detail = page("halloweenDetail");
        assertTrue(detail.path("liked").asBoolean(), detail.toString());
        assertTrue(detail.path("saved").asBoolean(), detail.toString());
        assertEquals(String.format(java.util.Locale.ROOT, "%.1f", billyBuiltIn.path("averageRating").asDouble()),
                detail.path("ratingAverage").asString(), detail.toString());
        assertTrue(detail.path("ratingInput").asBoolean(), detail.toString());
    }

    @Test
    void visitorsAreAskedToSignInFromHalloweenCards() {
        JsonNode guest = page("halloweenGuest");
        assertTrue(guest.path("buttons").asInt() >= 12, guest.toString());
        assertTrue(guest.path("urlAfterLike").asString().endsWith("/login.html"), guest.toString());
    }

    @Test
    void halloweenCardsFitOnPhones() {
        for (String width : List.of("360", "390")) {
            JsonNode view = page("halloweenPhone" + width);
            assertEquals(0, view.path("overlaps").asInt(), width + " " + view);
            assertEquals(0, view.path("overflow").asInt(), width + " " + view);
        }
    }

    // ---- 7. Top Routines: Halloween ----

    @Test
    void topRoutinesFiltersHalloweenWhateverTheLetterCase() throws Exception {
        Set<Long> ids = new HashSet<>(halloween.values());
        for (String category : List.of("Halloween", "halloween", "HALLOWEEN")) {
            for (String sort : List.of("overall", "liked", "saved", "rated")) {
                for (String period : List.of("day", "week", "month")) {
                    Reply reply = get("/top-routines?period=" + period + "&sort=" + sort + "&category=" + category);
                    assertEquals(200, reply.status(), reply.body());
                    JsonNode list = json.readTree(reply.body());
                    assertTrue(list.size() > 0, category + " " + sort + " " + period);
                    list.forEach(routine -> {
                        assertTrue(ids.contains(routine.path("id").asLong()), routine.toString());
                        assertEquals("Halloween", routine.path("category").asString(), routine.toString());
                    });
                }
            }
        }
        JsonNode all = json.readTree(get("/top-routines?period=week&sort=liked&category=all").body());
        List<Long> allIds = new ArrayList<>();
        all.forEach(routine -> allIds.add(routine.path("id").asLong()));
        assertTrue(allIds.contains(morningId) || all.size() == 5, all.toString());
    }

    @Test
    void topRoutinesOffersHalloween() {
        JsonNode view = page("topRoutines");
        assertTrue(view.path("options").toString().contains("\"Halloween\""), view.toString());
        assertTrue(view.path("options").toString().contains("\"All categories\""), view.toString());
        Set<Long> ids = new HashSet<>(halloween.values());
        List<String> problems = new ArrayList<>();
        view.path("halloween").properties().forEach(entry -> {
            JsonNode shown = entry.getValue();
            if (shown.size() == 0) {
                problems.add(entry.getKey() + " shows nothing");
            }
            shown.forEach(id -> {
                if (!ids.contains(id.asLong())) {
                    problems.add(entry.getKey() + " shows " + id);
                }
            });
        });
        assertEquals(List.of(), problems, view.toString());
        assertTrue(view.path("halloween").path("liked_week").toString().contains(String.valueOf(halloween.get(BILLY))),
                view.toString());
        assertTrue(view.path("morning").size() > 0, view.toString());
        view.path("morning").forEach(id -> assertFalse(ids.contains(id.asLong()), "Morning shows no Halloween routine " + view));
        assertEquals("No Halloween routines found for this period.", view.path("empty").asString(), view.toString());
        assertEquals("No routines found for this period.", view.path("emptyAll").asString(), view.toString());
    }

    // ---- real browser ----

    @BeforeAll
    void openThePagesInABrowser() throws Exception {
        assertTrue(jdbc.queryForObject("select current_database()", String.class).endsWith("_test"),
                "Refusing to run outside a *_test database");

        jdbc.queryForList("select id, title from routines where built_in = true and category = 'Halloween' "
                        + "and deleted = false order by id")
                .forEach(row -> halloween.putIfAbsent((String) row.get("title"), ((Number) row.get("id")).longValue()));
        assertEquals(10, halloween.size(), "the ten Halloween routines are seeded " + halloween);

        String fanEmail = "ui_fan_" + UUID.randomUUID().toString().substring(0, 10) + "@audit.test";
        JsonNode fan = register("Ui Fan", fanEmail);
        String fanToken = fan.path("token").asString();
        fanId = fan.path("user").path("id").asLong();

        String raterToken = register("Ui Rater", "ui_rater_" + UUID.randomUUID().toString().substring(0, 10) + "@audit.test")
                .path("token").asString();
        long billy = halloween.get(BILLY);
        assertEquals(200, post("/liked-routines/" + billy, null, raterToken).status());
        assertEquals(200, post("/saved-routines/" + billy, null, raterToken).status());
        Reply rated = post("/routine-ratings/" + billy, Map.of("value", 4), raterToken);
        assertEquals(200, rated.status(), rated.body());
        morningId = routine(raterToken, "Ui morning " + UUID.randomUUID());
        assertEquals(200, post("/liked-routines/" + morningId, null, raterToken).status());

        for (JsonNode routine : json.readTree(get("/routines/built-in").body())) {
            if (routine.path("id").asLong() == billy) {
                billyBuiltIn = routine;
            }
        }

        String chrome = findChrome();
        if (chrome == null || !nodeAvailable()) {
            return;
        }

        Map<String, Object> fixtures = new LinkedHashMap<>();
        fixtures.put("base", "http://127.0.0.1:" + port);
        fixtures.put("fanToken", fanToken);
        fixtures.put("fanEmail", fanEmail);
        fixtures.put("password", PASSWORD);
        fixtures.put("billyId", billy);
        fixtures.put("newEmail", "ui_new_" + UUID.randomUUID().toString().substring(0, 10) + "@audit.test");

        Path dir = Files.createTempDirectory("ui-polish-browser");
        Files.writeString(dir.resolve("fixtures.json"), json.writeValueAsString(fixtures));
        Process process = new ProcessBuilder(
                "node", "src/test/e2e/ui-polish-check.mjs", chrome,
                dir.resolve("fixtures.json").toString(), dir.resolve("results.json").toString()
        ).redirectErrorStream(true).redirectOutput(dir.resolve("node.log").toFile()).start();

        if (!process.waitFor(8, TimeUnit.MINUTES)) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            throw new AssertionError("Browser check timed out: " + readQuietly(dir.resolve("node.log")));
        }
        assertEquals(0, process.exitValue(), () -> readQuietly(dir.resolve("node.log")));
        browser = json.readTree(Files.readString(dir.resolve("results.json")));
    }

    private JsonNode page(String name) {
        Assumptions.assumeTrue(browser != null, "Chrome or node not found");
        return browser.path(name);
    }

    // ---- helpers ----

    private boolean sitePage(Path file) {
        String name = file.getFileName().toString();
        return (name.endsWith(".html") || name.endsWith(".js")) && !name.contains(" ");
    }

    private static String read(String page) throws Exception {
        return Files.readString(STATIC.resolve(page));
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private JsonNode register(String name, String email) throws Exception {
        Reply reply = post("/auth/register", Map.of("name", name, "email", email, "password", PASSWORD), null);
        assertEquals(200, reply.status(), reply.body());
        return json.readTree(reply.body());
    }

    private long routine(String token, String title) throws Exception {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("title", title);
        fields.put("description", "Ui polish check");
        fields.put("category", "Morning");
        fields.put("durationMinutes", "30");
        fields.put("isPublic", "true");

        StringBuilder body = new StringBuilder();
        fields.forEach((key, value) -> body.append("--").append(BOUNDARY).append("\r\n")
                .append("Content-Disposition: form-data; name=\"").append(key).append("\"\r\n\r\n")
                .append(value).append("\r\n"));
        body.append("--").append(BOUNDARY).append("--\r\n");

        HttpRequest request = HttpRequest.newBuilder(uri("/routines"))
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return json.readTree(response.body()).path("id").asLong();
    }

    private Reply get(String path) throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(uri(path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(), response.body());
    }

    private Reply post(String path, Map<String, ?> payload, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .POST(payload == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload)));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(), response.body());
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private static String readQuietly(Path path) {
        try {
            return Files.readString(path);
        } catch (Exception exception) {
            return "(no output)";
        }
    }

    private static boolean nodeAvailable() {
        try {
            return new ProcessBuilder("node", "--version").redirectErrorStream(true).start().waitFor() == 0;
        } catch (Exception exception) {
            return false;
        }
    }

    private static String findChrome() {
        List<String> candidates = new ArrayList<>();
        if (System.getenv("CHROME_BIN") != null) {
            candidates.add(System.getenv("CHROME_BIN"));
        }
        candidates.add("C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe");
        candidates.add("C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe");
        candidates.add("/usr/bin/google-chrome");
        candidates.add("/usr/bin/chromium");
        candidates.add("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome");
        return candidates.stream().filter(path -> new File(path).isFile()).findFirst().orElse(null);
    }
}
