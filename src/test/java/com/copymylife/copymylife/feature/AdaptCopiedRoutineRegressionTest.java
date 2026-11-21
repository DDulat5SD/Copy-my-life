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
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * US-19: before saving a copy of someone's public routine, a user changes each activity's start
 * time, duration, repeat days (how often it happens: the number of days a week) and notes.
 * Valid changes are saved in a new private copy of their own, whose length is the total of its
 * activities so it can be started straight away; the source never changes. Invalid values are
 * refused, naming the activity, and nothing at all is saved. The one-click copy stays as it was.
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
class AdaptCopiedRoutineRegressionTest {

    private static final String BOUNDARY = "AdaptCopyBoundary";
    private static final String TRICKY = "<img src=x onerror=\"window.__xss=1\">";
    private static final List<String> WEEKDAYS = List.of("MON", "TUE", "WED", "THU", "FRI");

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

    private JsonNode browser;
    private Account author;
    private Account copier;
    private Account other;
    private long source;
    private List<Long> steps;
    private long shared;
    private List<Long> sharedSteps;
    private long privateRoutine;
    private long deletedRoutine;
    private long elsewhereStep;

    // ---- AC1: valid changes are saved in the copy ----

    @Test
    void theChangedTimeDurationDaysAndNotesAreSavedInTheCopy() throws Exception {
        long copy = adapt(copier, source, List.of(
                activity(steps.get(0), "06:15", 25, List.of("MON", "WED", "FRI"), "Shorter"),
                activity(steps.get(1), "08:00", 30, List.of("SAT", "SUN"), null),
                activity(steps.get(2), null, 5, List.of(), "Any time")));

        JsonNode plan = plan(copier, copy);
        assertEquals(3, plan.size(), plan.toString());
        assertStep(plan.get(0), "Wake up stretch", "06:15", 25, List.of("MON", "WED", "FRI"), "Shorter");
        assertStep(plan.get(1), "Read", "08:00", 30, List.of("SAT", "SUN"), null);
        assertStep(plan.get(2), "Journal " + TRICKY, null, 5, List.of(), "Any time");
    }

    @Test
    void theCopyIsAsLongAsItsActivitiesSoItCanStart() throws Exception {
        long copy = adapt(copier, source, List.of(
                activity(steps.get(0), "06:15", 25, List.of("MON"), null),
                activity(steps.get(1), "08:00", 15, List.of("MON"), null),
                activity(steps.get(2), null, 5, List.of(), null)));
        JsonNode routine = json.readTree(get("/routines/" + copy, copier).body());
        assertEquals(45, routine.path("durationMinutes").asInt(), routine.toString());

        // The untimed activity is planned every day, so today has something to run.
        Reply started = send("POST", "/routine-runs/" + copy + "/start", copier, null);
        assertEquals(200, started.status(), started.body());
        Reply stopped = send("POST", "/routine-runs/" + copy + "/stop", copier, null);
        assertEquals(200, stopped.status(), stopped.body());
    }

    @Test
    void howOftenItHappensIsTheNumberOfRepeatDays() throws Exception {
        long copy = adapt(copier, source, List.of(
                activity(steps.get(0), "06:00", 20, List.of("TUE", "THU"), null),
                activity(steps.get(1), "07:00", 30, List.of("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN"), null),
                activity(steps.get(2), null, 10, List.of(), null)));
        JsonNode plan = plan(copier, copy);
        assertEquals(2, plan.get(0).path("repeatDays").size());
        assertEquals(7, plan.get(1).path("repeatDays").size());
        assertEquals(0, plan.get(2).path("repeatDays").size(), "no days: not tied to a day, so every day");
        // Days come back in week order, whatever order they were sent in.
        long reordered = adapt(copier, source, List.of(
                activity(steps.get(0), "06:00", 20, List.of("FRI", "MON"), null),
                activity(steps.get(1), "07:00", 30, List.of("MON"), null),
                activity(steps.get(2), null, 10, List.of(), null)));
        assertEquals(List.of("MON", "FRI"), texts(plan(copier, reordered).get(0).path("repeatDays")));
    }

