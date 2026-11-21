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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * US-05: a visitor or a signed-in user finds public routines and people by routine name,
 * person name or username, profession, category, goal or habit, and Explore says clearly
 * when nothing matches and lets the search be cleared and the filters reset.
 * Private, deleted and banned users' content is never found, in the search or in any public
 * list. A visitor finds people too and reads their public profile; profession and goals are
 * public profile fields, so they are searched for everyone. A habit is found by its title and
 * by its type, the category of the routine it was made in (habits have no type of their own).
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
class SearchAndFilterRegressionTest {

    private static final String PASSWORD = "Audit#12345";
    private static final String BOUNDARY = "SearchFilterBoundary";
    private static final String TRICKY = "<img src=x onerror=\"window.__xss=1\">";

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private EmailService emailService;

    private record Account(long id, String token) {
    }

    private record Reply(int status, String body) {
    }

    // One marker per run keeps this run's rows apart from earlier runs in the same database.
    private final String m = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    private final String title = "ttl" + m;
    private final String description = "dsc" + m;
    private final String personName = "Nmx" + m;
    private final String username = "usr" + m;
    private final String profession = "prf" + m;
    private final String goal = "gol" + m;
    private final String habit = "hbt" + m;

    private JsonNode browser;
    private Account owner;
    private Account viewer;
    private Account tricky;
    private long studyRoutine;
    private long fitnessRoutine;
    private long unicodeRoutine;
    private long trickyRoutine;
    private long privateRoutine;
    private long deletedRoutine;
    private long bannedRoutine;
    private long bannedUser;
    private long typedRoutine;
    private long kindRoutine;
    private long bannedTopRoutine;
    private long topControlRoutine;
    private final String kind = "knd" + m;
    private final String hush = "hsh" + m;
    private final String topCategory = "Topcat" + m;

    // ---- routines: every search signal ----

    @Test
    void aRoutineIsFoundByItsName() throws Exception {
        assertEquals(Set.of(studyRoutine, fitnessRoutine), mine(search(title, null, null)));
        assertTrue(search("Sunrise Ritual " + title, null, null).contains(studyRoutine));
    }

    @Test
    void aRoutineIsFoundByItsDescription() throws Exception {
        assertEquals(Set.of(studyRoutine), mine(search(description, null, null)));
    }

    @Test
    void aRoutineIsFoundByItsAuthorsNameAndUsername() throws Exception {
        Set<Long> owners = Set.of(studyRoutine, fitnessRoutine, unicodeRoutine);
        assertEquals(owners, mine(search(personName, null, null)));
        assertEquals(owners, mine(search(username, null, viewer)));
        assertEquals(owners, mine(search(username, null, null)),
                "the author's name and username are already shown to visitors on every routine");
    }

    @Test
    void aSignedInUserFindsRoutinesByTheAuthorsProfessionAndGoal() throws Exception {
        Set<Long> owners = Set.of(studyRoutine, fitnessRoutine, unicodeRoutine);
        assertEquals(owners, mine(search(profession, null, viewer)));
        assertEquals(owners, mine(search(goal, null, viewer)));
    }

    @Test
    void aVisitorFindsRoutinesByTheAuthorsPublicProfessionAndGoalToo() throws Exception {
        // Changed by the US-05 corrections: a visitor now reads the public profile, so its
        // profession and goals are searched for them as well.
        Set<Long> owners = Set.of(studyRoutine, fitnessRoutine, unicodeRoutine);
        assertEquals(owners, mine(search(profession, null, null)));
        assertEquals(owners, mine(search(goal, null, null)));
    }

    @Test
    void aRoutineIsFoundByTheHabitsInIt() throws Exception {
        assertEquals(Set.of(studyRoutine), mine(search(habit, null, null)));
        assertEquals(Set.of(studyRoutine), mine(search("cold shower " + habit, null, viewer)));
    }

    @Test
    void aRoutineIsFoundByTheTypeOfTheHabitsInIt() throws Exception {
        // A habit's type is the category of the routine it was made in; the study routine holds
        // a habit made in the kind routine, so the kind word finds both, and with the Study
        // category only the study routine.
        List<Long> ids = search(kind.toUpperCase(), null, null);
        assertTrue(ids.contains(typedRoutine) && ids.contains(kindRoutine), ids.toString());
        assertEquals(List.of(typedRoutine), search("  " + kind + " ", "study", viewer).stream()
                .filter(id -> id == typedRoutine || id == kindRoutine).toList());
        // The title still counts as before.
        assertTrue(search("kettlebell swing " + m, null, null).contains(typedRoutine));
    }

    @Test
    void aHabitTypeFromAPrivateRoutineIsNeverSearched() throws Exception {
        // The second habit in the study routine was made in a private routine; that category stays hidden.
        for (Account account : new Account[]{null, viewer}) {
            assertEquals(List.of(), search(hush, null, account), "a private routine's category is not a habit type");
        }
    }

    @Test
    void aRoutineIsFoundByCategoryAndTheCategoryNarrowsASearch() throws Exception {
        assertEquals(Set.of(studyRoutine), mine(search(title, "study", null)));
        assertEquals(Set.of(fitnessRoutine), mine(search(title, "Fitness", null)));
        assertEquals(Set.of(), mine(search(title, "halloween", null)));
        assertEquals(Set.of(studyRoutine, fitnessRoutine), mine(search(title, "all", null)));
        // The category on its own, without any text, lists that category.
        List<Long> study = search("", "study", null);
        assertTrue(study.contains(studyRoutine) && !study.contains(fitnessRoutine), study.toString());
        // The category name is also a search word.
        assertTrue(search("fitness", null, null).contains(fitnessRoutine));
    }

