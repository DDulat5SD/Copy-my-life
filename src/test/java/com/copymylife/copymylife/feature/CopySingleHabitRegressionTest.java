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
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * US-18: a user copies one activity of someone's public routine into one of their own routines.
 * Only that activity is added, as a new habit of theirs with the same plan, so later edits on
 * either side stay on that side; no routine is created and the source never changes. The target
 * must have room for it and no timed activity sharing its minutes; a refused copy, a cancelled
 * one or a failure while saving leaves nothing behind.
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
class CopySingleHabitRegressionTest {

    private static final String BOUNDARY = "CopyHabitBoundary";
    private static final String TRICKY = "<img src=x onerror=\"window.__xss=1\">";
    private static final List<String> WEEKDAYS = List.of("MON", "TUE", "WED", "THU", "FRI");
    private static final List<String> MWF = List.of("MON", "WED", "FRI");

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
    private Account empty;
    private long source;
    // Wake Up, Morning Exercise, Reading, Breakfast, by routine habit id.
    private List<Long> steps;
    private long shared;
    private List<Long> sharedSteps;
    private long privateRoutine;
    private long deletedRoutine;
    private long trimmed;
    private long removedStep;
    private long othersRoutine;
    private long browserTarget;
    private long busyTarget;

    // ---- AC1: the chosen habit is added to the chosen routine, and nothing else is copied ----

    @Test
    void theChosenHabitIsAddedToTheChosenRoutine() throws Exception {
        long target = target("Chosen routine", 120);
        habit(copier, target, "Make coffee", 10, "05:30", WEEKDAYS, null);

        JsonNode reply = copy(copier, steps.get(1), target);

        assertEquals(2, reply.size(), reply.toString());
        assertEquals("Make coffee", reply.get(0).path("title").asString());
        assertStep(reply.get(1), "Morning Exercise", "06:30", 25, MWF, "Stretch first");
        assertEquals("06:55", reply.get(1).path("endTime").asString(), reply.toString());
        assertEquals(reply.toString(), plan(copier, target).toString(), "the answer is the routine's activities");
    }

    @Test
    void onlyThatActivityIsCopiedAndNoRoutineIsCreated() throws Exception {
        long target = target("Only one", 120);
        long[] before = belongings(copier);

        copy(copier, steps.get(1), target);

        long[] after = belongings(copier);
        assertEquals(before[0], after[0], "no routine was created");
        assertEquals(before[1] + 1, after[1], "one habit");
        assertEquals(before[2] + 1, after[2], "one activity");
        assertEquals(List.of("Morning Exercise"), texts(plan(copier, target), "title"));
        assertEquals(List.of("Morning Exercise"), jdbc.queryForList(
                "select title from habits where routine_id = ?", String.class, target), "the other three stayed behind");
    }

    @Test
    void startTimeDurationRepeatDaysNotesAndDescriptionAreKept() throws Exception {
        long target = target("Kept plan", 120);
        long added = lastStep(copy(copier, steps.get(0), target));

        assertStep(plan(copier, target).get(0), "Wake Up", "06:00", 15, WEEKDAYS, "Open the curtains");
        assertEquals("Wake Up description", jdbc.queryForObject(
                "select h.description from routine_habits rh join habits h on h.id = rh.habit_id where rh.id = ?",
                String.class, added));
        // Untimed and not tied to a day stays so.
        long untimed = target("Untimed", 60);
        copy(copier, steps.get(3), untimed);
        assertStep(plan(copier, untimed).get(0), "Breakfast " + TRICKY, null, 20, List.of(), null);
    }

    @Test
    void theCopyIsANewHabitOfTheCopiersInTheTarget() throws Exception {
        long target = target("Own habit", 120);
        long added = lastStep(copy(copier, steps.get(1), target, Map.of("userId", other.id(), "ownerId", other.id())));

        Map<String, Object> row = jdbc.queryForMap("""
                select h.id as habit, h.user_id as owner, h.routine_id as routine, rh.paused_at as paused
                from routine_habits rh join habits h on h.id = rh.habit_id where rh.id = ?
                """, added);
        long sourceHabit = jdbc.queryForObject("select habit_id from routine_habits where id = ?", Long.class, steps.get(1));
        assertNotEquals(sourceHabit, ((Number) row.get("habit")).longValue(), "a new habit, not the author's");
        assertEquals(copier.id(), ((Number) row.get("owner")).longValue(), "an owner sent in the body is ignored");
        assertEquals(target, ((Number) row.get("routine")).longValue());
        assertEquals(null, row.get("paused"));
    }