    @Test
    void theSourceNeverChanges() throws Exception {
        String before = snapshot(source);
        adapt(copier, source, valid());
        Reply refused = send("POST", "/routines/" + source + "/adapted-copy", copier, Map.of("activities", List.of(
                activity(steps.get(0), "25:00", 20, WEEKDAYS, null),
                activity(steps.get(1), "07:30", 30, WEEKDAYS, null),
                activity(steps.get(2), null, 10, List.of(), null))));
        assertEquals(400, refused.status(), refused.body());
        assertEquals(before, snapshot(source));
    }

    @Test
    void theCopyIsThePrivateOwnRoutineOfWhoeverCopiedIt() throws Exception {
        long copy = adapt(copier, source, valid());
        assertEquals(copier.id(), (long) jdbc.queryForObject("select user_id from routines where id = ?", Long.class, copy));
        assertFalse(jdbc.queryForObject("select is_public from routines where id = ?", Boolean.class, copy));
        assertEquals(0L, (long) jdbc.queryForObject("""
                select count(*) from routine_habits rh join habits h on h.id = rh.habit_id
                where rh.routine_id = ? and (h.user_id <> ? or h.routine_id <> ?)
                """, Long.class, copy, copier.id(), copy), "every habit of the copy is a new one of the copier's");
        JsonNode mine = json.readTree(get("/routines/" + copy, copier).body());
        assertTrue(mine.path("ownedByMe").asBoolean(), mine.toString());
        assertEquals(404, get("/routines/" + copy, other).status());
        assertTrue(ids(json.readTree(get("/routines", copier).body())).contains(copy), "listed in My Routines");
    }

    @Test
    void aHabitListedTwiceStaysOneHabitAndKeepsOneDuration() throws Exception {
        long copy = adapt(copier, shared, List.of(
                activity(sharedSteps.get(0), "06:00", 20, List.of("MON"), null),
                activity(sharedSteps.get(1), "18:00", 20, List.of("MON"), null)));
        assertEquals(1L, (long) jdbc.queryForObject(
                "select count(distinct habit_id) from routine_habits where routine_id = ?", Long.class, copy));
        assertEquals(40, json.readTree(get("/routines/" + copy, copier).body()).path("durationMinutes").asInt());

        Reply differing = send("POST", "/routines/" + shared + "/adapted-copy", copier, Map.of("activities", List.of(
                activity(sharedSteps.get(0), "06:00", 20, List.of("MON"), null),
                activity(sharedSteps.get(1), "18:00", 25, List.of("MON"), null))));
        assertRefused(differing, 2, "same habit");
    }

    @Test
    void laterChangesOnEitherSideStayOnThatSide() throws Exception {
        long copy = adapt(copier, source, valid());
        String sourceBefore = snapshot(source);
        long copyStep = plan(copier, copy).get(0).path("routineHabitId").asLong();
        Reply edited = send("PUT", "/routines/" + copy + "/routine-habits/" + copyStep, copier,
                Map.of("startTime", "05:00", "durationMinutes", 20, "repeatDays", List.of("SUN")));
        assertEquals(200, edited.status(), edited.body());
        assertEquals(sourceBefore, snapshot(source));

        String copyBefore = plan(copier, copy).toString();
        Reply sourceEdit = send("PUT", "/routines/" + source + "/routine-habits/" + steps.get(2), author,
                Map.of("notes", "Changed by the author"));
        assertEquals(200, sourceEdit.status(), sourceEdit.body());
        assertEquals(copyBefore, plan(copier, copy).toString());
        send("PUT", "/routines/" + source + "/routine-habits/" + steps.get(2), author, Map.of("notes", "Write a page"));
    }

