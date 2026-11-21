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
import java.sql.Timestamp;
import java.time.LocalDateTime;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * US-30: Top Routines ranks the public routines people liked, saved and rated, overall or by
 * one count, for the last day, week or month, within a routine category and a time of day.
 * The time of day (morning 04:00-11:59, afternoon 12:00-16:59, evening 17:00-03:59) comes from
 * the start time of a routine's first timed activity in that day; it is not the routine's
 * category, and a routine with no timed activity has none. Every number is counted from the
 * stored likes, saves and ratings on each request, so the order follows every change.
 * Private, deleted and banned authors' routines are never ranked.
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
class RoutineLeaderboardRegressionTest {

    private static final String PASSWORD = "Audit#12345";
    private static final String BOUNDARY = "LeaderboardBoundary";
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

    // One marker per run; each group of routines has a category of its own, so that routines
    // ranked by other runs or other test classes in the same database never change the answer.
    private final String m = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    private final String rank = "Lbrank" + m;
    private final String times = "Lbtime" + m;
    private final String edges = "Lbedge" + m;
    private final String live = "Lblive" + m;
    private final String period = "Lbperiod" + m;
    private final String hidden = "Lbhidden" + m;
    private final String ties = "Lbtie" + m;

    private JsonNode browser;
    private Account author;
    private final List<Account> fans = new ArrayList<>();

    private long highRated;
    private long manyLikes;
    private long manySaves;
    private long middle;

    private long morning;
    private long afternoon;
    private long evening;
    private long morningAndEvening;
    private long lateNight;
    private long untimed;
    private long pausedMorning;
    private long removedMorning;
    private final Map<String, Long> edge = new LinkedHashMap<>();

    private long fitnessAfternoon;
    private long morningCategoryEvening;
    private long halloweenRoutine;

    private long liveA;
    private long liveB;

    private long today;
    private long thisWeek;
    private long thisMonth;
    private long longAgo;

    private long privateRoutine;
    private long deletedRoutine;
    private long bannedRoutine;
    private long shownRoutine;

    private final List<Long> tied = new ArrayList<>();

    // ---- AC1 and the sort orders ----

    @Test
    void highestRatedPutsTheBestAverageFirst() throws Exception {
        assertEquals(List.of(highRated, middle, manyLikes, manySaves), ids(top("week", "rated", rank, "all")));
    }

    @Test
    void aRoutineWithoutRatingsIsNeverGivenOne() throws Exception {
        JsonNode saves = routine(top("week", "rated", rank, "all"), manySaves);
        assertEquals(0, saves.path("ratingCount").asLong(), saves.toString());
        assertEquals(0.0, saves.path("averageRating").asDouble(), saves.toString());
    }

    @Test
    void mostLikedPutsTheMostLikesFirst() throws Exception {
        // Equal likes go by saves, then by rating.
        assertEquals(List.of(manyLikes, middle, manySaves, highRated), ids(top("week", "liked", rank, "all")));
    }

    @Test
    void mostSavedPutsTheMostSavesFirst() throws Exception {
        assertEquals(List.of(manySaves, middle, manyLikes, highRated), ids(top("week", "saved", rank, "all")));
    }

    @Test
    void overallAddsLikesSavesRatingsAndTenTimesTheAverage() throws Exception {
        JsonNode list = top("week", "overall", rank, "all");
        assertEquals(List.of(highRated, middle, manyLikes, manySaves), ids(list));
        assertEquals(51.0, score(routine(list, highRated)), 1e-9);
        assertEquals(49.0, score(routine(list, middle)), 1e-9);
        assertEquals(34.0, score(routine(list, manyLikes)), 1e-9);
        assertEquals(3.0, score(routine(list, manySaves)), 1e-9);
    }

    @Test
    void everyNumberIsCountedFromTheStoredRows() throws Exception {
        for (JsonNode routine : top("month", "overall", rank, "all")) {
            long id = routine.path("id").asLong();
            assertEquals(count("select count(*) from liked_routines where routine_id = ?", id), routine.path("likeCount").asLong());
            assertEquals(count("select count(*) from saved_routines where routine_id = ?", id), routine.path("saveCount").asLong());
            assertEquals(count("select count(*) from routine_ratings where routine_id = ?", id), routine.path("ratingCount").asLong());
            Double average = jdbc.queryForObject("select avg(value) from routine_ratings where routine_id = ?", Double.class, id);
            assertEquals(average == null ? 0.0 : average, routine.path("averageRating").asDouble(), 1e-9);
        }
    }