    @Test
    void theSourceNeverChanges() throws Exception {
        String before = snapshot(source);
        long target = target("Leaves the source", 120);
        copy(copier, steps.get(1), target);
        assertEquals(400, send("POST", copyPath(source, steps.get(1)), copier, Map.of("targetRoutineId", target)).status());
        assertEquals(before, snapshot(source));
    }

    @Test
    void editingTheCopyLeavesTheOriginalAndTheOtherWayRound() throws Exception {
        long target = target("Edited later", 120);
        long added = lastStep(copy(copier, steps.get(2), target));
        String sourceBefore = snapshot(source);

        Reply edited = send("PUT", "/routines/" + target + "/routine-habits/" + added, copier,
                Map.of("startTime", "21:00", "durationMinutes", 40, "repeatDays", List.of("SUN"), "notes", "At night"));
        assertEquals(200, edited.status(), edited.body());
        long copiedHabit = jdbc.queryForObject("select habit_id from routine_habits where id = ?", Long.class, added);
        Reply renamed = send("PUT", "/routines/" + target + "/habits/" + copiedHabit, copier,
                Map.of("title", "Reading at night", "description", "Mine", "durationMinutes", 40));
        assertEquals(200, renamed.status(), renamed.body());
        assertEquals(sourceBefore, snapshot(source), "the original did not follow the copy");

        String copyBefore = plan(copier, target).toString();
        Reply sourceEdit = send("PUT", "/routines/" + source + "/routine-habits/" + steps.get(2), author,
                Map.of("startTime", "07:00", "repeatDays", WEEKDAYS, "notes", "Changed by the author"));
        assertEquals(200, sourceEdit.status(), sourceEdit.body());
        assertEquals(copyBefore, plan(copier, target).toString(), "the copy did not follow the original");
        send("PUT", "/routines/" + source + "/routine-habits/" + steps.get(2), author,
                Map.of("startTime", "07:00", "repeatDays", WEEKDAYS));
    }

    @Test
    void oneActivityOfAHabitListedTwiceIsCopiedOnItsOwn() throws Exception {
        long target = target("Twice in the source", 60);
        JsonNode reply = copy(copier, shared, sharedSteps.get(1), target);
        assertEquals(1, reply.size(), reply.toString());
        assertEquals("Water the plants", reply.get(0).path("title").asString());
        assertEquals(15, reply.get(0).path("durationMinutes").asInt());
        assertEquals(1L, (long) jdbc.queryForObject(
                "select count(*) from routine_habits where routine_id = ?", Long.class, target));
    }

    @Test
    void aPausedSourceActivityIsCopiedAndTheCopyIsNotPaused() throws Exception {
        long paused = routine(author, "Paused by its author", 30, true);
        habit(author, paused, "Quiet minute", 10, null, null, null);
        long step = links(paused).get(0);
        assertEquals(200, send("POST", "/routines/" + paused + "/routine-habits/" + step + "/pause", author, null).status());

        JsonNode reply = copy(copier, paused, step, target("From a paused one", 30));
        assertFalse(reply.get(0).path("paused").asBoolean(), reply.toString());
    }

    @Test
    void theFullCopiesStayAsTheyWere() throws Exception {
        Reply full = send("POST", "/routines/" + source + "/copy", copier, null);
        assertEquals(200, full.status(), full.body());
        assertEquals(4, plan(copier, json.readTree(full.body()).path("id").asLong()).size());
    }

    // ---- the target routine ----

    @Test
    void aCopyThatDoesNotFitInTheTargetIsRefused() throws Exception {
        long target = target("Almost full", 30);
        habit(copier, target, "Make coffee", 20, null, null, null);
        String before = plan(copier, target).toString();

        assertRefused(send("POST", copyPath(source, steps.get(2)), copier, Map.of("targetRoutineId", target)),
                400, "Only 10 minutes remaining");
        assertEquals(before, plan(copier, target).toString());
        assertEquals(30, routineMinutes(target), "the target keeps its length");

        // Exactly enough room is enough.
        long roomy = target("Just enough", 45);
        habit(copier, roomy, "Make coffee", 20, null, null, null);
        copy(copier, steps.get(1), roomy);
        assertEquals(45, routineMinutes(roomy));
    }