    @Test
    void theCopyWorksInTheCalendarWithItsNewTimes() throws Exception {
        long copy = adapt(copier, source, valid());
        LocalDate monday = LocalDate.now(ZoneId.of("Asia/Almaty")).with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
        Reply event = send("POST", "/calendar-events", copier,
                Map.of("routineId", copy, "day", monday.toString(), "time", "06:15", "repeat", "WEEKLY"));
        assertEquals(200, event.status(), event.body());
        Reply week = get("/calendar-events?from=" + monday + "&to=" + monday.plusDays(6), copier);
        assertEquals(200, week.status(), week.body());
        assertTrue(week.body().contains("\"routineId\":" + copy), week.body());
        assertEquals("06:15", plan(copier, copy).get(0).path("startTime").asString());
    }

    @Test
    void theOneClickCopyStaysAsItWas() throws Exception {
        Reply copied = send("POST", "/routines/" + source + "/copy", copier, null);
        assertEquals(200, copied.status(), copied.body());
        long copy = json.readTree(copied.body()).path("id").asLong();
        JsonNode plan = plan(copier, copy);
        assertStep(plan.get(0), "Wake up stretch", "07:00", 20, WEEKDAYS, "Slowly");
        assertStep(plan.get(1), "Read", "07:30", 30, WEEKDAYS, null);
        assertStep(plan.get(2), "Journal " + TRICKY, null, 10, List.of(), "Write a page");
        assertEquals(60, json.readTree(copied.body()).path("durationMinutes").asInt());
    }

    // ---- AC2: invalid values are refused and nothing is saved ----

    @Test
    void anInvalidStartTimeIsRefused() throws Exception {
        for (String time : List.of("25:00", "7am", "07:60", "7:5")) {
            assertRefused(adaptWith(0, time, 20, WEEKDAYS), 1, "Start time");
        }
    }

    @Test
    void anInvalidDurationIsRefused() throws Exception {
        assertRefused(adaptWith(1, "07:30", 0, WEEKDAYS), 2, "greater than 0");
        assertRefused(adaptWith(1, "07:30", -5, WEEKDAYS), 2, "greater than 0");
        assertRefused(adaptWith(1, "07:30", 1441, WEEKDAYS), 2, "at most 1440");
        assertRefused(adaptWith(1, "07:30", null, WEEKDAYS), 2, "greater than 0");
    }

    @Test
    void invalidRepeatDaysAreRefused() throws Exception {
        assertRefused(adaptWith(0, "07:00", 20, List.of("FUNDAY")), 1, "Unknown repeat day");
        assertRefused(adaptWith(0, "07:00", 20, List.of("MON", "MON")), 1, "more than once");
        assertRefused(adaptWith(0, "07:00", 20, List.of()), 1, "repeat day");
    }

    @Test
    void timedActivitiesThatShareMinutesAreRefused() throws Exception {
        Reply overlap = send("POST", "/routines/" + source + "/adapted-copy", copier, Map.of("activities", List.of(
                activity(steps.get(0), "07:00", 30, List.of("MON"), null),
                activity(steps.get(1), "07:15", 30, List.of("MON", "TUE"), null),
                activity(steps.get(2), null, 10, List.of(), null))));
        assertRefused(overlap, 2, "Overlaps");
        // On different days the same minutes are fine.
        adapt(copier, source, List.of(
                activity(steps.get(0), "07:00", 30, List.of("MON"), null),
                activity(steps.get(1), "07:15", 30, List.of("TUE"), null),
                activity(steps.get(2), null, 10, List.of(), null)));
    }

    @Test
    void activitiesLongerThanADayInTotalAreRefused() throws Exception {
        Reply tooLong = send("POST", "/routines/" + source + "/adapted-copy", copier, Map.of("activities", List.of(
                activity(steps.get(0), null, 700, List.of(), null),
                activity(steps.get(1), null, 700, List.of(), null),
                activity(steps.get(2), null, 100, List.of(), null))));
        assertRefused(tooLong, 0, "1500 minutes");
    }