    @Test
    void equalRoutinesKeepOneOrderByTheirId() throws Exception {
        for (String sort : List.of("overall", "liked", "saved", "rated")) {
            assertEquals(tied, ids(top("week", sort, ties, "all")), sort);
            assertEquals(ids(top("week", sort, ties, "all")), ids(top("week", sort, ties, "all")), sort);
        }
    }

    @Test
    void theDefaultIsTheBestOfEveryCategoryAndTimeThisWeek() throws Exception {
        JsonNode plain = json.readTree(get("/top-routines", null).body());
        assertEquals(plain, top("week", "overall", "all", "all"));
        assertTrue(plain.size() <= 5, plain.toString());
        for (int i = 1; i < plain.size(); i++) {
            assertTrue(before(plain.get(i - 1), plain.get(i)), "in order: " + plain);
        }
    }

    // ---- AC2: time of day ----

    @Test
    void eachTimeOfDayListsTheRoutinesThatStartInIt() throws Exception {
        assertEquals(Set.of(morning, morningAndEvening), Set.copyOf(ids(top("week", "overall", times, "morning"))));
        assertEquals(Set.of(afternoon, removedMorning), Set.copyOf(ids(top("week", "overall", times, "afternoon"))));
        assertEquals(Set.of(evening, lateNight, pausedMorning), Set.copyOf(ids(top("week", "overall", times, "evening"))));
    }

    @Test
    void aRoutineIsTimedByItsFirstActivityOfTheDay() throws Exception {
        // 07:15 and 21:00: a morning routine; 23:30 and 00:30: one evening.
        JsonNode list = top("week", "overall", times, "all");
        assertEquals("morning", routine(list, morningAndEvening).path("timeOfDay").asString());
        assertEquals("07:15", routine(list, morningAndEvening).path("startsAt").asString());
        assertEquals("evening", routine(top("week", "overall", times, "evening"), lateNight).path("timeOfDay").asString());
        assertEquals("23:30", routine(top("week", "overall", times, "evening"), lateNight).path("startsAt").asString());
    }

    @Test
    void theTimesOfDayMeetAtExactBoundaries() throws Exception {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("03:59", "evening");
        expected.put("04:00", "morning");
        expected.put("11:59", "morning");
        expected.put("12:00", "afternoon");
        expected.put("16:59", "afternoon");
        expected.put("17:00", "evening");
        expected.put("23:59", "evening");
        expected.put("00:00", "evening");
        for (String time : List.of("morning", "afternoon", "evening")) {
            Set<Long> want = new HashSet<>();
            expected.forEach((start, part) -> {
                if (part.equals(time)) {
                    want.add(edge.get(start));
                }
            });
            assertEquals(want, Set.copyOf(ids(top("month", "overall", edges, time))), time);
        }
    }

    @Test
    void aRoutineWithoutStartTimesIsOnlyUnderAllTimes() throws Exception {
        JsonNode all = top("month", "liked", times, "all");
        assertTrue(ids(all).contains(untimed), all.toString());
        assertNull(textOrNull(routine(all, untimed).path("timeOfDay")), all.toString());
        for (String time : List.of("morning", "afternoon", "evening")) {
            assertFalse(ids(top("month", "liked", times, time)).contains(untimed), time);
        }
    }

    @Test
    void pausedAndRemovedActivitiesDoNotTimeARoutine() throws Exception {
        assertEquals("evening", routine(top("week", "overall", times, "evening"), pausedMorning).path("timeOfDay").asString());
        assertEquals("afternoon", routine(top("week", "overall", times, "afternoon"), removedMorning).path("timeOfDay").asString());
        assertFalse(ids(top("week", "overall", times, "morning")).contains(pausedMorning));
        assertFalse(ids(top("week", "overall", times, "morning")).contains(removedMorning));
    }