    @Test
    void aCopySharingMinutesWithATimedActivityIsRefused() throws Exception {
        long target = target("Busy Wednesday", 120);
        habit(copier, target, "Make coffee", 10, "06:40", List.of("WED"), null);
        long[] before = belongings(copier);

        assertRefused(send("POST", copyPath(source, steps.get(1)), copier, Map.of("targetRoutineId", target)),
                400, "Overlaps with \"Make coffee\"");
        long[] after = belongings(copier);
        assertEquals(List.of(before[0], before[1], before[2]), List.of(after[0], after[1], after[2]), "nothing was added");

        // The same minutes on another day are fine.
        long tuesday = target("Busy Tuesday", 120);
        habit(copier, tuesday, "Make coffee", 10, "06:40", List.of("TUE"), null);
        copy(copier, steps.get(1), tuesday);
        // Copying the same timed habit again would share its own minutes.
        assertRefused(send("POST", copyPath(source, steps.get(1)), copier, Map.of("targetRoutineId", tuesday)),
                400, "Overlaps with \"Morning Exercise\"");
    }

    @Test
    void theCopyGoesAfterEveryRowOfTheTargetRemovedOnesIncluded() throws Exception {
        long target = target("With history", 120);
        habit(copier, target, "First", 10, null, null, null);
        habit(copier, target, "Second", 10, null, null, null);
        habit(copier, target, "Removed", 10, null, null, null);
        habit(copier, target, "Removed long ago", 10, null, null, null);
        List<Long> rows = links(target);
        // As US-09 removes a tracked habit, and as a row removed before that kept its place.
        jdbc.update("update routine_habits set removed_at = now(), position = -id where id = ?", rows.get(2));
        jdbc.update("update routine_habits set removed_at = now() where id = ?", rows.get(3));

        JsonNode reply = copy(copier, steps.get(3), target);

        assertEquals(List.of("First", "Second", "Breakfast " + TRICKY), texts(reply, "title"));
        List<Integer> positions = jdbc.queryForList(
                "select position from routine_habits where routine_id = ? order by position", Integer.class, target);
        assertEquals(positions.size(), positions.stream().distinct().count(), positions.toString());
        assertEquals(4, (int) jdbc.queryForObject("""
                select rh.position from routine_habits rh join habits h on h.id = rh.habit_id
                where rh.routine_id = ? and h.title = ?
                """, Integer.class, target, "Breakfast " + TRICKY), "after the removed row at 3");
        assertEquals(2, jdbc.queryForObject(
                "select count(*) from routine_habits where routine_id = ? and removed_at is not null", Integer.class, target),
                "removed rows keep their history");
    }

    @Test
    void aRunningTargetIsNotChanged() throws Exception {
        long target = target("Running now", 10);
        habit(copier, target, "Make coffee", 10, null, null, null);
        assertEquals(200, send("POST", "/routine-runs/" + target + "/start", copier, null).status());
        try {
            Reply reply = send("POST", copyPath(source, steps.get(3)), copier, Map.of("targetRoutineId", target));
            assertEquals(409, reply.status(), reply.body());
            assertEquals(1, plan(copier, target).size());
        } finally {
            send("POST", "/routine-runs/" + target + "/stop", copier, null);
        }
    }

    @Test
    void theCopyIsListedInMyRoutinesAndCanBeEditedAndRemoved() throws Exception {
        long target = target("Listed", 120);
        int routinesBefore = json.readTree(get("/routines", copier).body()).size();
        long added = lastStep(copy(copier, steps.get(1), target));

        JsonNode mine = json.readTree(get("/routines", copier).body());
        assertEquals(routinesBefore, mine.size(), "My Routines has no new routine");
        assertTrue(ids(mine).contains(target));
        assertEquals(120, routineMinutes(target));
        JsonNode details = json.readTree(get("/routines/" + target, copier).body());
        assertTrue(details.path("ownedByMe").asBoolean(), details.toString());

        Reply edited = send("PUT", "/routines/" + target + "/routine-habits/" + added, copier,
                Map.of("startTime", "09:00", "repeatDays", List.of("SAT")));
        assertEquals(200, edited.status(), edited.body());
        Reply removed = send("DELETE", "/routines/" + target + "/routine-habits/" + added, copier, null);
        assertEquals(200, removed.status(), removed.body());
        assertEquals(0, plan(copier, target).size());
    }