    @Test
    void theSearchIgnoresCaseAndSurroundingSpaces() throws Exception {
        assertEquals(Set.of(studyRoutine, fitnessRoutine), mine(search("   " + title.toUpperCase() + "  ", null, null)));
        assertEquals(Set.of(studyRoutine), mine(search("  SUNRISE   ritual " + title + " ", null, null)),
                "several spaces inside count as one");
    }

    @Test
    void kazakhAndRussianTextIsFoundInAnyCase() throws Exception {
        assertTrue(search("  ТАҢҒЫ ЖҮГІРУ " + m + " ", null, null).contains(unicodeRoutine));
        assertTrue(search("таңғы жүгіру " + m.toUpperCase(), null, null).contains(unicodeRoutine));
        assertTrue(search("УТРЕННЯЯ пробежка " + m, null, null).contains(unicodeRoutine));
        assertTrue(search("Өмір сүру ұлы " + m, null, null).contains(unicodeRoutine));
    }

    @Test
    void aRoutineThatMatchesInSeveralWaysIsListedOnce() throws Exception {
        // The study routine matches by title, description and two of its habits.
        List<Long> ids = search(m, null, viewer);
        assertEquals(ids.size(), new HashSet<>(ids).size(), ids.toString());
        assertTrue(ids.contains(studyRoutine), ids.toString());
        assertEquals(1, ids.stream().filter(id -> id == studyRoutine).count());
    }

    @Test
    void nothingMatchingGivesAnEmptyList() throws Exception {
        Reply reply = get("/routines/search?q=" + encode("zzqx-nothing-" + m), null);
        assertEquals(200, reply.status(), reply.body());
        assertEquals("[]", reply.body().trim());
    }

    // ---- routines: nothing private ----

    @Test
    void privateDeletedAndBannedUsersRoutinesAreNeverFound() throws Exception {
        for (Account account : new Account[]{null, viewer, owner}) {
            List<Long> ids = search(m, null, account);
            assertFalse(ids.contains(privateRoutine), "private: " + ids);
            assertFalse(ids.contains(deletedRoutine), "deleted: " + ids);
            assertFalse(ids.contains(bannedRoutine), "banned author: " + ids);
            assertFalse(search(habit, null, account).contains(privateRoutine), "habits of a private routine");
        }
        // Whatever is found is a public routine or a built-in one.
        for (long id : search("", null, null)) {
            Map<String, Object> row = jdbc.queryForMap("select is_public, built_in, deleted from routines where id = ?", id);
            assertTrue(Boolean.TRUE.equals(row.get("is_public")) || Boolean.TRUE.equals(row.get("built_in")), row.toString());
            assertFalse(Boolean.TRUE.equals(row.get("deleted")), row.toString());
        }
    }

    @Test
    void bannedAuthorsRoutinesAreLeftOutOfEveryPublicList() throws Exception {
        for (Account account : new Account[]{null, viewer}) {
            List<Long> listed = routineIds(get("/routines/public", account));
            assertFalse(listed.contains(bannedRoutine), "Explore's community list: " + listed);
            assertFalse(listed.contains(bannedTopRoutine), "Explore's community list: " + listed);
            assertTrue(listed.contains(studyRoutine), listed.toString());
            assertFalse(listed.contains(privateRoutine) || listed.contains(deletedRoutine), listed.toString());

            List<Long> top = routineIds(get("/top-routines?period=week&category=" + encode(topCategory), account));
            assertTrue(top.contains(topControlRoutine), "a liked routine is ranked: " + top);
            assertFalse(top.contains(bannedTopRoutine), "a banned author's liked routine is not: " + top);
        }
    }

    @Test
    void thePublicListAndTheSearchShowTheSameRoutines() throws Exception {
        // An empty search finds every routine Explore may show: the community list plus the built-in ones.
        for (Account account : new Account[]{null, viewer}) {
            Set<Long> expected = new HashSet<>(routineIds(get("/routines/public", account)));
            expected.addAll(routineIds(get("/routines/built-in", account)));
            assertEquals(expected, new HashSet<>(search("", null, account)));
        }
    }

    @Test
    void builtInRoutinesAreFoundButNotByTheSystemAccountBehindThem() throws Exception {
        Assumptions.assumeTrue(count("select count(*) from routines where built_in = true and deleted = false and title = 'David Goggins Routine'") > 0);
        long goggins = jdbc.queryForObject("select id from routines where built_in = true and deleted = false and title = 'David Goggins Routine' limit 1", Long.class);
        assertTrue(search("GOGGINS", null, null).contains(goggins));
        assertTrue(search("goggins", "fitness", null).contains(goggins));
        assertFalse(search("goggins", "study", null).contains(goggins));
        assertFalse(search("explore@copymylife", null, viewer).contains(goggins));
    }

    @Test
    void aVisitorMaySearchRoutinesWithoutSigningIn() throws Exception {
        assertEquals(200, get("/routines/search?q=" + encode(title), null).status());
        assertEquals(200, get("/routines/search", null).status());
    }