    @Test
    void everyActivityMustBeGivenOnceAndOnlyThoseOfTheRoutine() throws Exception {
        Map<String, Object> first = activity(steps.get(0), "07:00", 20, WEEKDAYS, null);
        Map<String, Object> second = activity(steps.get(1), "07:30", 30, WEEKDAYS, null);
        Map<String, Object> third = activity(steps.get(2), null, 10, List.of(), null);
        assertRefused(send("POST", "/routines/" + source + "/adapted-copy", copier,
                Map.of("activities", List.of(first, second))), 0, "every activity");
        assertRefused(send("POST", "/routines/" + source + "/adapted-copy", copier,
                Map.of("activities", List.of(first, first, third))), 0, "more than once");
        assertRefused(send("POST", "/routines/" + source + "/adapted-copy", copier,
                Map.of("activities", List.of(first, second, activity(elsewhereStep, null, 10, List.of(), null)))), 0, "not part of this routine");
        assertRefused(send("POST", "/routines/" + source + "/adapted-copy", copier, Map.of()), 0, "every activity");
    }

    @Test
    void aRefusedCopyLeavesNothingBehind() throws Exception {
        long[] before = belongings(copier);
        // The first two are fine; the third is not.
        Reply refused = send("POST", "/routines/" + source + "/adapted-copy", copier, Map.of("activities", List.of(
                activity(steps.get(0), "06:00", 20, WEEKDAYS, null),
                activity(steps.get(1), "06:30", 30, WEEKDAYS, null),
                activity(steps.get(2), "06:45", 10, List.of("MON"), null))));
        assertRefused(refused, 3, "Overlaps");
        long[] after = belongings(copier);
        assertEquals(List.of(before[0], before[1], before[2]), List.of(after[0], after[1], after[2]), "no routine, habit or link was kept");
    }

    // ---- who may adapt ----

    @Test
    void aVisitorCannotCopy() throws Exception {
        Reply guest = send("POST", "/routines/" + source + "/adapted-copy", null, Map.of("activities", valid()));
        assertTrue(guest.status() == 401 || guest.status() == 403, guest.status() + " " + guest.body());
    }

    @Test
    void privateDeletedAndUnknownRoutinesCannotBeCopied() throws Exception {
        for (long routine : List.of(privateRoutine, deletedRoutine, 987_654_321L)) {
            Reply reply = send("POST", "/routines/" + routine + "/adapted-copy", copier, Map.of("activities", List.of()));
            assertEquals(404, reply.status(), routine + " " + reply.body());
        }
    }

    @Test
    void nobodyElseCanChangeOrCopyThePersonalCopy() throws Exception {
        long copy = adapt(copier, source, valid());
        String before = plan(copier, copy).toString();
        long step = plan(copier, copy).get(0).path("routineHabitId").asLong();
        Reply edit = send("PUT", "/routines/" + copy + "/routine-habits/" + step, other,
                Map.of("startTime", "05:00", "durationMinutes", 20, "repeatDays", List.of("SUN")));
        assertTrue(edit.status() == 403 || edit.status() == 404, edit.status() + " " + edit.body());
        Reply steal = send("POST", "/routines/" + copy + "/adapted-copy", other, Map.of("activities", List.of()));
        assertEquals(404, steal.status(), steal.body());
        assertEquals(before, plan(copier, copy).toString());
    }

    // ---- browser ----

    @Test
    void browserOffersAdaptNextToCopy() {
        JsonNode view = page("buttons");
        assertTrue(view.path("copyVisible").asBoolean(), view.toString());
        assertTrue(view.path("adaptVisible").asBoolean(), view.toString());
        assertFalse(view.path("ownerAdaptVisible").asBoolean(), "not on your own routine: " + view);
        assertTrue(view.path("guestNotice").asString().contains("Sign in"), view.toString());
    }

    @Test
    void browserShowsEachActivityWithItsCurrentValues() {
        JsonNode form = page("form");
        assertEquals(List.of("Wake up stretch", "Read", "Journal " + TRICKY), texts(form.path("titles")), form.toString());
        assertEquals(List.of("07:00", "07:30", ""), texts(form.path("times")), form.toString());
        assertEquals(List.of("20", "30", "10"), texts(form.path("durations")), form.toString());
        assertEquals("MON,TUE,WED,THU,FRI", form.path("days").get(0).asString(), form.toString());
        assertEquals(List.of("5 days a week", "5 days a week", "Every day"), texts(form.path("frequencies")), form.toString());
        assertEquals("Total 60 min", form.path("total").asString(), form.toString());
        assertFalse(form.path("xss").asBoolean(), form.toString());
    }