    @Test
    void theCopyRunsTodayAndWorksInTheCalendar() throws Exception {
        // A run needs the activities to fill the routine exactly.
        long target = target("Runs today", 20);
        long added = lastStep(copy(copier, steps.get(3), target));

        Reply started = send("POST", "/routine-runs/" + target + "/start", copier, null);
        assertEquals(200, started.status(), started.body());
        List<Long> running = new ArrayList<>();
        json.readTree(started.body()).path("habits").forEach(item -> running.add(item.path("routineHabitId").asLong()));
        assertEquals(List.of(added), running, "the untimed copy is planned every day");
        assertEquals(200, send("POST", "/routine-runs/" + target + "/stop", copier, null).status());
        assertEquals(200, get("/progress", copier).status());

        long timed = target("In the calendar", 60);
        copy(copier, steps.get(1), timed);
        LocalDate monday = LocalDate.now(ZoneId.of("Asia/Almaty")).with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
        Reply event = send("POST", "/calendar-events", copier,
                Map.of("routineId", timed, "day", monday.toString(), "time", "06:30", "repeat", "WEEKLY"));
        assertEquals(200, event.status(), event.body());
        Reply week = get("/calendar-events?from=" + monday + "&to=" + monday.plusDays(6), copier);
        assertTrue(week.body().contains("\"routineId\":" + timed), week.body());
        JsonNode step = plan(copier, timed).get(0);
        assertEquals("06:30", step.path("startTime").asString());
        assertEquals(MWF, texts(step.path("repeatDays")));
    }

    @Test
    void theTitleIsKeptAsPlainText() throws Exception {
        long target = target("Tricky title", 60);
        long added = lastStep(copy(copier, steps.get(3), target));
        assertEquals("Breakfast " + TRICKY, plan(copier, target).get(0).path("title").asString());
        assertEquals("Breakfast " + TRICKY, jdbc.queryForObject(
                "select h.title from routine_habits rh join habits h on h.id = rh.habit_id where rh.id = ?", String.class, added),
                "stored as typed; the pages escape it when they show it");
    }

    // ---- AC2 and refusals: nothing is added ----

    @Test
    void aCopyWithoutAChosenRoutineAddsNothing() throws Exception {
        long[] before = belongings(copier);
        assertRefused(send("POST", copyPath(source, steps.get(1)), copier, null), 400, "Choose one of your routines");
        assertRefused(send("POST", copyPath(source, steps.get(1)), copier, Map.of()), 400, "Choose one of your routines");
        assertEquals(before[1], belongings(copier)[1]);
        assertEquals(before[2], belongings(copier)[2]);
    }

    @Test
    void aFailureWhileSavingKeepsNothing() throws Exception {
        long target = target("Fails while saving", 120);
        long[] before = belongings(copier);
        jdbc.execute("""
                create or replace function copy_habit_test_fail() returns trigger language plpgsql as $$
                begin raise exception 'copy habit test failure'; end $$
                """);
        jdbc.execute("create trigger copy_habit_test_fail before insert on routine_habits for each row when (new.routine_id = "
                + target + ") execute function copy_habit_test_fail()");
        try {
            Reply reply = send("POST", copyPath(source, steps.get(1)), copier, Map.of("targetRoutineId", target));
            assertTrue(reply.status() >= 500, reply.status() + " " + reply.body());
        } finally {
            jdbc.execute("drop trigger if exists copy_habit_test_fail on routine_habits");
            jdbc.execute("drop function if exists copy_habit_test_fail()");
        }
        long[] after = belongings(copier);
        assertEquals(List.of(before[0], before[1], before[2]), List.of(after[0], after[1], after[2]),
                "the new habit was rolled back with its activity");
        copy(copier, steps.get(1), target);
    }

    @Test
    void twoRequestsAtOnceNeverOverfillTheTarget() throws Exception {
        long target = target("Room for one", 20);
        Map<String, Object> body = Map.of("targetRoutineId", target);
        CompletableFuture<HttpResponse<String>> first = sendAsync(copyPath(source, steps.get(3)), copier, body);
        CompletableFuture<HttpResponse<String>> second = sendAsync(copyPath(source, steps.get(3)), copier, body);
        List<Integer> statuses = new ArrayList<>(List.of(first.get(30, TimeUnit.SECONDS).statusCode(),
                second.get(30, TimeUnit.SECONDS).statusCode()));
        statuses.sort(Integer::compareTo);
        assertEquals(List.of(200, 400), statuses);
        assertEquals(1, plan(copier, target).size());
    }