    @Test
    void searchTextIsNeverSql() throws Exception {
        for (String attack : List.of("' OR '1'='1", "'; DROP TABLE routines; --", "%", "_", "\\", "%" + m + "%' OR 1=1 --")) {
            Reply reply = get("/routines/search?q=" + encode(attack), viewer);
            assertEquals(200, reply.status(), attack + " " + reply.body());
            List<Long> ids = ids(reply);
            assertFalse(ids.contains(studyRoutine), attack + " matched everything: " + ids);
            assertFalse(ids.contains(privateRoutine), attack + " " + ids);
        }
        assertTrue(count("select count(*) from routines where id = ?", studyRoutine) == 1, "the table is still there");
        // A literal percent sign or underscore is searched as itself.
        assertEquals(Set.of(trickyRoutine), mine(search("50%_off" + m, null, null)));
    }

    @Test
    void aVeryLongSearchIsRefusedWithAReason() throws Exception {
        Reply reply = get("/routines/search?q=" + encode("a".repeat(101)), null);
        assertEquals(400, reply.status(), reply.body());
        assertTrue(reply.body().toLowerCase().contains("search"), reply.body());
        assertEquals(200, get("/routines/search?q=" + encode("a".repeat(100)), null).status());
    }

    @Test
    void theSearchAnswersWithinTwoSecondsOverManyRoutines() throws Exception {
        // NFR-02, measured on the local test database with 2,000 extra public routines and
        // 4,000 habits; the rows are removed again afterwards.
        String perf = "perf" + m;
        long author = owner.id();
        try {
            List<Object[]> routines = new ArrayList<>();
            for (int i = 0; i < 2000; i++) {
                routines.add(new Object[]{perf + " routine " + i, "Load test routine number " + i, 60,
                        i % 2 == 0 ? "Study" : "Fitness", author});
            }
            jdbc.batchUpdate("insert into routines (title, description, duration_minutes, category, is_public, user_id,"
                    + " built_in, average_rating, rating_count, deleted, completed) values (?, ?, ?, ?, true, ?, false, 0, 0, false, false)", routines);
            List<Long> created = jdbc.queryForList("select id from routines where title like ?", Long.class, perf + " routine %");
            assertEquals(2000, created.size());
            List<Object[]> habits = new ArrayList<>();
            for (long id : created) {
                habits.add(new Object[]{perf + " stretch " + id, 10, id, author});
                habits.add(new Object[]{"Journal " + id, 10, id, author});
            }
            jdbc.batchUpdate("insert into habits (title, duration_minutes, routine_id, user_id) values (?, ?, ?, ?)", habits);
            jdbc.update("insert into routine_habits (routine_id, habit_id, position)"
                    + " select h.routine_id, h.id, row_number() over (partition by h.routine_id order by h.id)"
                    + " from habits h join routines r on r.id = h.routine_id where r.title like ?", perf + " routine %");

            get("/routines/search?q=warmup", null);
            for (String query : List.of(perf + " routine 1999", perf + " stretch", "journal " + created.get(0), "no-such-" + perf)) {
                long started = System.nanoTime();
                Reply reply = get("/routines/search?q=" + encode(query) + "&category=study", viewer);
                long millis = (System.nanoTime() - started) / 1_000_000;
                assertEquals(200, reply.status(), reply.body());
                assertTrue(millis < 2000, query + " took " + millis + " ms");
            }
            assertEquals(1000, mine(search(perf + " stretch", "study", null), created).size());
        } finally {
            jdbc.update("delete from routine_habits where routine_id in (select id from routines where title like ?)", perf + " routine %");
            jdbc.update("delete from habits where routine_id in (select id from routines where title like ?)", perf + " routine %");
            jdbc.update("delete from routines where title like ?", perf + " routine %");
        }
    }

    // ---- people ----

    @Test
    void aSignedInUserFindsPeopleByNameUsernameProfessionAndGoal() throws Exception {
        for (String query : List.of(personName, username, profession, goal, "  " + profession.toUpperCase() + " ")) {
            JsonNode people = people(query, viewer);
            JsonNode found = person(people, owner.id());
            assertTrue(found != null, query + " " + people);
            assertEquals(personName + " Aruzhan", found.path("name").asString());
            assertEquals(username, found.path("username").asString());
            assertEquals("Glaciologist " + profession, found.path("profession").asString());
        }
    }

    @Test
    void peopleSearchKeepsPrivateDetailsOutAndBannedPeopleAway() throws Exception {
        JsonNode people = people(profession, viewer);
        assertTrue(person(people, bannedUser) == null, "a banned user is not found: " + people);
        String body = people.toString();
        assertFalse(body.contains("@audit.test"), "no e-mail addresses: " + body);
        assertFalse(body.toLowerCase().contains("password"), body);
        assertTrue(person(people(profession, owner), owner.id()) == null, "you do not find yourself");
    }

    @Test
    void peopleSearchTreatsTextAsText() throws Exception {
        // Changed by the US-05 corrections: a visitor may search people too, so the guest is no
        // longer refused here; the same attacks are tried as a visitor and as a user.
        for (Account account : new Account[]{viewer, null}) {
            for (String attack : List.of("%", "_", "' OR '1'='1")) {
                Reply reply = get("/users/search?query=" + encode(attack), account);
                assertEquals(200, reply.status(), reply.body());
                assertTrue(person(json.readTree(reply.body()), owner.id()) == null, attack + " " + reply.body());
            }
        }
    }