    @Test
    void theRoutineCategoryMorningIsNotTheTimeOfDayMorning() throws Exception {
        assertTrue(ids(top("week", "overall", "Morning", "evening")).contains(morningCategoryEvening));
        assertFalse(ids(top("week", "overall", "Morning", "morning")).contains(morningCategoryEvening));
        assertFalse(ids(top("week", "overall", "all", "morning")).contains(morningCategoryEvening));
    }

    // ---- filters together ----

    @Test
    void theFiltersWorkTogether() throws Exception {
        assertEquals(List.of(morningAndEvening, morning), ids(top("week", "rated", times, "morning")));
        assertEquals(List.of(lateNight, pausedMorning, evening), ids(top("week", "liked", times, "evening")));

        JsonNode fitness = top("week", "overall", "Fitness", "afternoon");
        assertTrue(ids(fitness).contains(fitnessAfternoon), fitness.toString());
        fitness.forEach(routine -> {
            assertEquals("Fitness", routine.path("category").asString(), routine.toString());
            assertEquals("afternoon", routine.path("timeOfDay").asString(), routine.toString());
        });

        JsonNode halloween = top("week", "overall", "Halloween", "all");
        assertTrue(halloween.size() > 0 && ids(halloween).contains(halloweenRoutine) || halloween.size() == 5, halloween.toString());
        halloween.forEach(routine -> assertEquals("Halloween", routine.path("category").asString(), routine.toString()));
        // Built-in Halloween routines have no timed activities.
        assertEquals(0, top("week", "overall", "Halloween", "morning").size());
    }

    @Test
    void anUnknownCategoryOrTimeIsAnsweredSafely() throws Exception {
        for (String path : List.of(
                "/top-routines?category=" + encode("' OR '1'='1") + "&time=all",
                "/top-routines?category=" + encode(TRICKY) + "&sort=" + encode("<script>"),
                "/top-routines?category=Nothing" + m + "&time=evening")) {
            Reply reply = get(path, null);
            assertEquals(200, reply.status(), path + " " + reply.body());
            assertEquals("[]", reply.body(), path);
        }
        Reply odd = get("/top-routines?category=" + encode(rank) + "&time=" + encode("' OR 1=1 --"), null);
        assertEquals(200, odd.status(), odd.body());
        assertEquals(top("week", "overall", rank, "all"), json.readTree(odd.body()));
    }

    // ---- AC4: the order follows every change ----

    @Test
    void aLikeAndAnUnlikeMoveARoutine() throws Exception {
        assertEquals(List.of(liveB, liveA), ids(top("week", "liked", live, "all")));
        like(fans.get(2), liveA);
        like(fans.get(3), liveA);
        assertEquals(List.of(liveA, liveB), ids(top("week", "liked", live, "all")));
        assertEquals(204, unlike(fans.get(2), liveA), "unlike");
        assertEquals(204, unlike(fans.get(3), liveA), "unlike");
        assertEquals(List.of(liveB, liveA), ids(top("week", "liked", live, "all")));
    }

    @Test
    void aChangedRatingMovesARoutineAndCountsOnce() throws Exception {
        rate(fans.get(0), liveB, 4);
        rate(fans.get(1), liveA, 3);
        assertEquals(List.of(liveB, liveA), ids(top("week", "rated", live, "all")));
        rate(fans.get(1), liveA, 5);
        JsonNode after = top("week", "rated", live, "all");
        assertEquals(List.of(liveA, liveB), ids(after));
        assertEquals(1, routine(after, liveA).path("ratingCount").asLong(), after.toString());
        assertEquals(5.0, routine(after, liveA).path("averageRating").asDouble(), after.toString());
    }

    @Test
    void aSecondLikeOrSaveBySomeoneCountsOnce() throws Exception {
        long before = routine(top("week", "liked", live, "all"), liveB).path("likeCount").asLong();
        send("POST", "/liked-routines/" + liveB, fans.get(0));
        send("POST", "/liked-routines/" + liveB, fans.get(0));
        assertEquals(before, routine(top("week", "liked", live, "all"), liveB).path("likeCount").asLong());

        save(fans.get(3), liveB);
        send("POST", "/saved-routines/" + liveB, fans.get(3));
        assertEquals(1, routine(top("week", "saved", live, "all"), liveB).path("saveCount").asLong());
        assertEquals(1, count("select count(*) from saved_routines where routine_id = ?", liveB));
        assertEquals(204, send("DELETE", "/saved-routines/" + liveB, fans.get(3)).status());
    }