    @Test
    void sourceDataThatCouldNotBeSavedTodayIsRefused() throws Exception {
        long broken = routine(author, "Old data", 60, true);
        habit(author, broken, "No minutes", 10, null, null, null);
        habit(author, broken, "No days", 10, "09:00", List.of("MON"), null);
        List<Long> rows = links(broken);
        jdbc.update("update habits set duration_minutes = 0 where id = (select habit_id from routine_habits where id = ?)", rows.get(0));
        jdbc.update("update routine_habits set repeat_days = null where id = ?", rows.get(1));
        long target = target("Not for old data", 60);

        assertRefused(send("POST", copyPath(broken, rows.get(0)), copier, Map.of("targetRoutineId", target)),
                400, "can't be copied: Habit duration must be greater than 0");
        assertRefused(send("POST", copyPath(broken, rows.get(1)), copier, Map.of("targetRoutineId", target)),
                400, "can't be copied: Choose at least one repeat day");
        assertEquals(0, plan(copier, target).size());
    }

    // ---- who may copy, and from where to where ----

    @Test
    void aVisitorOrABadTokenCannotCopy() throws Exception {
        long target = target("Not for visitors", 120);
        for (Account account : Arrays.asList(null, new Account(0, "not-a-token"))) {
            Reply reply = send("POST", copyPath(source, steps.get(1)), account, Map.of("targetRoutineId", target));
            assertTrue(reply.status() == 401 || reply.status() == 403, reply.status() + " " + reply.body());
        }
        assertEquals(0, plan(copier, target).size());
    }

    @Test
    void aBannedUserCannotCopy() throws Exception {
        Account banned = register("Banned copier");
        long target = routine(banned, "Banned target", 60, false);
        jdbc.update("update users set banned = true where id = ?", banned.id());
        Reply reply = send("POST", copyPath(source, steps.get(3)), banned, Map.of("targetRoutineId", target));
        assertTrue(reply.status() == 401 || reply.status() == 403, reply.status() + " " + reply.body());
        assertEquals(0L, (long) jdbc.queryForObject("select count(*) from routine_habits where routine_id = ?", Long.class, target));
    }

    @Test
    void aBannedAuthorsRoutineFollowsTheSameRuleAsCopyingTheWholeRoutine() throws Exception {
        Account bannedAuthor = register("Banned author");
        long theirs = routine(bannedAuthor, "From a banned author", 30, true);
        habit(bannedAuthor, theirs, "Their habit", 10, null, null, null);
        jdbc.update("update users set banned = true where id = ?", bannedAuthor.id());

        int whole = send("POST", "/routines/" + theirs + "/copy", copier, null).status();
        int one = send("POST", copyPath(theirs, links(theirs).get(0)), copier,
                Map.of("targetRoutineId", target("From banned", 30))).status();
        assertEquals(whole, one, "copying one habit is allowed exactly where copying the routine is");
    }

    @Test
    void privateDeletedAndUnknownSourcesAreRefused() throws Exception {
        long target = target("Nothing from these", 120);
        long privateStep = links(privateRoutine).get(0);
        long deletedStep = jdbc.queryForObject("select id from routine_habits where routine_id = ?", Long.class, deletedRoutine);
        long[] before = belongings(copier);

        assertRefused(send("POST", copyPath(privateRoutine, privateStep), copier, Map.of("targetRoutineId", target)), 404, "Routine not found");
        assertRefused(send("POST", copyPath(deletedRoutine, deletedStep), copier, Map.of("targetRoutineId", target)), 404, "Routine not found");
        assertRefused(send("POST", copyPath(987_654_321L, steps.get(1)), copier, Map.of("targetRoutineId", target)), 404, "Routine not found");
        // A private routine answers exactly like one that does not exist.
        assertEquals(send("POST", copyPath(987_654_321L, privateStep), copier, Map.of("targetRoutineId", target)).body(),
                send("POST", copyPath(privateRoutine, privateStep), copier, Map.of("targetRoutineId", target)).body());
        assertEquals(before[2], belongings(copier)[2]);
    }