    @Test
    void aVisitorFindsPeopleByNameUsernameProfessionAndGoal() throws Exception {
        for (String query : List.of(personName, username, profession, goal, "  " + goal.toUpperCase() + " ")) {
            JsonNode people = people(query, null);
            JsonNode found = person(people, owner.id());
            assertTrue(found != null, query + " " + people);
            assertEquals(personName + " Aruzhan", found.path("name").asString());
            assertEquals("Glaciologist " + profession, found.path("profession").asString());
            assertTrue(person(people, bannedUser) == null, "a banned user is not found: " + people);
            assertPublicOnly(people.toString());
        }
    }

    @Test
    void aVisitorReadsAPublicProfileWithoutPrivateDetails() throws Exception {
        Reply reply = get("/users/" + owner.id() + "/profile", null);
        assertEquals(200, reply.status(), reply.body());
        JsonNode profile = json.readTree(reply.body());
        assertEquals(personName + " Aruzhan", profile.path("name").asString());
        assertEquals(username, profile.path("username").asString());
        assertEquals("Glaciologist " + profession, profile.path("profession").asString());
        assertEquals("Run a marathon " + goal, profile.path("goals").asString());
        assertEquals("GUEST", profile.path("relationshipStatus").asString());
        assertFalse(profile.path("following").asBoolean(), reply.body());
        assertTrue(profile.path("requestId").isNull() || profile.path("requestId").isMissingNode(), reply.body());
        assertPublicOnly(reply.body());

        assertEquals(404, get("/users/" + bannedUser + "/profile", null).status(), "a banned user's profile is gone");
        assertEquals(404, get("/users/999999999/profile", null).status());
        List<Long> admins = jdbc.queryForList("select id from users where role = 'ADMIN' limit 1", Long.class);
        if (!admins.isEmpty()) {
            assertEquals(404, get("/users/" + admins.get(0) + "/profile", null).status(), "administrators have no public profile");
        }
        // A signed-in user still gets their relationship, as before.
        assertEquals("NONE", json.readTree(get("/users/" + owner.id() + "/profile", viewer).body()).path("relationshipStatus").asString());
    }

    @Test
    void aVisitorSeesOnlyTheOwnersPublicRoutines() throws Exception {
        Reply reply = get("/users/" + owner.id() + "/routines/public", null);
        assertEquals(200, reply.status(), reply.body());
        Set<Long> listed = new HashSet<>(routineIds(reply));
        assertTrue(listed.containsAll(Set.of(studyRoutine, fitnessRoutine, unicodeRoutine)), listed.toString());
        assertFalse(listed.contains(privateRoutine) || listed.contains(deletedRoutine), listed.toString());
        assertFalse(reply.body().contains("Secret " + description), reply.body());
        assertEquals(404, get("/users/" + bannedUser + "/routines/public", null).status());
    }

    @Test
    void aVisitorStillSignsInForListsPostsAndEveryChange() throws Exception {
        assertNotEquals(200, get("/users/" + owner.id() + "/todo-lists", null).status());
        assertNotEquals(200, get("/users/" + owner.id() + "/posts", null).status());
        assertEquals(403, send("POST", "/users/" + owner.id() + "/follow", null).status());
        assertEquals(403, send("DELETE", "/users/" + owner.id() + "/follow", null).status());
        assertEquals(403, get("/users/me", null).status());
        assertEquals(403, get("/users/me/following", null).status());
        assertEquals(403, get("/users/" + owner.id(), null).status());
        assertEquals(403, get("/users", null).status());
        assertEquals(403, put("/users/me", null, Map.of("name", "Changed")).status());
        assertEquals(personName + " Aruzhan", jdbc.queryForObject("select name from users where id = ?", String.class, owner.id()));
    }

    // ---- the Explore page ----

    @Test
    void browserFindsRoutinesByEverySignal() {
        JsonNode view = page("signals");
        for (String signal : List.of("title", "author", "username", "habit", "description", "unicode", "category")) {
            assertTrue(view.path(signal).path("viewAll").toString().contains("Sunrise Ritual")
                            || view.path(signal).path("viewAll").toString().contains("Таңғы"),
                    signal + " " + view.path(signal));
            assertFalse(view.path(signal).path("emptyVisible").asBoolean() && "No routines found".equals(view.path(signal).path("emptyTitle").asString()),
                    signal + " " + view.path(signal));
        }
        assertTrue(view.path("unicode").path("viewAll").toString().contains("Таңғы жүгіру"), view.path("unicode").toString());
        assertTrue(view.path("profession").path("viewAll").toString().contains("Sunrise Ritual"), view.path("profession").toString());
        assertTrue(view.path("goal").path("viewAll").toString().contains("Sunrise Ritual"), view.path("goal").toString());
        // Changed by the US-05 corrections: profession is public, so a visitor finds it too.
        assertTrue(view.path("guestProfession").path("viewAll").toString().contains("Sunrise Ritual"), view.path("guestProfession").toString());
    }

    @Test
    void browserSearchesFeaturedRoutinesByNameProfessionAndCategoryWord() {
        JsonNode view = page("featured");
        assertEquals("[\"Cristiano Ronaldo\"]", view.path("name").path("names").toString(), view.toString());
        assertEquals("[\"Marques Brownlee\"]", view.path("profession").path("names").toString(), view.toString());
        assertEquals("[\"Arianna Huffington\"]", view.path("goal").path("names").toString(), "a goal word from a description: " + view);
        assertTrue(view.path("habitsWord").path("names").size() < 6,
                "the word habits no longer matches every card through its \"8 habits\" label: " + view);
    }