    @Test
    void aSaveAndAnUnsaveMoveARoutine() throws Exception {
        long a = routine(top("week", "saved", rank, "all"), middle).path("saveCount").asLong();
        save(fans.get(3), middle);
        save(fans.get(4), middle);
        save(fans.get(5), middle);
        assertEquals(middle, ids(top("week", "saved", rank, "all")).get(0));
        for (int fan = 3; fan <= 5; fan++) {
            assertEquals(204, send("DELETE", "/saved-routines/" + middle, fans.get(fan)).status());
        }
        assertEquals(a, routine(top("week", "saved", rank, "all"), middle).path("saveCount").asLong());
        assertEquals(manySaves, ids(top("week", "saved", rank, "all")).get(0));
    }

    // ---- day, week and month ----

    @Test
    void dayWeekAndMonthCountWhatHappenedInThem() throws Exception {
        assertEquals(Set.of(today), Set.copyOf(ids(top("day", "liked", period, "all"))));
        assertEquals(Set.of(today, thisWeek), Set.copyOf(ids(top("week", "liked", period, "all"))));
        assertEquals(Set.of(today, thisWeek, thisMonth), Set.copyOf(ids(top("month", "liked", period, "all"))));
        assertEquals(0, routine(top("month", "liked", period, "all"), today).path("ratingCount").asLong());
    }

    // ---- privacy ----

    @Test
    void privateDeletedAndBannedAuthorsRoutinesAreNeverRanked() throws Exception {
        for (String sort : List.of("overall", "liked", "saved", "rated")) {
            for (String time : List.of("all", "morning", "afternoon", "evening")) {
                List<Long> listed = ids(top("month", sort, hidden, time));
                assertFalse(listed.contains(privateRoutine), sort + " " + time + " " + listed);
                assertFalse(listed.contains(deletedRoutine), sort + " " + time + " " + listed);
                assertFalse(listed.contains(bannedRoutine), sort + " " + time + " " + listed);
            }
        }
        assertEquals(List.of(shownRoutine), ids(top("month", "overall", hidden, "all")));
        assertEquals(List.of(shownRoutine), ids(top("month", "overall", hidden, "morning")));
        String body = get("/top-routines?period=month&category=" + encode(hidden), null).body().toLowerCase();
        assertFalse(body.contains("@audit.test") || body.contains("\"email\"") || body.contains("\"password\""), body);
    }

    @Test
    void aVisitorSeesTheSameLeaderboard() throws Exception {
        for (String time : List.of("all", "morning", "evening")) {
            for (String sort : List.of("overall", "rated")) {
                String path = "/top-routines?period=week&sort=" + sort + "&category=" + encode(times) + "&time=" + time;
                Reply guest = get(path, null);
                assertEquals(200, guest.status(), guest.body());
                assertEquals(guest.body(), get(path, author).body(), path);
            }
        }
        assertEquals(200, get("/top-routines.html", null).status());
    }

    // ---- browser ----

    @Test
    void browserOffersTimeOfDayNextToTheCategory() {
        JsonNode view = page("controls");
        assertEquals(List.of("All categories", "Morning", "Fitness", "Study", "Wellness", "Productivity", "Creators", "Halloween"),
                texts(view.path("categories")), view.toString());
        assertEquals(List.of("All times", "Morning", "Afternoon", "Evening"), texts(view.path("times")), view.toString());
        assertEquals("Category", view.path("categoryLabel").asString(), view.toString());
        assertEquals("Time of day", view.path("timeLabel").asString(), view.toString());
        assertEquals(List.of("Day", "Week", "Month"), texts(view.path("periods")), view.toString());
        assertEquals(List.of("Overall", "Most liked", "Most saved", "Highest rated"), texts(view.path("sorts")), view.toString());
    }