    @Test
    void aRemovedOrForeignActivityIsRefused() throws Exception {
        long target = target("Only real activities", 120);
        long privateStep = links(privateRoutine).get(0);

        assertRefused(send("POST", copyPath(trimmed, removedStep), copier, Map.of("targetRoutineId", target)), 404, "Activity not found");
        // Activities of other routines, the private one's too, are not reached through a public routine.
        assertRefused(send("POST", copyPath(source, sharedSteps.get(0)), copier, Map.of("targetRoutineId", target)), 404, "Activity not found");
        assertRefused(send("POST", copyPath(source, privateStep), copier, Map.of("targetRoutineId", target)), 404, "Activity not found");
        assertRefused(send("POST", copyPath(source, 987_654_321L), copier, Map.of("targetRoutineId", target)), 404, "Activity not found");
        assertEquals(0, plan(copier, target).size());
    }

    @Test
    void onlyTheCopiersOwnLiveRoutinesCanReceiveIt() throws Exception {
        String othersBefore = snapshot(othersRoutine);
        long gone = target("Deleted target", 120);
        assertEquals(204, send("DELETE", "/routines/" + gone, copier, null).status());

        for (long target : List.of(othersRoutine, gone, 987_654_321L, -1L)) {
            assertRefused(send("POST", copyPath(source, steps.get(1)), copier, Map.of("targetRoutineId", target)),
                    404, "Target routine not found");
        }
        assertEquals(othersBefore, snapshot(othersRoutine), "someone else's routine did not change");
        assertEquals(0L, (long) jdbc.queryForObject("select count(*) from routine_habits where routine_id = ?", Long.class, gone));
    }

    @Test
    void malformedRequestsAreRefused() throws Exception {
        long target = target("Bad requests", 120);
        assertEquals(400, raw("POST", "/routines/abc/routine-habits/" + steps.get(1) + "/copy", copier,
                "{\"targetRoutineId\":" + target + "}").status());
        assertEquals(400, raw("POST", copyPath(source, steps.get(1)), copier, "{\"targetRoutineId\":\"x\"}").status());
        assertEquals(400, raw("POST", copyPath(source, steps.get(1)), copier, "{\"targetRoutineId\":").status());
        assertEquals(400, raw("POST", copyPath(source, steps.get(1)), copier, "[1, 2]").status());
        assertEquals(0, plan(copier, target).size());
    }

    // ---- browser ----

    @Test
    void browserOffersCopyHabitOnEachActivityOfSomeoneElsesRoutine() {
        JsonNode view = page("buttons");
        assertEquals(4, view.path("copierButtons").asInt(), view.toString());
        assertEquals(0, view.path("ownerButtons").asInt(), "not on your own routine: " + view);
        assertTrue(view.path("copyRoutineVisible").asBoolean(), "Copy routine stays: " + view);
        assertTrue(view.path("adaptVisible").asBoolean(), "Adapt & copy stays: " + view);
        assertTrue(view.path("guestNotice").asString().contains("Sign in to copy this habit"), view.toString());
        assertFalse(view.path("guestModalOpen").asBoolean(), view.toString());
    }

    @Test
    void browserShowsTheHabitAndTheCopiersRoutines() {
        JsonNode form = page("form");
        assertEquals("Morning Exercise", form.path("title").asString(), form.toString());
        assertEquals("25 min", form.path("duration").asString(), form.toString());
        assertEquals("06:30 – 06:55", form.path("time").asString(), form.toString());
        assertEquals("Mon, Wed, Fri", form.path("days").asString(), form.toString());
        assertEquals("Stretch first", form.path("notes").asString(), form.toString());
        List<String> options = texts(form.path("options"));
        assertTrue(options.stream().anyMatch(option -> option.startsWith("Browser target")), options.toString());
        assertTrue(options.stream().anyMatch(option -> option.startsWith("Busy evening")), options.toString());
        assertFalse(options.stream().anyMatch(option -> option.startsWith("Morning plan")), "not the source: " + options);
        assertTrue(form.path("confirmEnabled").asBoolean(), form.toString());
        assertEquals("Breakfast " + TRICKY, form.path("trickyTitle").asString(), form.toString());
        assertFalse(form.path("xss").asBoolean(), form.toString());
    }

    @Test
    void browserCancelAddsNothing() {
        JsonNode view = page("cancel");
        assertCounted(view);
        assertFalse(view.path("modalOpen").asBoolean(), view.toString());
        assertEquals(view.path("before").asInt(), view.path("after").asInt(), view.toString());
    }