    @Test
    void browserCombinesTheSearchWithTheCategory() {
        JsonNode view = page("combined");
        assertTrue(view.path("study").path("viewAll").toString().contains("Sunrise Ritual"), view.toString());
        assertFalse(view.path("study").path("viewAll").toString().contains("Strength"), view.toString());
        assertTrue(view.path("fitness").path("viewAll").toString().contains("Strength"), view.toString());
        assertFalse(view.path("fitness").path("viewAll").toString().contains("Sunrise Ritual"), view.toString());
        assertEquals("No routines found", view.path("halloween").path("emptyTitle").asString(), view.toString());
    }

    @Test
    void browserShowsEachRoutineOnce() {
        // A featured person or a View all card is keyed by name, a community routine by its id:
        // two people may give their routines the same title.
        for (String state : List.of("all", "allSearch", "fitness", "halloween")) {
            JsonNode view = page("duplicates").path(state);
            List<String> keys = new ArrayList<>();
            view.path("keys").forEach(key -> keys.add(key.asString()));
            assertEquals(keys.size(), new HashSet<>(keys).size(), state + " " + keys);
            assertTrue(keys.size() > 0, state + " " + view);
        }
    }

    @Test
    void browserExplainsNoResultsAndResetsTheFilters() {
        JsonNode none = page("noResults");
        assertTrue(none.path("emptyVisible").asBoolean(), none.toString());
        assertEquals("No routines found", none.path("emptyTitle").asString());
        assertTrue(none.path("hint").asString().toLowerCase().contains("reset"), none.toString());
        assertTrue(none.path("resetVisible").asBoolean(), none.toString());
        assertEquals(0, none.path("cards").asInt(), none.toString());
        assertEquals(0, none.path("blankCards").asInt(), none.toString());

        JsonNode reset = page("afterReset");
        assertFalse(reset.path("emptyVisible").asBoolean(), reset.toString());
        assertEquals(6, reset.path("cards").asInt(), reset.toString());
        assertEquals("", reset.path("query").asString());
        assertEquals("all", reset.path("activeChip").asString());
        assertFalse(reset.path("halloween").asBoolean(), reset.toString());
        assertTrue(reset.path("viewAllTotal").asInt() > 0, "every routine is back under View all: " + reset);
        assertEquals(page("idle").path("viewAllTotal").asInt(), reset.path("viewAllTotal").asInt());
    }

    @Test
    void browserClearsTheSearch() {
        JsonNode typed = page("typed");
        assertTrue(typed.path("clearVisible").asBoolean(), typed.toString());
        assertFalse(page("idle").path("clearVisible").asBoolean(), page("idle").toString());

        JsonNode cleared = page("cleared");
        assertEquals("", cleared.path("query").asString());
        assertFalse(cleared.path("clearVisible").asBoolean(), cleared.toString());
        assertEquals(6, cleared.path("cards").asInt(), cleared.toString());
        assertFalse(cleared.path("emptyVisible").asBoolean(), cleared.toString());
        assertFalse(cleared.path("peopleVisible").asBoolean(), cleared.toString());
        assertTrue(cleared.path("focused").asBoolean(), "the field keeps the focus: " + cleared);
    }

    @Test
    void browserShowsLoadingAndAFailedSearch() {
        JsonNode loading = page("loading");
        assertTrue(loading.path("status").asString().toLowerCase().contains("searching"), loading.toString());

        JsonNode failed = page("searchFailed");
        assertTrue(failed.path("status").asString().toLowerCase().contains("could not"), failed.toString());
        assertEquals("[\"Cristiano Ronaldo\"]", failed.path("names").toString(), "the page still finds what it shows: " + failed);
    }

    @Test
    void browserHandlesAnEmptyDatabase() {
        JsonNode view = page("emptyDatabase");
        assertTrue(view.path("emptyVisible").asBoolean(), view.toString());
        assertEquals("No routines yet", view.path("emptyTitle").asString(), view.toString());
        assertEquals(0, view.path("cards").asInt(), view.toString());
    }

    @Test
    void browserFindsPeopleForEveryoneAndLinksToTheirProfile() {
        JsonNode view = page("people");
        assertTrue(view.path("peopleVisible").asBoolean(), view.toString());
        JsonNode found = null;
        for (JsonNode card : view.path("people")) {
            if (card.path("href").asString().endsWith("user-profile.html?id=" + owner.id())) {
                found = card;
            }
        }
        assertTrue(found != null, view.toString());
        assertTrue(found.path("text").asString().contains(personName), found.toString());
        assertTrue(found.path("text").asString().contains("Glaciologist"), found.toString());
        assertTrue(page("peopleByGoal").path("people").toString().contains("user-profile.html?id=" + owner.id()), page("peopleByGoal").toString());

        // Changed by the US-05 corrections: a visitor gets the same people, not a sign-in note.
        JsonNode guest = page("guestPeople");
        assertTrue(guest.path("people").toString().contains("user-profile.html?id=" + owner.id()), guest.toString());
        assertEquals("", guest.path("peopleNote").asString(), guest.toString());
        assertTrue(page("guestPeopleByGoal").path("people").toString().contains("user-profile.html?id=" + owner.id()),
                page("guestPeopleByGoal").toString());
    }