    @Test
    void browserListsWhatTheServerRanks() {
        JsonNode views = page("shown");
        assertTrue(views.size() >= 4, views.toString());
        views.properties().forEach(entry -> {
            JsonNode view = entry.getValue();
            assertEquals(view.path("server"), view.path("cards"), entry.getKey() + " " + view);
            assertTrue(view.path("request").asString().contains(view.path("expect").asString()), entry.getKey() + " " + view);
        });
        List<Long> fitness = new ArrayList<>();
        views.path("fitnessAfternoon").path("cards").forEach(id -> fitness.add(id.asLong()));
        assertTrue(fitness.contains(fitnessAfternoon), views.toString());
        assertTrue(views.path("eveningLiked").path("cards").size() > 0, views.toString());
    }

    @Test
    void browserKeepsTheFiltersInTheAddress() {
        JsonNode view = page("address");
        assertTrue(view.path("afterChoosing").asString().contains("time=evening"), view.toString());
        assertTrue(view.path("afterChoosing").asString().contains("sort=liked"), view.toString());
        assertTrue(view.path("afterChoosing").asString().contains("period=month"), view.toString());
        assertEquals("evening", view.path("restored").path("time").asString(), view.toString());
        assertEquals("liked", view.path("restored").path("sort").asString(), view.toString());
        assertEquals("month", view.path("restored").path("period").asString(), view.toString());
        assertEquals("Fitness", view.path("restored").path("category").asString(), view.toString());
        assertEquals(view.path("restored").path("server"), view.path("restored").path("cards"), view.toString());
        // Anything else in the address is ignored, not trusted.
        assertEquals("week", view.path("unknown").path("period").asString(), view.toString());
        assertEquals("all", view.path("unknown").path("time").asString(), view.toString());
        assertEquals("all", view.path("unknown").path("category").asString(), view.toString());
        assertFalse(view.path("unknown").path("xss").asBoolean(), view.toString());
    }

    @Test
    void browserExplainsAnEmptyLeaderboardAndResetsIt() {
        JsonNode view = page("empty");
        assertEquals("No Creators routines starting in the morning found for this period.", view.path("message").asString(), view.toString());
        assertTrue(view.path("hint").asString().contains("start time"), view.toString());
        assertTrue(view.path("resetVisible").asBoolean(), view.toString());
        JsonNode card = page("emptyLayout");
        assertTrue(card.path("onCard").asBoolean() && card.path("centred").asBoolean(), card.toString());
        assertEquals("rgb(255, 255, 255)", card.path("background").asString(), card.toString());
        assertEquals("all", view.path("afterReset").path("category").asString(), view.toString());
        assertEquals("all", view.path("afterReset").path("time").asString(), view.toString());
        assertEquals("overall", view.path("afterReset").path("sort").asString(), view.toString());
        assertEquals("week", view.path("afterReset").path("period").asString(), view.toString());
        assertEquals("", view.path("afterReset").path("search").asString(), view.toString());
        assertEquals(view.path("afterReset").path("server"), view.path("afterReset").path("cards"), view.toString());
        // The plain list with nothing in it says so without offering a reset.
        assertEquals("No routines found for this period.", view.path("plain").path("message").asString(), view.toString());
        assertFalse(view.path("plain").path("resetVisible").asBoolean(), view.toString());
        assertEquals("No Halloween routines found for this period.", view.path("category").path("message").asString(), view.toString());
        assertEquals("No routines starting in the evening found for this period.", view.path("time").path("message").asString(), view.toString());
    }

    @Test
    void browserKeepsTheNewestChoiceWhenAnOlderAnswerArrivesLate() {
        JsonNode view = page("race");
        assertEquals(view.path("server"), view.path("cards"), view.toString());
        assertEquals("evening", view.path("time").asString(), view.toString());
    }

    @Test
    void browserShowsNoRatingAsNoRatingAndTheStartTime() {
        JsonNode view = page("cards");
        assertEquals("–", view.path("unratedValue").asString(), view.toString());
        assertEquals("No ratings", view.path("unratedLabel").asString(), view.toString());
        assertEquals("4.5", view.path("ratedValue").asString(), view.toString());
        assertEquals("Morning · starts 06:30", view.path("time").asString(), view.toString());
        assertEquals("", view.path("untimed").asString(), view.toString());
        assertFalse(view.path("xss").asBoolean(), view.toString());
        assertTrue(view.path("trickyShown").asBoolean(), view.toString());
        // The author's first letter, escaped once: "<", not "&lt;".
        assertEquals("<", view.path("initial").asString(), view.toString());
    }