    @Test
    void browserExplainsARefusalAndKeepsTheChosenRoutine() {
        JsonNode view = page("invalid");
        assertCounted(view);
        assertTrue(view.path("error").asString().contains("Overlaps"), view.toString());
        assertTrue(view.path("modalOpen").asBoolean(), view.toString());
        assertEquals(String.valueOf(busyTarget), view.path("chosen").asString(), view.toString());
        assertEquals(view.path("before").asInt(), view.path("after").asInt(), view.toString());
    }

    @Test
    void browserCopiesOnceForADoubleClickAndOpensTheTarget() {
        JsonNode view = page("save");
        assertCounted(view);
        assertEquals(view.path("before").asInt() + 1, view.path("after").asInt(), "one copy for a double click: " + view);
        assertFalse(view.path("modalOpen").asBoolean(), view.toString());
        assertTrue(view.path("notice").asString().contains("was added to"), view.toString());
        assertTrue(view.path("notice").asString().contains("Browser target"), view.toString());
        assertEquals("routine.html?id=" + browserTarget, view.path("openHref").asString(), view.toString());
        assertEquals(view.path("sourceBefore"), view.path("sourceAfter"), "the source did not change");
        assertEquals(List.of("Morning Exercise"), texts(view.path("targetTitles")), view.toString());
        assertEquals(0, view.path("targetCopyButtons").asInt(), "your own routine has no Copy habit: " + view);
        // Only the browser's one copy reached the database.
        assertEquals(1L, (long) jdbc.queryForObject("select count(*) from routine_habits where routine_id = ?", Long.class, browserTarget));
        assertEquals(1L, (long) jdbc.queryForObject("select count(*) from routine_habits where routine_id = ?", Long.class, busyTarget));
    }