    @Test
    void browserLetsAVisitorOpenAPublicProfile() {
        JsonNode view = page("guestProfile");
        assertTrue(view.path("path").asString().endsWith("/user-profile.html"), "no sign-in wall: " + view);
        assertEquals(personName + " Aruzhan", view.path("name").asString(), view.toString());
        assertEquals("@" + username, view.path("username").asString(), view.toString());
        assertTrue(view.path("profession").asString().contains("Glaciologist"), view.toString());
        assertTrue(view.path("routines").toString().contains("Sunrise Ritual"), view.toString());
        assertFalse(view.path("routines").toString().contains("Hidden"), "no private routine: " + view);
        assertTrue(view.path("signInVisible").asBoolean(), view.toString());
        assertFalse(view.path("menuVisible").asBoolean(), view.toString());
        assertFalse(view.path("followVisible").asBoolean(), view.toString());
        assertTrue(view.path("actions").toString().contains("login.html"), "following asks to sign in: " + view);
        assertTrue(view.path("todo").asString().toLowerCase().contains("sign in"), view.toString());
        assertTrue(view.path("posts").asString().toLowerCase().contains("sign in"), view.toString());
        assertFalse(view.path("email").asBoolean(), "no e-mail address on the page: " + view);
        assertTrue(view.path("back").asString().endsWith("Explore.html"), view.toString());

        // From a person found on Explore to their profile, without signing in.
        JsonNode followed = page("guestClickThrough");
        assertTrue(followed.path("path").asString().endsWith("/user-profile.html"), followed.toString());
        assertEquals(personName + " Aruzhan", followed.path("name").asString(), followed.toString());

        assertEquals("Profile not found", page("guestBannedProfile").path("name").asString(), page("guestBannedProfile").toString());
    }

    @Test
    void browserShowsAVisitorsProfileInEveryDesignAndWidth() {
        List<String> problems = new ArrayList<>();
        JsonNode layouts = page("guestProfileLayouts");
        assertTrue(layouts.size() >= 4, layouts.toString());
        layouts.properties().forEach(entry -> {
            JsonNode view = entry.getValue();
            if (!view.path("path").asString().endsWith("/user-profile.html")) {
                problems.add(entry.getKey() + " left the page " + view);
            }
            if (view.path("overflow").asInt() > 0) {
                problems.add(entry.getKey() + " scrolls sideways " + view);
            }
            if (view.path("measured").asInt() < 4 || view.path("minContrast").asDouble() < 4.5) {
                problems.add(entry.getKey() + " text too faint " + view);
            }
        });
        assertEquals(List.of(), problems);
    }

    @Test
    void browserNeverSaysNothingMatchedWhileTheAnswerIsOnItsWay() {
        // The habit word is only found by the server; while its answer is held back the page
        // says it is searching, never that nothing matched.
        JsonNode states = page("states");
        List<String> problems = new ArrayList<>();
        for (JsonNode sample : states.path("waiting")) {
            if ("No routines found".equals(sample.path("emptyTitle").asString()) || sample.path("viewAllEmpty").asBoolean()) {
                problems.add(sample.toString());
            }
        }
        assertEquals(List.of(), problems, "No Results shown before the answer");
        assertTrue(states.path("waiting").size() > 5, states.toString());
        assertTrue(states.path("waiting").get(states.path("waiting").size() - 1).path("status").asString().contains("Searching"), states.toString());

        JsonNode none = page("noneWaiting");
        assertTrue(none.size() > 5, none.toString());
        for (JsonNode sample : none) {
            assertFalse("No routines found".equals(sample.path("emptyTitle").asString()) || sample.path("viewAllEmpty").asBoolean(),
                    "a search with no results says so only after the answer: " + sample);
        }
        assertEquals("No routines found", page("noneAnswered").path("emptyTitle").asString(), page("noneAnswered").toString());
        assertTrue(page("noneAnswered").path("resetVisible").asBoolean(), page("noneAnswered").toString());
    }

    @Test
    void browserMovesThroughSearchingResultsNoResultsAndError() {
        JsonNode states = page("states");
        JsonNode results = states.path("results");
        assertTrue(results.path("viewAll").toString().contains("Sunrise Ritual"), "results once answered: " + states);
        assertFalse(results.path("emptyVisible").asBoolean(), results.toString());
        assertEquals("", results.path("status").asString(), results.toString());

        JsonNode none = states.path("noResults");
        assertEquals("No routines found", none.path("emptyTitle").asString(), none.toString());
        assertEquals("", none.path("status").asString(), none.toString());

        JsonNode error = states.path("error");
        assertTrue(error.path("status").asString().contains("could not"), error.toString());

        JsonNode again = states.path("again");
        assertTrue(again.path("viewAll").toString().contains("Sunrise Ritual"), "a working search after an error: " + again);
        assertEquals("", again.path("status").asString(), again.toString());
    }

    @Test
    void browserKeepsTheNewestSearchWhenAnOlderAnswerArrivesLate() {
        JsonNode before = page("raceBefore");
        assertEquals("No routines found", before.path("emptyTitle").asString(), before.toString());
        JsonNode after = page("raceAfter");
        assertEquals("zzqx-no-such-routine", after.path("query").asString(), after.toString());
        assertEquals("No routines found", after.path("emptyTitle").asString(), "the late answer changed nothing: " + after);
        assertFalse(after.path("viewAll").toString().contains("Sunrise Ritual"), after.toString());
        assertEquals("", after.path("status").asString(), after.toString());
    }