    @Test
    void browserCancelSavesNothing() {
        JsonNode view = page("cancel");
        assertCounted(view);
        assertFalse(view.path("modalOpen").asBoolean(), view.toString());
        assertEquals(view.path("routinesBefore").asInt(), view.path("routinesAfter").asInt(), view.toString());
    }

    @Test
    void browserExplainsInvalidValuesAndKeepsWhatWasTyped() {
        JsonNode local = page("invalidLocal");
        assertCounted(local);
        assertTrue(local.path("error").asString().contains("Wake up stretch"), local.toString());
        assertTrue(local.path("error").asString().toLowerCase().contains("duration"), local.toString());
        assertEquals(1, local.path("highlighted").asInt(), local.toString());
        assertEquals("05:45", local.path("keptTime").asString(), "what was typed stays: " + local);
        assertEquals(local.path("routinesBefore").asInt(), local.path("routinesAfter").asInt(), local.toString());

        JsonNode server = page("invalidServer");
        assertCounted(server);
        assertTrue(server.path("error").asString().contains("Overlaps"), server.toString());
        assertEquals(2, server.path("highlighted").asInt(), server.toString());
        assertTrue(server.path("modalOpen").asBoolean(), server.toString());
        assertEquals(server.path("routinesBefore").asInt(), server.path("routinesAfter").asInt(), server.toString());
    }

    @Test
    void browserSavesOneAdaptedCopyAndOpensIt() {
        JsonNode view = page("save");
        assertCounted(view);
        assertEquals(view.path("routinesBefore").asInt() + 1, view.path("routinesAfter").asInt(), "one copy for a double click: " + view);
        assertTrue(view.path("notice").asString().contains("adapted copy"), view.toString());
        assertTrue(view.path("openHref").asString().startsWith("routine.html?id="), view.toString());
        JsonNode first = view.path("copyPlan").get(0);
        assertEquals("06:15", first.path("startTime").asString(), view.toString());
        assertEquals(25, first.path("durationMinutes").asInt(), view.toString());
        assertEquals(List.of("MON", "WED", "FRI"), texts(first.path("repeatDays")), view.toString());
        assertEquals(view.path("sourceBefore"), view.path("sourceAfter"), "the source did not change");
        assertTrue(view.path("copyPageTitle").asString().contains("Morning base"), view.toString());
    }