    @Test
    void browserTellsSomeoneWithoutRoutinesWhereToCreateOne() {
        JsonNode view = page("empty");
        assertTrue(view.path("emptyVisible").asBoolean(), view.toString());
        assertEquals("profile.html", view.path("createHref").asString(), view.toString());
        assertFalse(view.path("selectVisible").asBoolean(), view.toString());
        assertFalse(view.path("confirmEnabled").asBoolean(), view.toString());
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
            if (!view.path("buttonsInside").asBoolean()) {
                problems.add(name + " has Copy habit buttons outside the screen");
            }
            if (!view.path("inside").asBoolean()) {
                problems.add(name + " has form parts outside the screen: " + view.path("outside").asString());
            }
            if (!view.path("actionsVisible").asBoolean()) {
                problems.add(name + " hides Confirm copy or Cancel");
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
        author = register("Habit author");
        copier = register("Habit copier");
        other = register("Someone else");
        empty = register("No routines yet");

        source = routine(author, "Morning plan", 150, true);
        habit(author, source, "Wake Up", 15, "06:00", WEEKDAYS, "Open the curtains");
        habit(author, source, "Morning Exercise", 25, "06:30", MWF, "Stretch first");
        habit(author, source, "Reading", 30, "07:00", WEEKDAYS, null);
        habit(author, source, "Breakfast " + TRICKY, 20, null, null, null);
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

        // A public routine with one activity removed after it was tracked.
        trimmed = routine(author, "Trimmed", 30, true);
        habit(author, trimmed, "Still here", 10, null, null, null);
        habit(author, trimmed, "Taken out", 10, null, null, null);
        removedStep = links(trimmed).get(1);
        jdbc.update("update routine_habits set removed_at = now(), position = -id where id = ?", removedStep);

        othersRoutine = routine(other, "Someone else's own", 120, false);
        habit(other, othersRoutine, "Their step", 10, null, null, null);

        browserTarget = target("Browser target", 120);
        busyTarget = target("Busy evening", 120);
        habit(copier, busyTarget, "Yoga", 30, "06:20", List.of("MON"), null);

        String chrome = findChrome();
        if (chrome == null || !nodeAvailable()) {
            return;
        }

        Map<String, Object> fixtures = new LinkedHashMap<>();
        fixtures.put("base", "http://127.0.0.1:" + port);
        fixtures.put("copierToken", copier.token());
        fixtures.put("authorToken", author.token());
        fixtures.put("emptyToken", empty.token());
        fixtures.put("source", source);
        fixtures.put("exercise", steps.get(1));
        fixtures.put("tricky", steps.get(3));
        fixtures.put("browserTarget", browserTarget);
        fixtures.put("busyTarget", busyTarget);

        Path dir = Files.createTempDirectory("copy-habit-browser");
        Files.writeString(dir.resolve("fixtures.json"), json.writeValueAsString(fixtures));
        Process process = new ProcessBuilder(
                "node", "src/test/e2e/copy-habit-check.mjs", chrome,
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

    // The counts were really read, so comparing them proves something.
    private static void assertCounted(JsonNode view) {
        assertTrue(view.path("before").isNumber() && view.path("after").isNumber(), view.toString());
    }

    private JsonNode page(String name) {
        Assumptions.assumeTrue(browser != null, "Chrome or node not found");
        return browser.path(name);
    }

    // ---- helpers ----

    private static String copyPath(long routine, long step) {
        return "/routines/" + routine + "/routine-habits/" + step + "/copy";
    }

    // Copies an activity of the Morning plan.
    private JsonNode copy(Account account, long step, long target) throws Exception {
        return copy(account, source, step, target);
    }

    private JsonNode copy(Account account, long from, long step, long target) throws Exception {
        return copy(account, from, step, target, Map.of());
    }

    private JsonNode copy(Account account, long step, long target, Map<String, Object> extra) throws Exception {
        return copy(account, source, step, target, extra);
    }

    private JsonNode copy(Account account, long from, long step, long target, Map<String, Object> extra) throws Exception {
        Map<String, Object> body = new HashMap<>(extra);
        body.put("targetRoutineId", target);
        Reply reply = send("POST", copyPath(from, step), account, body);
        assertEquals(200, reply.status(), reply.body());
        return json.readTree(reply.body());
    }

    private static long lastStep(JsonNode reply) {
        return reply.get(reply.size() - 1).path("routineHabitId").asLong();
    }

    private long target(String title, int minutes) throws Exception {
        return routine(copier, title, minutes, false);
    }

    private int routineMinutes(long routine) {
        return jdbc.queryForObject("select duration_minutes from routines where id = ?", Integer.class, routine);
    }

    private void assertRefused(Reply reply, int status, String words) throws Exception {
        assertEquals(status, reply.status(), reply.body());
        String message = json.readTree(reply.body()).path("message").asString();
        assertTrue(message.toLowerCase().contains(words.toLowerCase()), words + " in " + reply.body());
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

    // Everything stored for the routine, its activities and their history, to compare before and after.
    private String snapshot(long routine) {
        return jdbc.queryForList("""
                select title, description, category, duration_minutes, is_public, deleted, user_id from routines where id = ?
                """, routine) + " " + jdbc.queryForList("""
                select rh.id, rh.habit_id, rh.position, rh.start_time, rh.repeat_days, rh.notes, rh.paused_at, rh.removed_at,
                       h.title, h.description, h.duration_minutes, h.user_id, h.routine_id
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

    private static List<String> texts(JsonNode array, String field) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.path(field).asString()));
        return values;
    }

    private Account register(String name) throws Exception {
        Reply reply = send("POST", "/auth/register", null, Map.of(
                "name", name,
                "email", "copyhabit_" + UUID.randomUUID().toString().substring(0, 12) + "@audit.test",
                "password", "Audit#12345"
        ));
        assertEquals(200, reply.status(), reply.body());
        JsonNode node = json.readTree(reply.body());
        return new Account(node.path("user").path("id").asLong(), node.path("token").asString());
    }

    private long routine(Account owner, String title, int minutes, boolean isPublic) throws Exception {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("title", title);
        fields.put("description", "Copy habit check");
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
        return raw(method, path, account, payload == null ? null : json.writeValueAsString(payload));
    }

    private Reply raw(String method, String path, Account account, String payload) throws Exception {
        HttpResponse<String> response = http.send(request(method, path, account, payload), HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(), response.body());
    }

    private CompletableFuture<HttpResponse<String>> sendAsync(String path, Account account, Map<String, ?> payload) throws Exception {
        return http.sendAsync(request("POST", path, account, json.writeValueAsString(payload)), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest request(String method, String path, Account account, String payload) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path))
                .method(method, payload == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(payload));
        if (payload != null) {
            builder.header("Content-Type", "application/json");
        }
        if (account != null) {
            builder.header("Authorization", "Bearer " + account.token());
        }
        return builder.build();
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