    @Test
    void browserNeverRunsSearchedTextAsCode() {
        JsonNode view = page("xss");
        assertFalse(view.path("xss").asBoolean(), view.toString());
        assertTrue(view.path("viewAll").toString().contains("<img"), "shown as text: " + view);
        assertTrue(view.path("people").toString().contains("<img"), "shown as text: " + view);

        JsonNode injection = page("injection");
        assertTrue(injection.path("emptyVisible").asBoolean(), injection.toString());
        assertEquals("No routines found", injection.path("emptyTitle").asString(), injection.toString());
    }

    @Test
    void browserKeepsTheLayoutInEveryDesignAndWidth() {
        List<String> problems = new ArrayList<>();
        JsonNode layouts = page("layouts");
        assertTrue(layouts.size() >= 15, layouts.toString());
        layouts.properties().forEach(entry -> {
            JsonNode view = entry.getValue();
            if (view.path("overflow").asInt() > 0) {
                problems.add(entry.getKey() + " scrolls sideways " + view);
            }
            if (view.path("width").asInt() >= 1280 && !view.path("chipsOneRow").asBoolean()) {
                problems.add(entry.getKey() + " categories wrap " + view);
            }
            if (view.path("width").asInt() < 600 && !view.path("chipsScrollInside").asBoolean()) {
                problems.add(entry.getKey() + " categories do not scroll inside their row " + view);
            }
            if (!view.path("searchFits").asBoolean() || !view.path("emptyFits").asBoolean()) {
                problems.add(entry.getKey() + " search or message cut off " + view);
            }
            if (view.path("minContrast").asDouble() < 4.5) {
                problems.add(entry.getKey() + " text too faint " + view);
            }
        });
        assertEquals(List.of(), problems);
    }

    @Test
    void browserNeverFailsOrInterrupts() {
        assertEquals("[]", page("errors").toString(), "the page throws nothing");
        assertEquals("[]", page("dialogs").toString(), "no alert boxes");
    }

    // ---- data ----