    @Test
    void browserKeepsTheLayoutInEveryDesignAndWidth() {
        JsonNode layouts = page("layouts");
        assertEquals(15, layouts.size(), layouts.toString());
        List<String> problems = new ArrayList<>();
        layouts.properties().forEach(entry -> {
            JsonNode view = entry.getValue();
            String name = entry.getKey();
            if (view.path("overflow").asInt() > 0) {
                problems.add(name + " scrolls sideways by " + view.path("overflow").asInt());
            }
            if (!view.path("controlsInside").asBoolean()) {
                problems.add(name + " has a filter outside the screen");
            }
            if (view.path("width").asInt() >= 1280 && !view.path("filtersOneRow").asBoolean()) {
                problems.add(name + " breaks the filters over two rows");
            }
            if (view.path("width").asInt() < 700 && !view.path("selectsSideBySide").asBoolean()) {
                problems.add(name + " stacks the two drop-downs");
            }
            if (view.path("minContrast").asDouble() < 4.5) {
                problems.add(name + " has faint text " + view.path("faintest").asString() + " " + view.path("minContrast").asDouble());
            }
            if (view.path("cards").asInt() == 0) {
                problems.add(name + " shows no routine");
            }
            if (view.path("theme").asString().isEmpty()) {
                problems.add(name + " has no design");
            }
        });
        assertEquals(List.of(), problems, layouts.toString());
        assertEquals("autumn", layouts.path("autumn390").path("theme").asString());
        assertEquals("Halloween", layouts.path("halloween1280").path("category").asString());
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
        author = register("Leader " + m);
        for (int i = 0; i < 6; i++) {
            fans.add(register("Fan " + i + " " + m));
        }
        Account f0 = fans.get(0);
        Account f1 = fans.get(1);
        Account f2 = fans.get(2);

        // Sort orders: scores 51, 49, 34 and 3.
        highRated = routine(author, "Lb high rated " + m, rank, true);
        rate(f0, highRated, 5);
        manyLikes = routine(author, "Lb many likes " + m, rank, true);
        like(f0, manyLikes);
        like(f1, manyLikes);
        like(f2, manyLikes);
        rate(f0, manyLikes, 3);
        manySaves = routine(author, "Lb many saves " + m, rank, true);
        save(f0, manySaves);
        save(f1, manySaves);
        save(f2, manySaves);
        middle = routine(author, "Lb middle " + m, rank, true);
        like(f0, middle);
        save(f0, middle);
        rate(f0, middle, 4);
        rate(f1, middle, 5);

        // Times of day.
        morning = timed("Lb morning " + m, times, List.of("06:30"));
        rate(f0, morning, 4);
        afternoon = timed("Lb afternoon " + m, times, List.of("13:00"));
        evening = timed("Lb evening " + m, times, List.of("19:00"));
        morningAndEvening = timed("Lb both " + m, times, List.of("21:00", "07:15"));
        rate(f0, morningAndEvening, 5);
        lateNight = timed("Lb late night " + m, times, List.of("00:30", "23:30"));
        like(f1, lateNight);
        like(f2, lateNight);
        // The most liked of the group, so that All times lists it whatever the limit of five.
        untimed = timed("Lb untimed " + m, times, List.of(""));
        for (int fan = 1; fan <= 3; fan++) {
            like(fans.get(fan), untimed);
        }
        pausedMorning = timed("Lb paused " + m, times, List.of("06:00", "18:00"));
        like(f1, pausedMorning);
        assertEquals(200, send("POST", "/routines/" + pausedMorning + "/routine-habits/"
                + routineHabitAt(pausedMorning, "06:00") + "/pause", author).status());
        removedMorning = timed("Lb removed " + m, times, List.of("06:00", "14:00"));
        assertEquals(200, send("DELETE", "/routines/" + removedMorning + "/routine-habits/"
                + routineHabitAt(removedMorning, "06:00"), author).status());

        for (String start : List.of("03:59", "04:00", "11:59", "12:00", "16:59", "17:00", "23:59", "00:00")) {
            edge.put(start, timed("Lb edge " + start + " " + m, edges, List.of(start)));
        }

        fitnessAfternoon = timed("Lb fitness afternoon " + m, "Fitness", List.of("15:00"));
        like(f1, fitnessAfternoon);
        like(f2, fitnessAfternoon);
        morningCategoryEvening = timed("Lb morning category " + m, "Morning", List.of("19:30"));
        for (Account fan : fans) {
            like(fan, morningCategoryEvening);
        }
        halloweenRoutine = jdbc.queryForObject(
                "select min(id) from routines where built_in = true and deleted = false and category = 'Halloween'", Long.class);
        like(f2, halloweenRoutine);

        // Changes.
        liveA = routine(author, "Lb live a " + m, live, true);
        like(f0, liveA);
        liveB = routine(author, "Lb live b " + m, live, true);
        like(f0, liveB);
        like(f1, liveB);

        // Day, week and month, by when the like was given.
        today = likedAgo("Lb today " + m, LocalDateTime.now().minusHours(23));
        thisWeek = likedAgo("Lb this week " + m, LocalDateTime.now().minusHours(25));
        thisMonth = likedAgo("Lb this month " + m, LocalDateTime.now().minusDays(8));
        longAgo = likedAgo("Lb long ago " + m, LocalDateTime.now().minusDays(31));

        // Privacy: liked while public, then made private, deleted or its author banned.
        privateRoutine = timed("Lb private " + m, hidden, List.of("08:00"));
        deletedRoutine = timed("Lb deleted " + m, hidden, List.of("08:00"));
        Account banned = register("Banned leader " + m);
        bannedRoutine = routine(banned, "Lb banned " + m, hidden, true);
        habit(banned, bannedRoutine, "Banned step " + m, "08:00");
        shownRoutine = timed("Lb shown " + m, hidden, List.of("08:00"));
        for (long id : List.of(privateRoutine, deletedRoutine, bannedRoutine, shownRoutine)) {
            like(f0, id);
            save(f1, id);
            rate(f2, id, 5);
        }
        jdbc.update("update routines set is_public = false where id = ?", privateRoutine);
        assertEquals(204, send("DELETE", "/routines/" + deletedRoutine, author).status());
        jdbc.update("update users set banned = true where id = ?", banned.id());

        // Ties: three routines with one like each.
        for (int i = 0; i < 3; i++) {
            long id = routine(author, "Lb tie " + i + " " + m, ties, true);
            like(f0, id);
            tied.add(id);
        }
        // An edit moves the first one's row to the end of the table; the order must not follow the table.
        jdbc.update("update routines set description = description || '.' where id = ?", tied.get(0));

        String chrome = findChrome();
        if (chrome == null || !nodeAvailable()) {
            return;
        }

        Map<String, Object> fixtures = new LinkedHashMap<>();
        fixtures.put("base", "http://127.0.0.1:" + port);
        fixtures.put("times", times);
        fixtures.put("fitnessAfternoon", fitnessAfternoon);
        fixtures.put("tricky", TRICKY);

        Path dir = Files.createTempDirectory("leaderboard-browser");
        Files.writeString(dir.resolve("fixtures.json"), json.writeValueAsString(fixtures));
        Process process = new ProcessBuilder(
                "node", "src/test/e2e/routine-leaderboard-check.mjs", chrome,
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

    private JsonNode top(String period, String sort, String category, String time) throws Exception {
        String path = "/top-routines?period=" + period + "&sort=" + sort + "&category=" + encode(category) + "&time=" + time;
        Reply reply = get(path, null);
        assertEquals(200, reply.status(), path + " " + reply.body());
        return json.readTree(reply.body());
    }

    private static List<Long> ids(JsonNode list) {
        List<Long> ids = new ArrayList<>();
        list.forEach(routine -> ids.add(routine.path("id").asLong()));
        return ids;
    }

    private static JsonNode routine(JsonNode list, long id) {
        for (JsonNode routine : list) {
            if (routine.path("id").asLong() == id) {
                return routine;
            }
        }
        throw new AssertionError(id + " is not in " + list);
    }

    private static double score(JsonNode routine) {
        return routine.path("likeCount").asLong() + routine.path("saveCount").asLong()
                + routine.path("ratingCount").asLong() + routine.path("averageRating").asDouble() * 10;
    }

    // Overall order: score, then average, ratings, likes, saves, and the older routine first.
    private static boolean before(JsonNode a, JsonNode b) {
        double[] left = {score(a), a.path("averageRating").asDouble(), a.path("ratingCount").asLong(),
                a.path("likeCount").asLong(), a.path("saveCount").asLong(), -a.path("id").asLong()};
        double[] right = {score(b), b.path("averageRating").asDouble(), b.path("ratingCount").asLong(),
                b.path("likeCount").asLong(), b.path("saveCount").asLong(), -b.path("id").asLong()};
        for (int i = 0; i < left.length; i++) {
            if (left[i] != right[i]) {
                return left[i] > right[i];
            }
        }
        return false;
    }

    private static String textOrNull(JsonNode node) {
        return node.isMissingNode() || node.isNull() ? null : node.asString();
    }

    private static List<String> texts(JsonNode list) {
        List<String> texts = new ArrayList<>();
        list.forEach(item -> texts.add(item.asString()));
        return texts;
    }

    private Account register(String name) throws Exception {
        String email = "leader_" + UUID.randomUUID().toString().substring(0, 12) + "@audit.test";
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("email", email);
        body.put("password", PASSWORD);
        Reply reply = send("POST", "/auth/register", null, body);
        assertEquals(200, reply.status(), reply.body());
        JsonNode node = json.readTree(reply.body());
        return new Account(node.path("user").path("id").asLong(), node.path("token").asString());
    }

    private long routine(Account account, String name, String category, boolean isPublic) throws Exception {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("title", name);
        fields.put("description", "Ranked " + m);
        fields.put("category", category);
        fields.put("durationMinutes", "120");
        fields.put("isPublic", String.valueOf(isPublic));
        Reply reply = form("POST", "/routines", account, fields);
        assertEquals(200, reply.status(), reply.body());
        return json.readTree(reply.body()).path("id").asLong();
    }

    // A public routine by the author with one activity at each start time ("" for none), and one like.
    private long timed(String name, String category, List<String> starts) throws Exception {
        long id = routine(author, name, category, true);
        int step = 0;
        for (String start : starts) {
            habit(author, id, "Step " + step++ + " " + m, start);
        }
        like(fans.get(0), id);
        return id;
    }

    private void habit(Account account, long routineId, String name, String start) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("title", name);
        body.put("durationMinutes", 20);
        if (!start.isEmpty()) {
            body.put("startTime", start);
            body.put("repeatDays", List.of("MON"));
        }
        Reply reply = send("POST", "/routines/" + routineId + "/habits", account, body);
        assertEquals(200, reply.status(), reply.body());
    }

    private long routineHabitAt(long routineId, String start) throws Exception {
        for (JsonNode step : json.readTree(get("/routines/" + routineId + "/routine-habits", author).body())) {
            if (start.equals(step.path("startTime").asString())) {
                return step.path("routineHabitId").asLong();
            }
        }
        throw new AssertionError("no activity at " + start + " in " + routineId);
    }

    // A routine in the period group whose only like was given at the given time.
    private long likedAgo(String name, LocalDateTime when) throws Exception {
        long id = routine(author, name, period, true);
        like(fans.get(0), id);
        jdbc.update("update liked_routines set created_at = ? where routine_id = ?", Timestamp.valueOf(when), id);
        return id;
    }

    private void like(Account account, long routineId) throws Exception {
        Reply reply = send("POST", "/liked-routines/" + routineId, account);
        assertEquals(200, reply.status(), reply.body());
    }

    private int unlike(Account account, long routineId) throws Exception {
        return send("DELETE", "/liked-routines/" + routineId, account).status();
    }

    private void save(Account account, long routineId) throws Exception {
        Reply reply = send("POST", "/saved-routines/" + routineId, account);
        assertEquals(200, reply.status(), reply.body());
    }

    private void rate(Account account, long routineId, int value) throws Exception {
        Reply reply = send("POST", "/routine-ratings/" + routineId, account, Map.of("value", value));
        assertEquals(200, reply.status(), reply.body());
    }

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        assertNotNull(value);
        return value;
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
