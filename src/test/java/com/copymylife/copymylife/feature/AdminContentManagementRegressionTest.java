package com.copymylife.copymylife.feature;

import com.copymylife.copymylife.service.EmailService;
import org.junit.jupiter.api.AfterAll;
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
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * US-25: an administrator edits, hides and removes public profiles, posts and routine data,
 * manages the categories routines are filed under and reviews reports. What an administrator
 * changes is stored and the public pages show it; hidden content is gone from every public
 * view but stays with its owner, who cannot publish it again. Anyone who is not an
 * administrator is refused every admin action and nothing changes.
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
                "MAILJET_SENDER_EMAIL=test@invalid.test",
                "app.upload-dir=target/test-uploads"
        }
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AdminContentManagementRegressionTest {

    private static final String BOUNDARY = "AdminContentBoundary";
    private static final String TRICKY = "<img src=x onerror=\"window.__xss=1\">";
    private static final List<String> DEFAULT_CATEGORIES =
            List.of("Morning", "Fitness", "Study", "Wellness", "Productivity", "Creators");

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private EmailService emailService;

    private record Account(long id, String token, String email) {
    }

    private record Reply(int status, String body) {
    }

    private JsonNode browser;
    private Account admin;
    private Account owner;
    private Account other;
    private Account otherAdmin;

    // ---- AC1: routines ----

    @Test
    void anAdministratorEditsARoutineAndThePublicViewShowsIt() throws Exception {
        long routine = routine(owner, "Owner's sunrise", 30, true, "Morning");

        JsonNode edited = admin("PUT", "/admin-api/routines/" + routine,
                Map.of("title", "Edited by admin", "description", "Tidied up", "category", "Fitness"));

        assertEquals("Edited by admin", edited.path("title").asString(), edited.toString());
        assertFalse(edited.path("hidden").asBoolean(), edited.toString());
        JsonNode shown = json.readTree(get("/routines/" + routine, null).body());
        assertEquals("Edited by admin", shown.path("title").asString());
        assertEquals("Tidied up", shown.path("description").asString());
        assertEquals("Fitness", shown.path("category").asString());
        assertEquals(30, shown.path("durationMinutes").asInt(), "the length is the owner's");
        assertTrue(ids(json.readTree(get("/routines/public", null).body())).contains(routine), "still listed");
        assertTrue(get("/routines/search?q=edited+by+admin", null).body().contains(String.valueOf(routine)));
        assertEquals("Edited by admin", jdbc.queryForObject("select title from routines where id = ?", String.class, routine));
    }

    @Test
    void invalidRoutineEditsAreRefusedAndChangeNothing() throws Exception {
        long routine = routine(owner, "Kept as it is", 30, true, "Morning");
        String before = rows("routines", routine);

        assertRefused(send("PUT", "/admin-api/routines/" + routine, admin, edit(" ", "x", "Morning")), 400, "title");
        assertRefused(send("PUT", "/admin-api/routines/" + routine, admin, edit("t".repeat(101), "x", "Morning")), 400, "100");
        assertRefused(send("PUT", "/admin-api/routines/" + routine, admin, edit("Fine", "d".repeat(501), "Morning")), 400, "500");
        assertRefused(send("PUT", "/admin-api/routines/" + routine, admin, edit("Fine", "x", "No such category")), 400, "category");
        assertRefused(send("PUT", "/admin-api/routines/" + routine, admin, null), 400, "title");
        assertEquals(404, send("PUT", "/admin-api/routines/987654321", admin, edit("Fine", "x", "Morning")).status());
        assertEquals(before, rows("routines", routine));
    }

    @Test
    void aRoutineKeepsACategoryTheListNoLongerOffers() throws Exception {
        long routine = routine(owner, "Spooky one", 30, true, "Halloween");
        JsonNode edited = admin("PUT", "/admin-api/routines/" + routine, edit("Spooky, edited", "", "Halloween"));
        assertEquals("Halloween", edited.path("category").asString(), edited.toString());
    }

    @Test
    void aHiddenRoutineLeavesEveryPublicViewButStaysWithItsOwner() throws Exception {
        long routine = routine(owner, "Hide me routine", 20, true, "Morning");
        habit(owner, routine, "Hidden step", 20);
        long step = links(routine).get(0);
        long statsBefore = stats("routines");

        JsonNode hidden = admin("PUT", "/admin-api/routines/" + routine + "/hide", null);
        assertTrue(hidden.path("hidden").asBoolean(), hidden.toString());

        assertEquals(404, get("/routines/" + routine, null).status(), "a visitor");
        assertEquals(404, get("/routines/" + routine, other).status(), "another user");
        assertEquals(404, get("/routines/" + routine + "/routine-habits", other).status());
        assertFalse(ids(json.readTree(get("/routines/public", null).body())).contains(routine), "Explore");
        assertFalse(get("/routines/search?q=hide+me", null).body().contains(String.valueOf(routine)), "search");
        assertFalse(get("/top-routines", null).body().contains("Hide me routine"), "Top Routines");
        assertFalse(ids(json.readTree(get("/users/" + owner.id() + "/routines/public", null).body())).contains(routine), "profile");
        assertEquals(statsBefore - 1, stats("routines"), "site stats");
        assertEquals(404, send("POST", "/routines/" + routine + "/copy", other, null).status(), "copy");
        assertEquals(404, send("POST", "/liked-routines/" + routine, other, null).status(), "like");
        assertEquals(404, send("POST", "/saved-routines/" + routine, other, null).status(), "save");
        assertEquals(404, send("POST", "/routines/" + routine + "/routine-habits/" + step + "/copy", other,
                Map.of("targetRoutineId", routine(other, "Target", 60, false, "Morning"))).status(), "copy habit");

        JsonNode mine = json.readTree(get("/routines/" + routine, owner).body());
        assertTrue(mine.path("hiddenByAdmin").asBoolean(), mine.toString());
        assertTrue(ids(json.readTree(get("/routines", owner).body())).contains(routine), "My Routines");

        // Saving it as public again does not undo the administrator's decision.
        assertEquals(200, multipart("PUT", "/routines/" + routine, owner, routineFields("Hide me routine", 20, true, "Morning")).status());
        assertEquals(404, get("/routines/" + routine, null).status());

        assertTrue(admin("PUT", "/admin-api/routines/" + routine + "/hide", null).path("hidden").asBoolean(), "twice is fine");
        assertFalse(admin("PUT", "/admin-api/routines/" + routine + "/unhide", null).path("hidden").asBoolean());
        assertEquals(200, get("/routines/" + routine, null).status());
        assertFalse(json.readTree(get("/routines/" + routine, owner).body()).path("hiddenByAdmin").asBoolean());
        assertTrue(ids(json.readTree(get("/routines/public", null).body())).contains(routine));
        assertEquals(statsBefore, stats("routines"));
    }

    @Test
    void anAdministratorStillRemovesARoutine() throws Exception {
        long routine = routine(owner, "Remove me", 20, true, "Morning");
        assertEquals(200, send("DELETE", "/admin-api/routines/" + routine, admin, null).status());
        assertEquals(404, get("/routines/" + routine, null).status());
        assertEquals(404, send("PUT", "/admin-api/routines/" + routine + "/hide", admin, null).status());
    }

    // ---- AC1: posts ----

    @Test
    void anAdministratorSeesEveryPostWithItsAuthor() throws Exception {
        long open = post(owner, "Listed public post", true);
        long closed = post(owner, "Listed private post", false);
        JsonNode list = admin("GET", "/admin-api/posts", null);
        JsonNode found = find(list, open);
        assertEquals("Listed public post", found.path("text").asString(), found.toString());
        assertTrue(found.path("publicPost").asBoolean());
        assertFalse(found.path("hidden").asBoolean());
        assertEquals(owner.id(), found.path("authorId").asLong());
        assertEquals(owner.email(), found.path("authorEmail").asString());
        assertFalse(find(list, closed).path("publicPost").asBoolean());
    }

    @Test
    void anAdministratorEditsAPostAndReadersSeeTheNewText() throws Exception {
        long id = post(owner, "Original caption", true);
        JsonNode edited = admin("PUT", "/admin-api/posts/" + id, Map.of("text", "Caption fixed by admin"));
        assertEquals("Caption fixed by admin", edited.path("text").asString());
        assertEquals("Caption fixed by admin", json.readTree(get("/posts/" + id, other).body()).path("text").asString());
        assertTrue(get("/users/" + owner.id() + "/posts", other).body().contains("Caption fixed by admin"));

        String before = rows("posts", id);
        assertRefused(send("PUT", "/admin-api/posts/" + id, admin, Map.of("text", "p".repeat(2001))), 400, "2000");
        assertRefused(send("PUT", "/admin-api/posts/" + id, admin, Map.of("text", "   ")), 400, "Write something");
        assertEquals(404, send("PUT", "/admin-api/posts/987654321", admin, Map.of("text", "x")).status());
        assertEquals(before, rows("posts", id));
    }

    @Test
    void aHiddenPostLeavesThePublicViewButStaysWithItsOwner() throws Exception {
        long id = post(owner, "Post to hide", true);
        assertTrue(admin("PUT", "/admin-api/posts/" + id + "/hide", null).path("hidden").asBoolean());

        assertEquals(404, get("/posts/" + id, other).status());
        assertFalse(get("/users/" + owner.id() + "/posts", other).body().contains("Post to hide"));
        assertEquals(404, send("POST", "/posts/" + id + "/like", other, null).status());
        assertEquals(404, get("/posts/" + id + "/comments", other).status());
        JsonNode mine = json.readTree(get("/posts/" + id, owner).body());
        assertTrue(mine.path("hiddenByAdmin").asBoolean(), mine.toString());
        assertTrue(get("/posts", owner).body().contains("Post to hide"));

        // Making it public again does not undo the administrator's decision.
        assertEquals(200, multipart("PUT", "/posts/" + id, owner, Map.of("text", "Post to hide", "isPublic", "true")).status());
        assertEquals(404, get("/posts/" + id, other).status());

        assertFalse(admin("PUT", "/admin-api/posts/" + id + "/unhide", null).path("hidden").asBoolean());
        assertEquals(200, get("/posts/" + id, other).status());
    }

    @Test
    void anAdministratorRemovesAPostWithItsLikesAndComments() throws Exception {
        long id = post(owner, "Post to remove", true);
        assertEquals(200, send("POST", "/posts/" + id + "/like", other, null).status());
        assertEquals(200, send("POST", "/posts/" + id + "/comments", other, Map.of("text", "Nice")).status());

        assertEquals(204, send("DELETE", "/admin-api/posts/" + id, admin, null).status());
        assertEquals(404, get("/posts/" + id, owner).status());
        assertEquals(404, get("/posts/" + id, other).status());
        assertEquals(0L, (long) jdbc.queryForObject("select count(*) from post_likes where post_id = ?", Long.class, id));
        assertEquals(0L, (long) jdbc.queryForObject("select count(*) from post_comments where post_id = ?", Long.class, id));
        assertEquals(404, send("DELETE", "/admin-api/posts/" + id, admin, null).status());
    }

    // ---- AC1: public profiles ----

    @Test
    void anAdministratorEditsAProfileAndThePublicProfileShowsIt() throws Exception {
        Account person = register("Profile person");
        JsonNode edited = admin("PUT", "/admin-api/profiles/" + person.id(), Map.of(
                "name", "Renamed person", "username", "renamed_" + person.id(),
                "profession", "Gardener", "bio", "Edited bio", "interests", "Plants", "goals", "Grow"));
        assertEquals("Renamed person", edited.path("name").asString(), edited.toString());

        JsonNode shown = json.readTree(get("/users/" + person.id() + "/profile", null).body());
        assertEquals("Renamed person", shown.path("name").asString(), shown.toString());
        assertEquals("Gardener", shown.path("profession").asString(), shown.toString());
        assertEquals("Edited bio", shown.path("bio").asString(), shown.toString());
        assertTrue(get("/users/search?query=renamed+person", null).body().contains("\"id\":" + person.id()));
    }

    @Test
    void invalidProfileEditsAreRefusedAndChangeNothing() throws Exception {
        Account person = register("Strict person");
        String before = rows("users", person.id());
        String taken = jdbc.queryForObject("select username from users where id = ?", String.class, owner.id());

        assertRefused(send("PUT", "/admin-api/profiles/" + person.id(), admin, Map.of("name", "A")), 400, "at least 2");
        assertRefused(send("PUT", "/admin-api/profiles/" + person.id(), admin, Map.of("name", "Fine", "username", "no spaces")), 400, "username");
        assertRefused(send("PUT", "/admin-api/profiles/" + person.id(), admin, Map.of("name", "Fine", "bio", "b".repeat(501))), 400, "500");
        if (taken != null) {
            assertRefused(send("PUT", "/admin-api/profiles/" + person.id(), admin, Map.of("name", "Fine", "username", taken)), 409, "taken");
        }
        assertEquals(404, send("PUT", "/admin-api/profiles/987654321", admin, Map.of("name", "Fine")).status());
        assertEquals(before, rows("users", person.id()));
    }

    @Test
    void aHiddenProfileLeavesThePublicViewButItsOwnerCarriesOn() throws Exception {
        Account person = register("Hidden person");
        long theirs = routine(person, "Hidden person's routine", 20, true, "Morning");
        post(person, "Hidden person's post", true);

        JsonNode hidden = admin("PUT", "/admin-api/profiles/" + person.id() + "/hide", null);
        assertTrue(hidden.path("profileHidden").asBoolean(), hidden.toString());

        assertEquals(404, get("/users/" + person.id() + "/profile", null).status());
        assertEquals(404, get("/users/" + person.id() + "/profile", other).status());
        assertFalse(get("/users/search?query=hidden+person", null).body().contains("\"id\":" + person.id()));
        assertEquals(404, get("/users/" + person.id() + "/routines/public", null).status());
        assertEquals(404, get("/users/" + person.id() + "/posts", other).status());
        assertEquals(200, get("/users/me", person).status(), "the owner still signs in");
        assertTrue(json.readTree(get("/users/me", person).body()).path("hiddenByAdmin").asBoolean());
        // A routine is moderated on its own; hiding the profile leaves it where it is.
        assertEquals(200, get("/routines/" + theirs, null).status());

        assertFalse(admin("PUT", "/admin-api/profiles/" + person.id() + "/unhide", null).path("profileHidden").asBoolean());
        assertEquals(200, get("/users/" + person.id() + "/profile", null).status());
        assertFalse(json.readTree(get("/users/me", person).body()).path("hiddenByAdmin").asBoolean());
    }

    @Test
    void removingAProfileClearsWhatItShowsAndKeepsTheAccount() throws Exception {
        Account person = register("Wordy person");
        admin("PUT", "/admin-api/profiles/" + person.id(), Map.of("name", "Wordy person",
                "profession", "Spammer", "bio", "Buy now", "interests", "Ads", "goals", "Clicks"));
        jdbc.update("update users set avatar_url = 'https://i.ibb.co/x/a.png' where id = ?", person.id());

        JsonNode cleared = admin("DELETE", "/admin-api/profiles/" + person.id() + "/details", null);
        assertNull(textOrNull(cleared.path("bio")), cleared.toString());

        JsonNode shown = json.readTree(get("/users/" + person.id() + "/profile", null).body());
        assertEquals("Wordy person", shown.path("name").asString(), shown.toString());
        for (String field : List.of("profession", "bio", "avatarUrl")) {
            assertNull(textOrNull(shown.path(field)), field + " in " + shown);
        }
        Map<String, Object> row = jdbc.queryForMap(
                "select profession, bio, interests, goals, avatar_url, banned from users where id = ?", person.id());
        assertTrue(row.values().stream().limit(5).allMatch(value -> value == null), row.toString());
        assertEquals(200, get("/users/me", person).status(), "the account stays");
    }

    @Test
    void administratorAccountsAreNotChangedThroughProfiles() throws Exception {
        String before = rows("users", otherAdmin.id());
        assertEquals(403, send("PUT", "/admin-api/profiles/" + otherAdmin.id(), admin, Map.of("name", "Changed")).status());
        assertEquals(403, send("PUT", "/admin-api/profiles/" + otherAdmin.id() + "/hide", admin, null).status());
        assertEquals(403, send("DELETE", "/admin-api/profiles/" + otherAdmin.id() + "/details", admin, null).status());
        assertEquals(404, send("PUT", "/admin-api/profiles/987654321/hide", admin, null).status());
        assertEquals(before, rows("users", otherAdmin.id()));
    }

    // ---- AC1: categories ----

    @Test
    void everyoneReadsTheCategoriesInTheirOrder() throws Exception {
        Reply reply = get("/categories", null);
        assertEquals(200, reply.status(), reply.body());
        List<String> names = texts(json.readTree(reply.body()), "name");
        assertEquals(DEFAULT_CATEGORIES, names.subList(0, DEFAULT_CATEGORIES.size()), names.toString());
    }

    @Test
    void anAdministratorCreatesACategoryThatFormsAndExploreOffer() throws Exception {
        String name = "Mindful " + suffix();
        JsonNode created = admin("POST", "/admin-api/categories", Map.of("name", "  " + name + "  "));
        assertEquals(name, created.path("name").asString(), created.toString());
        List<String> names = texts(json.readTree(get("/categories", null).body()), "name");
        assertEquals(name, names.get(names.size() - 1), "added at the end: " + names);

        long routine = routine(owner, "Filed under it", 20, true, name);
        assertEquals(name, json.readTree(get("/routines/" + routine, null).body()).path("category").asString());
        assertEquals(1, find(admin("GET", "/admin-api/categories", null), created.path("id").asLong()).path("routineCount").asInt());
        assertTrue(get("/routines/search?category=" + name.toLowerCase().replace(" ", "+"), null).body()
                .contains(String.valueOf(routine)));
    }

    @Test
    void invalidCategoriesAreRefused() throws Exception {
        long before = (long) jdbc.queryForObject("select count(*) from categories", Long.class);
        assertRefused(send("POST", "/admin-api/categories", admin, Map.of("name", "   ")), 400, "name");
        assertRefused(send("POST", "/admin-api/categories", admin, null), 400, "name");
        assertRefused(send("POST", "/admin-api/categories", admin, Map.of("name", "c".repeat(41))), 400, "40");
        assertRefused(send("POST", "/admin-api/categories", admin, Map.of("name", "All")), 400, "All");
        assertRefused(send("POST", "/admin-api/categories", admin, Map.of("name", "fitness")), 409, "already");
        assertEquals(before, (long) jdbc.queryForObject("select count(*) from categories", Long.class));
    }

    @Test
    void renamingACategoryCarriesItsRoutines() throws Exception {
        String name = "Focus " + suffix();
        long id = admin("POST", "/admin-api/categories", Map.of("name", name)).path("id").asLong();
        long routine = routine(owner, "Focused routine", 20, true, name);

        JsonNode renamed = admin("PUT", "/admin-api/categories/" + id, Map.of("name", "Deep " + name));
        assertEquals("Deep " + name, renamed.path("name").asString());
        assertEquals("Deep " + name, json.readTree(get("/routines/" + routine, null).body()).path("category").asString());
        assertFalse(texts(json.readTree(get("/categories", null).body()), "name").contains(name));

        assertRefused(send("PUT", "/admin-api/categories/" + id, admin, Map.of("name", "Study")), 409, "already");
        assertRefused(send("PUT", "/admin-api/categories/" + id, admin, Map.of("name", "")), 400, "name");
        assertEquals(404, send("PUT", "/admin-api/categories/987654321", admin, Map.of("name", "Nope")).status());
        // The same name in another case is a rename of its own spelling.
        assertEquals("DEEP " + name.toUpperCase(), admin("PUT", "/admin-api/categories/" + id,
                Map.of("name", "DEEP " + name.toUpperCase())).path("name").asString());
    }

    @Test
    void aCategoryInUseIsNotRemovedAndAnUnusedOneIs() throws Exception {
        String used = "Used " + suffix();
        long usedId = admin("POST", "/admin-api/categories", Map.of("name", used)).path("id").asLong();
        routine(owner, "Still filed", 20, false, used);
        assertRefused(send("DELETE", "/admin-api/categories/" + usedId, admin, null), 409, "1 routine");

        String unused = "Unused " + suffix();
        long unusedId = admin("POST", "/admin-api/categories", Map.of("name", unused)).path("id").asLong();
        assertEquals(204, send("DELETE", "/admin-api/categories/" + unusedId, admin, null).status());
        assertFalse(texts(json.readTree(get("/categories", null).body()), "name").contains(unused));
        assertEquals(404, send("DELETE", "/admin-api/categories/" + unusedId, admin, null).status());
    }

    @Test
    void reportsAreStillReviewed() throws Exception {
        assertEquals(200, send("GET", "/admin-api/reports", admin, null).status());
        assertEquals(200, send("GET", "/admin-api/comments", admin, null).status());
        assertEquals(200, send("GET", "/admin-api/users", admin, null).status());
    }

    // ---- AC2: nobody else ----

    @Test
    void aNormalUserIsRefusedEveryAdminActionAndNothingChanges() throws Exception {
        long routine = routine(owner, "Not for users", 20, true, "Morning");
        long postId = post(owner, "Not for users either", true);
        long category = admin("POST", "/admin-api/categories", Map.of("name", "Guarded " + suffix())).path("id").asLong();
        String before = everything();

        for (Object[] call : adminCalls(routine, postId, category)) {
            Reply reply = send((String) call[0], (String) call[1], other, castBody(call[2]));
            assertEquals(403, reply.status(), call[0] + " " + call[1] + " " + reply.body());
        }
        assertEquals(before, everything(), "content remains unchanged");
    }

    @Test
    void aVisitorABadTokenAndAFormerAdministratorAreRefused() throws Exception {
        long routine = routine(owner, "Not for visitors", 20, true, "Morning");
        long postId = post(owner, "Not for visitors either", true);
        long category = admin("POST", "/admin-api/categories", Map.of("name", "Fenced " + suffix())).path("id").asLong();
        Account former = register("Former admin");
        jdbc.update("update users set role = 'ADMIN' where id = ?", former.id());
        assertEquals(200, send("GET", "/admin-api/posts", former, null).status(), "an administrator at first");
        jdbc.update("update users set role = 'USER' where id = ?", former.id());
        Account bannedAdmin = register("Banned admin");
        jdbc.update("update users set role = 'ADMIN', banned = true where id = ?", bannedAdmin.id());
        String before = everything();

        for (Account who : Arrays.asList(null, new Account(0, "not-a-token", ""), former, bannedAdmin)) {
            for (Object[] call : adminCalls(routine, postId, category)) {
                Reply reply = send((String) call[0], (String) call[1], who, castBody(call[2]));
                assertTrue(reply.status() == 401 || reply.status() == 403, call[0] + " " + call[1] + " " + reply.status());
            }
        }
        assertEquals(before, everything());
    }

    @Test
    void anOwnerCannotUndoAModerationThroughTheirOwnPages() throws Exception {
        long routine = routine(owner, "Owner tries", 20, true, "Morning");
        admin("PUT", "/admin-api/routines/" + routine + "/hide", null);
        assertEquals(403, send("PUT", "/admin-api/routines/" + routine + "/unhide", owner, null).status());
        assertEquals(200, multipart("PUT", "/routines/" + routine, owner, routineFields("Owner tries again", 20, true, "Morning")).status());
        assertEquals(404, get("/routines/" + routine, other).status());
        assertEquals("Owner tries again", json.readTree(get("/routines/" + routine, owner).body()).path("title").asString(),
                "the owner still edits it");
    }

    // ---- browser ----

    @Test
    void browserLetsTheAdministratorEditHideAndRemoveRoutines() {
        JsonNode view = page("routines");
        assertTrue(view.path("editSaved").asBoolean(), view.toString());
        assertEquals("Edited in the browser", view.path("publicTitle").asString(), view.toString());
        assertEquals("Hidden", view.path("statusAfterHide").asString(), view.toString());
        assertEquals(404, view.path("publicAfterHide").asInt(), view.toString());
        assertEquals(200, view.path("publicAfterUnhide").asInt(), view.toString());
        assertTrue(view.path("categoryOptions").toString().contains("Fitness"), view.toString());
    }

    @Test
    void browserLetsTheAdministratorManagePosts() {
        JsonNode view = page("posts");
        assertTrue(view.path("listed").asBoolean(), view.toString());
        assertEquals("Fixed in the browser", view.path("textAfterEdit").asString(), view.toString());
        assertEquals(404, view.path("readerAfterHide").asInt(), view.toString());
        assertEquals(404, view.path("ownerAfterRemove").asInt(), view.toString());
        assertFalse(view.path("xss").asBoolean(), view.toString());
    }

    @Test
    void browserLetsTheAdministratorManageProfiles() {
        JsonNode view = page("profile");
        assertEquals("Edited person", view.path("publicName").asString(), view.toString());
        assertEquals(404, view.path("publicAfterHide").asInt(), view.toString());
        assertEquals(200, view.path("publicAfterUnhide").asInt(), view.toString());
        assertEquals("", view.path("bioAfterRemove").asString(), view.toString());
    }

    @Test
    void browserLetsTheAdministratorManageCategories() {
        JsonNode view = page("categories");
        assertTrue(view.path("created").asBoolean(), view.toString());
        assertTrue(view.path("renamed").asBoolean(), view.toString());
        assertTrue(view.path("inUseMessage").asString().contains("routine"), view.toString());
        assertTrue(view.path("removed").asBoolean(), view.toString());
    }

    @Test
    void browserPublicPagesFollowTheCategoriesAndTheModeration() {
        JsonNode view = page("public");
        assertTrue(view.path("exploreHasNewChip").asBoolean(), view.toString());
        assertTrue(view.path("newChipShowsItsRoutine").asBoolean(), view.toString());
        assertTrue(view.path("createFormOffersNew").asBoolean(), view.toString());
        assertTrue(view.path("ownerBadge").asString().contains("Hidden by an administrator"), view.toString());
        assertTrue(view.path("ownerPostBadge").asString().contains("Hidden by an administrator"), view.toString());
    }

    @Test
    void browserKeepsTheLayoutInEveryDesignAndWidth() {
        JsonNode layouts = page("layouts");
        assertEquals(30, layouts.size(), layouts.toString());
        List<String> problems = new ArrayList<>();
        layouts.properties().forEach(entry -> {
            JsonNode view = entry.getValue();
            if (view.path("overflow").asInt() > 0) {
                problems.add(entry.getKey() + " scrolls sideways by " + view.path("overflow").asInt());
            }
            if (!view.path("inside").asBoolean()) {
                problems.add(entry.getKey() + " has parts outside the screen: " + view.path("outside").asString());
            }
            if (view.path("minContrast").asDouble() < 4.5) {
                problems.add(entry.getKey() + " has faint text " + view.path("faintest").asString() + " " + view.path("minContrast").asDouble());
            }
        });
        assertEquals(List.of(), problems, layouts.toString());
    }

    @Test
    void browserNeverFailsOrInterrupts() {
        assertEquals(0, page("errors").size(), browser.toString());
        assertEquals(0, page("dialogs").size(), browser.toString());
    }

    // ---- data ----

    @BeforeAll
    void createContent() throws Exception {
        assertTrue(jdbc.queryForObject("select current_database()", String.class).endsWith("_test"),
                "Refusing to run outside a *_test database");
        admin = register("Content admin");
        jdbc.update("update users set role = 'ADMIN' where id = ?", admin.id());
        otherAdmin = register("Second admin");
        jdbc.update("update users set role = 'ADMIN' where id = ?", otherAdmin.id());
        owner = register("Content owner");
        other = register("Some reader");

        String chrome = findChrome();
        if (chrome == null || !nodeAvailable()) {
            return;
        }

        Account person = register("Browser person");
        String category = "Browser cat " + suffix();
        Map<String, Object> fixtures = new LinkedHashMap<>();
        fixtures.put("base", "http://127.0.0.1:" + port);
        fixtures.put("adminToken", admin.token());
        fixtures.put("ownerToken", owner.token());
        fixtures.put("readerToken", other.token());
        fixtures.put("routine", routine(owner, "Browser routine", 20, true, "Morning"));
        fixtures.put("hiddenRoutine", routine(owner, "Browser hidden routine", 20, true, "Morning"));
        fixtures.put("post", post(owner, "Browser post " + TRICKY, true));
        fixtures.put("hiddenPost", post(owner, "Browser hidden post", true));
        fixtures.put("person", person.id());
        fixtures.put("category", category);
        // A category with a routine in it; none before US-25, when the browser part simply fails.
        String busyName = "Busy " + suffix();
        Reply busy = send("POST", "/admin-api/categories", admin, Map.of("name", busyName));
        fixtures.put("busyCategory", busy.status() == 200 ? json.readTree(busy.body()).path("id").asLong() : -1);
        routine(owner, "Keeps a category busy", 20, false, busyName);
        fixtures.put("chipCategory", "Chip " + suffix());

        Path dir = Files.createTempDirectory("admin-content-browser");
        Files.writeString(dir.resolve("fixtures.json"), json.writeValueAsString(fixtures));
        Process process = new ProcessBuilder(
                "node", "src/test/e2e/admin-content-check.mjs", chrome,
                dir.resolve("fixtures.json").toString(), dir.resolve("results.json").toString()
        ).redirectErrorStream(true).redirectOutput(dir.resolve("node.log").toFile()).start();

        if (!process.waitFor(10, TimeUnit.MINUTES)) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            throw new AssertionError("Browser check timed out: " + readQuietly(dir.resolve("node.log")));
        }
        assertEquals(0, process.exitValue(), () -> readQuietly(dir.resolve("node.log")));
        browser = json.readTree(Files.readString(dir.resolve("results.json")));
    }

    // The categories added here would show as extra chips in the Explore checks of later classes.
    @AfterAll
    void removeAddedCategories() {
        jdbc.update("delete from categories where name_key not in ('morning', 'fitness', 'study', 'wellness', 'productivity', 'creators')");
    }

    private JsonNode page(String name) {
        Assumptions.assumeTrue(browser != null, "Chrome or node not found");
        return browser.path(name);
    }

    // ---- helpers ----

    // Every admin action, the earlier ones and those of US-25, as [method, path, body].
    private List<Object[]> adminCalls(long routine, long postId, long category) {
        Map<String, Object> routineEdit = edit("Taken over", "x", "Morning");
        return List.of(
                new Object[]{"GET", "/admin-api/users", null},
                new Object[]{"PUT", "/admin-api/users/" + owner.id() + "/ban", null},
                new Object[]{"GET", "/admin-api/routines", null},
                new Object[]{"PUT", "/admin-api/routines/" + routine, routineEdit},
                new Object[]{"PUT", "/admin-api/routines/" + routine + "/hide", null},
                new Object[]{"PUT", "/admin-api/routines/" + routine + "/unhide", null},
                new Object[]{"DELETE", "/admin-api/routines/" + routine, null},
                new Object[]{"GET", "/admin-api/posts", null},
                new Object[]{"PUT", "/admin-api/posts/" + postId, Map.of("text", "Taken over")},
                new Object[]{"PUT", "/admin-api/posts/" + postId + "/hide", null},
                new Object[]{"DELETE", "/admin-api/posts/" + postId, null},
                new Object[]{"GET", "/admin-api/profiles/" + owner.id(), null},
                new Object[]{"PUT", "/admin-api/profiles/" + owner.id(), Map.of("name", "Taken over")},
                new Object[]{"PUT", "/admin-api/profiles/" + owner.id() + "/hide", null},
                new Object[]{"DELETE", "/admin-api/profiles/" + owner.id() + "/details", null},
                new Object[]{"GET", "/admin-api/categories", null},
                new Object[]{"POST", "/admin-api/categories", Map.of("name", "Sneaky")},
                new Object[]{"PUT", "/admin-api/categories/" + category, Map.of("name", "Sneaky")},
                new Object[]{"DELETE", "/admin-api/categories/" + category, null},
                new Object[]{"GET", "/admin-api/reports", null},
                new Object[]{"GET", "/admin-api/comments", null},
                new Object[]{"GET", "/admin-api/habits", null}
        );
    }

    @SuppressWarnings("unchecked")
    private static Map<String, ?> castBody(Object body) {
        return (Map<String, ?>) body;
    }

    // Everything an admin action could change, to compare before and after.
    private String everything() {
        return jdbc.queryForList("select * from routines order by id").toString()
                + jdbc.queryForList("select * from posts order by id")
                + jdbc.queryForList("select id, name, username, profession, bio, interests, goals, avatar_url, role, banned, profile_hidden_at from users order by id")
                + jdbc.queryForList("select * from categories order by id")
                + jdbc.queryForList("select * from routine_habits order by id");
    }

    private String rows(String table, long id) {
        return jdbc.queryForList("select * from " + table + " where id = ?", id).toString();
    }

    private static Map<String, Object> edit(String title, String description, String category) {
        Map<String, Object> body = new HashMap<>();
        body.put("title", title);
        body.put("description", description);
        body.put("category", category);
        return body;
    }

    private JsonNode admin(String method, String path, Map<String, ?> body) throws Exception {
        Reply reply = send(method, path, admin, body);
        assertTrue(reply.status() == 200, method + " " + path + " " + reply.status() + " " + reply.body());
        return json.readTree(reply.body());
    }

    private void assertRefused(Reply reply, int status, String words) throws Exception {
        assertEquals(status, reply.status(), reply.body());
        String message = json.readTree(reply.body()).path("message").asString();
        assertTrue(message.toLowerCase().contains(words.toLowerCase()), words + " in " + reply.body());
    }

    private long stats(String key) throws Exception {
        return json.readTree(get("/site-stats", null).body()).path(key).asLong();
    }

    private static JsonNode find(JsonNode list, long id) {
        for (JsonNode item : list) {
            if (item.path("id").asLong() == id) {
                return item;
            }
        }
        throw new AssertionError(id + " not in " + list);
    }

    private static String textOrNull(JsonNode node) {
        return node.isNull() || node.isMissingNode() || node.asString().isEmpty() ? null : node.asString();
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 6);
    }

    private List<Long> links(long routine) {
        return jdbc.queryForList("select id from routine_habits where routine_id = ? and removed_at is null order by position",
                Long.class, routine);
    }

    private static List<Long> ids(JsonNode list) {
        List<Long> ids = new ArrayList<>();
        list.forEach(item -> ids.add(item.path("id").asLong()));
        return ids;
    }

    private static List<String> texts(JsonNode array, String field) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.path(field).asString()));
        return values;
    }

    private Account register(String name) throws Exception {
        String email = "admin25_" + UUID.randomUUID().toString().substring(0, 12) + "@audit.test";
        Reply reply = send("POST", "/auth/register", null, Map.of("name", name, "email", email, "password", "Audit#12345"));
        assertEquals(200, reply.status(), reply.body());
        JsonNode node = json.readTree(reply.body());
        return new Account(node.path("user").path("id").asLong(), node.path("token").asString(), email);
    }

    private static Map<String, String> routineFields(String title, int minutes, boolean isPublic, String category) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("title", title);
        fields.put("description", "Admin content check");
        fields.put("category", category);
        fields.put("durationMinutes", String.valueOf(minutes));
        fields.put("isPublic", String.valueOf(isPublic));
        return fields;
    }

    private long routine(Account who, String title, int minutes, boolean isPublic, String category) throws Exception {
        Reply reply = multipart("POST", "/routines", who, routineFields(title, minutes, isPublic, category));
        assertEquals(200, reply.status(), reply.body());
        return json.readTree(reply.body()).path("id").asLong();
    }

    private long post(Account who, String text, boolean isPublic) throws Exception {
        Reply reply = multipart("POST", "/posts", who, Map.of("text", text, "isPublic", String.valueOf(isPublic)));
        assertEquals(200, reply.status(), reply.body());
        return json.readTree(reply.body()).path("id").asLong();
    }

    private void habit(Account who, long routine, String title, int minutes) throws Exception {
        Reply reply = send("POST", "/routines/" + routine + "/habits", who,
                Map.of("title", title, "description", title, "durationMinutes", minutes));
        assertEquals(200, reply.status(), reply.body());
    }

    private Reply multipart(String method, String path, Account who, Map<String, String> fields) throws Exception {
        StringBuilder body = new StringBuilder();
        fields.forEach((key, value) -> body.append("--").append(BOUNDARY).append("\r\n")
                .append("Content-Disposition: form-data; name=\"").append(key).append("\"\r\n\r\n")
                .append(value).append("\r\n"));
        body.append("--").append(BOUNDARY).append("--\r\n");
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(uri(path))
                .header("Authorization", "Bearer " + who.token())
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .method(method, HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build(), HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(), response.body());
    }

    private Reply get(String path, Account who) throws Exception {
        return send("GET", path, who, null);
    }

    private Reply send(String method, String path, Account who, Map<String, ?> payload) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path))
                .method(method, payload == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload)));
        if (payload != null) {
            builder.header("Content-Type", "application/json");
        }
        if (who != null) {
            builder.header("Authorization", "Bearer " + who.token());
        }
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
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