    @BeforeAll
    void createContent() throws Exception {
        owner = register(personName + " Aruzhan");
        assertEquals(200, put("/users/me", owner, Map.of("name", personName + " Aruzhan", "username", username,
                "profession", "Glaciologist " + profession, "goals", "Run a marathon " + goal)).status());
        viewer = register("Viewer " + m);

        studyRoutine = routine(owner, "Sunrise Ritual " + title, "Calm start " + description, "Study", true);
        habit(owner, studyRoutine, "Cold shower " + habit);
        habit(owner, studyRoutine, "Evening " + habit + " walk");
        fitnessRoutine = routine(owner, "Strength " + title + " Plan", "Lift and rest", "Fitness", true);
        unicodeRoutine = routine(owner, "Таңғы жүгіру " + m, "Утренняя пробежка " + m + ". Өмір сүру ұлы " + m, "Wellness", true);
        privateRoutine = routine(owner, "Hidden " + title, "Secret " + description, "Study", false);
        habit(owner, privateRoutine, "Private " + habit);
        deletedRoutine = routine(owner, "Deleted " + title, "Gone", "Study", true);
        assertEquals(204, send("DELETE", "/routines/" + deletedRoutine, owner).status());

        tricky = register("Tricky " + m);
        assertEquals(200, put("/users/me", tricky, Map.of("name", "Tricky " + m, "username", "trk" + m,
                "profession", TRICKY + " xsp" + m)).status());
        trickyRoutine = routine(tricky, TRICKY + " 50%_off" + m, "Careful text", "Wellness", true);

        Account banned = register("Banned " + m);
        assertEquals(200, put("/users/me", banned, Map.of("name", "Banned " + m, "username", "ban" + m,
                "profession", "Glaciologist " + profession)).status());
        bannedRoutine = routine(banned, "Banned " + title, "Banned " + description, "Study", true);
        bannedTopRoutine = routine(banned, "Banned top " + m, "Liked before the ban", topCategory, true);

        // Habit types: a habit made in the kind routine and one made in a private routine,
        // both added to a public Study routine by the same person.
        Account maker = register("Maker " + m);
        typedRoutine = routine(maker, "Library hour " + m, "Quiet reading", "Study", true);
        kindRoutine = routine(maker, "Kettle plan " + m, "Swings", kind, true);
        long kindHabit = habit(maker, kindRoutine, "Kettlebell swing " + m);
        long secret = routine(maker, "Secret origin " + m, "Not shared", hush, false);
        long secretHabit = habit(maker, secret, "Breathing drill " + m);
        for (long habitId : List.of(kindHabit, secretHabit)) {
            Reply added = send("POST", "/routines/" + typedRoutine + "/routine-habits/" + habitId, maker);
            assertEquals(200, added.status(), added.body());
        }

        // Top Routines: a liked routine of a banned author next to an ordinary one.
        topControlRoutine = routine(maker, "Top control " + m, "Liked", topCategory, true);
        for (long liked : List.of(bannedTopRoutine, topControlRoutine)) {
            Reply like = send("POST", "/liked-routines/" + liked, viewer);
            assertEquals(200, like.status(), like.body());
        }

        bannedUser = banned.id();
        jdbc.update("update users set banned = true where id = ?", bannedUser);

        String chrome = findChrome();
        if (chrome == null || !nodeAvailable()) {
            return;
        }

        Map<String, Object> fixtures = new LinkedHashMap<>();
        fixtures.put("base", "http://127.0.0.1:" + port);
        fixtures.put("viewerToken", viewer.token());
        fixtures.put("ownerId", owner.id());
        fixtures.put("bannedId", bannedUser);
        fixtures.put("studyRoutineId", studyRoutine);
        fixtures.put("marker", m);
        fixtures.put("title", title);
        fixtures.put("description", description);
        fixtures.put("personName", personName);
        fixtures.put("username", username);
        fixtures.put("profession", profession);
        fixtures.put("goal", goal);
        fixtures.put("habit", habit);
        fixtures.put("tricky", "xsp" + m);
        fixtures.put("trickyRoutine", "50%_off" + m);

        Path dir = Files.createTempDirectory("search-filter-browser");
        Files.writeString(dir.resolve("fixtures.json"), json.writeValueAsString(fixtures));
        Process process = new ProcessBuilder(
                "node", "src/test/e2e/search-filter-check.mjs", chrome,
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

    private List<Long> search(String query, String category, Account account) throws Exception {
        String path = "/routines/search?q=" + encode(query) + (category == null ? "" : "&category=" + encode(category));
        Reply reply = get(path, account);
        assertEquals(200, reply.status(), path + " " + reply.body());
        return ids(reply);
    }

    private List<Long> ids(Reply reply) throws Exception {
        List<Long> ids = new ArrayList<>();
        json.readTree(reply.body()).forEach(id -> ids.add(id.asLong()));
        return ids;
    }

    // Only this run's routines, so that rows left by earlier runs never change the answer.
    private Set<Long> mine(List<Long> ids) {
        return mine(ids, List.of(studyRoutine, fitnessRoutine, unicodeRoutine, trickyRoutine, privateRoutine, deletedRoutine, bannedRoutine));
    }

    private Set<Long> mine(List<Long> ids, List<Long> ours) {
        Set<Long> found = new HashSet<>(ids);
        found.retainAll(new HashSet<>(ours));
        return found;
    }

    private JsonNode people(String query, Account account) throws Exception {
        Reply reply = get("/users/search?query=" + encode(query), account);
        assertEquals(200, reply.status(), reply.body());
        return json.readTree(reply.body());
    }

    private static JsonNode person(JsonNode people, long id) {
        for (JsonNode person : people) {
            if (person.path("id").asLong() == id) {
                return person;
            }
        }
        return null;
    }

    private Account register(String name) throws Exception {
        String email = "search_" + UUID.randomUUID().toString().substring(0, 12) + "@audit.test";
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("email", email);
        body.put("password", PASSWORD);
        Reply reply = send("POST", "/auth/register", null, body);
        assertEquals(200, reply.status(), reply.body());
        JsonNode node = json.readTree(reply.body());
        return new Account(node.path("user").path("id").asLong(), node.path("token").asString());
    }

    private long routine(Account account, String name, String text, String category, boolean isPublic) throws Exception {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("title", name);
        fields.put("description", text);
        fields.put("category", category);
        fields.put("durationMinutes", "120");
        fields.put("isPublic", String.valueOf(isPublic));
        Reply reply = form("POST", "/routines", account, fields);
        assertEquals(200, reply.status(), reply.body());
        return json.readTree(reply.body()).path("id").asLong();
    }

    private long habit(Account account, long routineId, String name) throws Exception {
        Reply reply = send("POST", "/routines/" + routineId + "/habits", account, Map.of("title", name, "durationMinutes", 20));
        assertEquals(200, reply.status(), reply.body());
        return json.readTree(reply.body()).path("id").asLong();
    }

    // The ids in a list of routines, as /routines/public, /top-routines or a profile return them.
    private List<Long> routineIds(Reply reply) throws Exception {
        assertEquals(200, reply.status(), reply.body());
        List<Long> ids = new ArrayList<>();
        json.readTree(reply.body()).forEach(routine -> ids.add(routine.path("id").asLong()));
        return ids;
    }

    // Nothing about the account itself: no e-mail address, password, token, role or ban.
    private static void assertPublicOnly(String body) {
        String lower = body.toLowerCase();
        assertFalse(lower.contains("@audit.test"), "no e-mail addresses: " + body);
        for (String key : List.of("\"email\"", "\"password\"", "\"token\"", "\"tokenversion\"", "\"role\"", "\"banned\"")) {
            assertFalse(lower.contains(key), key + " in " + body);
        }
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private Reply get(String path, Account account) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path)).GET();
        if (account != null) {
            builder.header("Authorization", "Bearer " + account.token());
        }
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(), response.body());
    }

    private Reply put(String path, Account account, Map<String, ?> payload) throws Exception {
        return send("PUT", path, account, payload);
    }

    private Reply send(String method, String path, Account account) throws Exception {
        return send(method, path, account, null);
    }

    private Reply send(String method, String path, Account account, Map<String, ?> payload) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path))
                .method(method, payload == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload)));
        if (payload != null) {
            builder.header("Content-Type", "application/json");
        }
        if (account != null) {
            builder.header("Authorization", "Bearer " + account.token());
        }
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(), response.body());
    }

    private Reply form(String method, String path, Account account, Map<String, String> fields) throws Exception {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (Map.Entry<String, String> field : fields.entrySet()) {
            body.write(("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"" + field.getKey() + "\"\r\n"
                    + "Content-Type: text/plain; charset=UTF-8\r\n\r\n"
                    + field.getValue() + "\r\n").getBytes(StandardCharsets.UTF_8));
        }
        body.write(("--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8));

        HttpRequest request = HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .header("Authorization", "Bearer " + account.token())
                .method(method, HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
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