    @Test
    void browserKeepsTheLayoutInEveryDesignAndWidth() {
        JsonNode layouts = page("layouts");
        assertEquals(10, layouts.size(), layouts.toString());
        List<String> problems = new ArrayList<>();
        layouts.properties().forEach(entry -> {
            JsonNode view = entry.getValue();
            String name = entry.getKey();
            if (view.path("overflow").asInt() > 0) {
                problems.add(name + " scrolls sideways by " + view.path("overflow").asInt());
            }
            if (!view.path("inside").asBoolean()) {
                problems.add(name + " has form parts outside the screen: " + view.path("outside").asString());
            }
            if (!view.path("actionsVisible").asBoolean()) {
                problems.add(name + " hides Save copy or Cancel");
            }
            if (view.path("minContrast").asDouble() < 4.5) {
                problems.add(name + " has faint text " + view.path("faintest").asString() + " " + view.path("minContrast").asDouble());
            }
        });
        assertEquals(List.of(), problems, layouts.toString());
        assertEquals("autumn", layouts.path("autumn390").path("theme").asString());
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
        author = register("Base author");
        copier = register("Copier");
        other = register("Someone else");

        source = routine(author, "Morning base", 60, true);
        habit(author, source, "Wake up stretch", 20, "07:00", WEEKDAYS, "Slowly");
        habit(author, source, "Read", 30, "07:30", WEEKDAYS, null);
        habit(author, source, "Journal " + TRICKY, 10, null, null, "Write a page");
        steps = links(source);

        // One habit listed twice.
        shared = routine(author, "Twice a day", 30, true);
        habit(author, shared, "Water the plants", 15, null, null, null);
        long plants = jdbc.queryForObject("select habit_id from routine_habits where routine_id = ?", Long.class, shared);
        Reply again = send("POST", "/routines/" + shared + "/routine-habits/" + plants, author, null);
        assertEquals(200, again.status(), again.body());
        sharedSteps = links(shared);

        privateRoutine = routine(author, "Not shared", 10, false);
        habit(author, privateRoutine, "Secret step", 10, null, null, null);
        deletedRoutine = routine(author, "Gone soon", 10, true);
        habit(author, deletedRoutine, "Gone step", 10, null, null, null);
        assertEquals(204, send("DELETE", "/routines/" + deletedRoutine, author, null).status());
        elsewhereStep = links(privateRoutine).get(0);

        String chrome = findChrome();
        if (chrome == null || !nodeAvailable()) {
            return;
        }

        Map<String, Object> fixtures = new LinkedHashMap<>();
        fixtures.put("base", "http://127.0.0.1:" + port);
        fixtures.put("copierToken", copier.token());
        fixtures.put("authorToken", author.token());
        fixtures.put("source", source);

        Path dir = Files.createTempDirectory("adapt-copy-browser");
        Files.writeString(dir.resolve("fixtures.json"), json.writeValueAsString(fixtures));
        Process process = new ProcessBuilder(
                "node", "src/test/e2e/adapt-copy-check.mjs", chrome,
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

    // The routine counts were really read, so comparing them proves something.
    private static void assertCounted(JsonNode view) {
        assertTrue(view.path("routinesBefore").isNumber() && view.path("routinesAfter").isNumber(), view.toString());
    }

    private JsonNode page(String name) {
        Assumptions.assumeTrue(browser != null, "Chrome or node not found");
        return browser.path(name);
    }

    // ---- helpers ----

    private List<Map<String, Object>> valid() {
        return List.of(
                activity(steps.get(0), "06:15", 25, List.of("MON", "WED", "FRI"), null),
                activity(steps.get(1), "07:00", 30, WEEKDAYS, null),
                activity(steps.get(2), null, 10, List.of(), null));
    }

    // The source's three activities with one of them changed.
    private Reply adaptWith(int index, String time, Integer minutes, List<String> days) throws Exception {
        List<Map<String, Object>> activities = new ArrayList<>(List.of(
                activity(steps.get(0), "07:00", 20, WEEKDAYS, null),
                activity(steps.get(1), "07:30", 30, WEEKDAYS, null),
                activity(steps.get(2), null, 10, List.of(), null)));
        activities.set(index, activity(steps.get(index), time, minutes, days, null));
        return send("POST", "/routines/" + source + "/adapted-copy", copier, Map.of("activities", activities));
    }

    private static Map<String, Object> activity(long step, String time, Integer minutes, List<String> days, String notes) {
        Map<String, Object> activity = new HashMap<>();
        activity.put("routineHabitId", step);
        activity.put("startTime", time);
        activity.put("durationMinutes", minutes);
        activity.put("repeatDays", days);
        activity.put("notes", notes);
        return activity;
    }

    private long adapt(Account account, long routine, List<Map<String, Object>> activities) throws Exception {
        Reply reply = send("POST", "/routines/" + routine + "/adapted-copy", account, Map.of("activities", activities));
        assertEquals(200, reply.status(), reply.body());
        long copy = json.readTree(reply.body()).path("id").asLong();
        assertNotEquals(routine, copy);
        return copy;
    }

    // 400, a message naming the problem and, when one activity is at fault, which one (1-based).
    private void assertRefused(Reply reply, int activity, String words) throws Exception {
        assertEquals(400, reply.status(), reply.body());
        JsonNode body = json.readTree(reply.body());
        assertTrue(body.path("message").asString().toLowerCase().contains(words.toLowerCase()), words + " in " + reply.body());
        if (activity > 0) {
            assertEquals(activity, body.path("activity").asInt(), reply.body());
        }
    }

    private void assertStep(JsonNode step, String title, String time, int minutes, List<String> days, String notes) {
        assertEquals(title, step.path("title").asString(), step.toString());
        assertEquals(time, step.path("startTime").isNull() || step.path("startTime").isMissingNode() ? null : step.path("startTime").asString(), step.toString());
        assertEquals(minutes, step.path("durationMinutes").asInt(), step.toString());
        assertEquals(days, texts(step.path("repeatDays")), step.toString());
        assertEquals(notes, step.path("notes").isNull() || step.path("notes").isMissingNode() ? null : step.path("notes").asString(), step.toString());
    }

    private JsonNode plan(Account account, long routine) throws Exception {
        Reply reply = get("/routines/" + routine + "/routine-habits", account);
        assertEquals(200, reply.status(), reply.body());
        return json.readTree(reply.body());
    }

    // Everything stored for the routine and its activities, to compare before and after.
    private String snapshot(long routine) {
        return jdbc.queryForList("""
                select title, description, category, duration_minutes, is_public, deleted, user_id from routines where id = ?
                """, routine) + " " + jdbc.queryForList("""
                select rh.id, rh.habit_id, rh.position, rh.start_time, rh.repeat_days, rh.notes, rh.paused_at, rh.removed_at,
                       h.title, h.duration_minutes, h.user_id
                from routine_habits rh join habits h on h.id = rh.habit_id
                where rh.routine_id = ? order by rh.id
                """, routine) + " " + jdbc.queryForObject(
                "select count(*) from habit_completions hc join routine_habits rh on rh.id = hc.routine_habit_id where rh.routine_id = ?",
                Long.class, routine);
    }

    // [routines, habits, routine links] of a user.
    private long[] belongings(Account account) {
        return new long[]{
                jdbc.queryForObject("select count(*) from routines where user_id = ?", Long.class, account.id()),
                jdbc.queryForObject("select count(*) from habits where user_id = ?", Long.class, account.id()),
                jdbc.queryForObject("select count(*) from routine_habits rh join routines r on r.id = rh.routine_id where r.user_id = ?",
                        Long.class, account.id())
        };
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

    private static List<String> texts(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.asString()));
        return values;
    }

    private Account register(String name) throws Exception {
        Reply reply = send("POST", "/auth/register", null, Map.of(
                "name", name,
                "email", "adapt_" + UUID.randomUUID().toString().substring(0, 12) + "@audit.test",
                "password", "Audit#12345"
        ));
        assertEquals(200, reply.status(), reply.body());
        JsonNode node = json.readTree(reply.body());
        return new Account(node.path("user").path("id").asLong(), node.path("token").asString());
    }

    private long routine(Account owner, String title, int minutes, boolean isPublic) throws Exception {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("title", title);
        fields.put("description", "Adapt check");
        fields.put("category", "Morning");
        fields.put("durationMinutes", String.valueOf(minutes));
        fields.put("isPublic", String.valueOf(isPublic));

        StringBuilder body = new StringBuilder();
        fields.forEach((key, value) -> body.append("--").append(BOUNDARY).append("\r\n")
                .append("Content-Disposition: form-data; name=\"").append(key).append("\"\r\n\r\n")
                .append(value).append("\r\n"));
        body.append("--").append(BOUNDARY).append("--\r\n");

        HttpResponse<String> response = http.send(HttpRequest.newBuilder(uri("/routines"))
                .header("Authorization", "Bearer " + owner.token())
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return json.readTree(response.body()).path("id").asLong();
    }

    private void habit(Account owner, long routine, String title, int minutes, String time, List<String> days, String notes) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("title", title);
        body.put("description", title + " description");
        body.put("durationMinutes", minutes);
        if (time != null) {
            body.put("startTime", time);
            body.put("repeatDays", days);
        }
        if (notes != null) {
            body.put("notes", notes);
        }
        Reply reply = send("POST", "/routines/" + routine + "/habits", owner, body);
        assertEquals(200, reply.status(), reply.body());
    }

    private Reply get(String path, Account account) throws Exception {
        return send("GET", path, account, null);
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
