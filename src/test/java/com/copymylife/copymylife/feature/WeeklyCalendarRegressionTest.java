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
import org.springframework.dao.DuplicateKeyException;
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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
class WeeklyCalendarRegressionTest {

    private static final String BOUNDARY = "WeeklyCalendarBoundary";
    // A fixed week far from today: Monday 7 to Sunday 13 January 2030.
    private static final LocalDate MON = LocalDate.of(2030, 1, 7);
    private static final LocalDate TUE = MON.plusDays(1);
    private static final LocalDate WED = MON.plusDays(2);
    private static final LocalDate SAT = MON.plusDays(5);
    private static final LocalDate SUN = MON.plusDays(6);

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private EmailService emailService;

    private JsonNode browser;

    private record Reply(int status, String body) {
    }

    private record Account(long id, String token) {
    }

    // ---- add and weekly display ----

    @Test
    void anAddedEventAppearsInItsWeekAndSurvivesARefresh() throws Exception {
        Account owner = register();
        long routine = routine(owner, "Reading", 45, false);

        Reply created = post("/calendar-events", owner, eventBody(routine, WED, "07:30", "NONE"));

        assertEquals(200, created.status(), created.body());
        JsonNode event = json.readTree(created.body());
        assertEquals(WED.toString(), event.path("day").asString());
        assertEquals("07:30", event.path("time").asString().substring(0, 5));
        assertEquals("Reading", event.path("title").asString());
        assertEquals(45, event.path("durationMinutes").asInt());
        assertFalse(event.path("repeating").asBoolean());

        List<JsonNode> week = week(owner, MON);
        assertEquals(1, week.size());
        assertEquals(WED.toString(), week.get(0).path("day").asString());
        assertEquals(0, week(owner, MON.plusWeeks(1)).size());
    }

    @Test
    void repeatingEventsFillEveryMatchingDay() throws Exception {
        Account owner = register();
        long routine = routine(owner, "Morning", 30, false);
        post("/calendar-events", owner, eventBody(routine, WED, "06:00", "DAILY"));
        post("/calendar-events", owner, eventBody(routine, TUE, "18:00", "WEEKLY"));

        List<JsonNode> first = week(owner, MON);
        assertEquals(List.of("2030-01-08", "2030-01-09", "2030-01-10", "2030-01-11", "2030-01-12", "2030-01-13"),
                days(first).stream().sorted().toList());
        assertEquals(List.of("18:00"), times(onDay(first, TUE)), "daily starts on Wednesday, so Tuesday only has the weekly one");
        assertEquals(List.of("06:00"), times(onDay(first, WED)));
        assertEquals(0, onDay(first, MON).size());
        assertEquals(8, week(owner, MON.plusWeeks(1)).size(), "7 daily + 1 weekly");
        assertEquals(0, week(owner, MON.minusWeeks(1)).size(), "nothing before the series starts");
    }

    @Test
    void invalidEventsAreRefusedWithAReason() throws Exception {
        Account owner = register();
        long routine = routine(owner, "Valid", 30, false);
        Account other = register();
        long othersPrivate = routine(other, "Private", 30, false);
        long othersPublic = routine(other, "Public", 30, true);

        for (Map<String, Object> body : List.of(
                eventBody(null, WED, "07:00", "NONE"),
                eventBody(routine, null, "07:00", "NONE"),
                eventBody(routine, WED, null, "NONE"),
                eventBody(routine, WED, "07:00", null)
        )) {
            Reply reply = post("/calendar-events", owner, body);
            assertEquals(400, reply.status(), body + " " + reply.body());
            assertFalse(message(reply).isBlank(), reply.body());
        }
        Reply missing = post("/calendar-events", owner, eventBody(987_654_321L, WED, "07:00", "NONE"));
        Reply privateOne = post("/calendar-events", owner, eventBody(othersPrivate, WED, "07:00", "NONE"));
        Reply publicOne = post("/calendar-events", owner, eventBody(othersPublic, WED, "07:00", "NONE"));
        assertEquals(404, missing.status(), missing.body());
        assertEquals(404, privateOne.status(), privateOne.body());
        assertEquals(404, publicOne.status(), "only your own routines go in your calendar");
        assertEquals(missing.body(), privateOne.body(), "a private routine looks the same as a missing one");
        assertEquals(400, get("/calendar-events?from=" + SUN + "&to=" + MON, owner).status());
        assertEquals(400, get("/calendar-events?from=" + MON + "&to=" + MON.plusDays(60), owner).status());
        assertEquals(0, eventsOf(owner));
    }

    // ---- single occurrence ----

    @Test
    void editingOneOccurrenceChangesOnlyThatDay() throws Exception {
        Account owner = register();
        long routine = routine(owner, "Daily", 30, false);
        long series = id(post("/calendar-events", owner, eventBody(routine, MON, "07:00", "DAILY")));
        String seriesRow = seriesRow(series);

        Reply reply = put("/calendar-events/" + series + "?scope=occurrence&date=" + WED, owner, eventBody(routine, WED, "09:15", "DAILY"));

        assertEquals(200, reply.status(), reply.body());
        List<JsonNode> week = week(owner, MON);
        assertEquals(7, week.size(), "no day lost and no duplicate");
        assertEquals(List.of("09:15"), times(onDay(week, WED)));
        for (LocalDate day : List.of(MON, TUE, MON.plusDays(3), MON.plusDays(4), SAT, SUN)) {
            assertEquals(List.of("07:00"), times(onDay(week, day)), day.toString());
        }
        assertEquals(List.of("07:00"), times(onDay(week(owner, MON.plusWeeks(1)), WED.plusWeeks(1))), "the next Wednesday is untouched");
        assertEquals(seriesRow, seriesRow(series), "the series itself is not rewritten");
        JsonNode changed = onDay(week, WED).get(0);
        assertEquals(series, changed.path("seriesId").asLong());
        assertEquals(WED.toString(), changed.path("originalDay").asString());
    }

    @Test
    void oneOccurrenceCanMoveToAnotherDayAndRoutine() throws Exception {
        Account owner = register();
        long morning = routine(owner, "Morning", 30, false);
        long evening = routine(owner, "Evening", 20, false);
        long series = id(post("/calendar-events", owner, eventBody(morning, MON, "07:00", "WEEKLY")));

        Reply reply = put("/calendar-events/" + series + "?scope=occurrence&date=" + MON, owner, eventBody(evening, SAT, "20:00", "NONE"));

        assertEquals(200, reply.status(), reply.body());
        List<JsonNode> week = week(owner, MON);
        assertEquals(0, onDay(week, MON).size());
        assertEquals("Evening", onDay(week, SAT).get(0).path("title").asString());
        assertEquals("Morning", onDay(week(owner, MON.plusWeeks(1)), MON.plusWeeks(1)).get(0).path("title").asString());
    }

    @Test
    void repeatingAnOccurrenceEditCreatesNothingTwice() throws Exception {
        Account owner = register();
        long routine = routine(owner, "Daily", 30, false);
        long series = id(post("/calendar-events", owner, eventBody(routine, MON, "07:00", "DAILY")));
        String path = "/calendar-events/" + series + "?scope=occurrence&date=" + WED;

        assertEquals(200, put(path, owner, eventBody(routine, WED, "09:00", "NONE")).status());
        assertEquals(200, put(path, owner, eventBody(routine, WED, "10:00", "NONE")).status());

        assertEquals(List.of("10:00"), times(onDay(week(owner, MON), WED)));
        assertEquals(1, exceptionsOf(series));
        assertEquals(2, eventsOf(owner), "the series and one changed occurrence");
    }

    @Test
    void deletingOneOccurrenceRemovesOnlyThatDayForGood() throws Exception {
        Account owner = register();
        long routine = routine(owner, "Daily", 30, false);
        long series = id(post("/calendar-events", owner, eventBody(routine, MON, "07:00", "DAILY")));

        Reply reply = delete("/calendar-events/" + series + "?scope=occurrence&date=" + WED, owner);
        Reply again = delete("/calendar-events/" + series + "?scope=occurrence&date=" + WED, owner);

        assertEquals(204, reply.status(), reply.body());
        assertEquals(204, again.status(), "deleting it again changes nothing");
        List<JsonNode> week = week(owner, MON);
        assertEquals(6, week.size());
        assertEquals(0, onDay(week, WED).size());
        assertEquals(7, week(owner, MON.plusWeeks(1)).size());
        assertEquals(1, exceptionsOf(series));
        assertEquals(1, eventsOf(owner), "the series stays");
    }

    @Test
    void anOccurrenceMustBelongToTheSeries() throws Exception {
        Account owner = register();
        long routine = routine(owner, "Weekly", 30, false);
        long series = id(post("/calendar-events", owner, eventBody(routine, MON, "07:00", "WEEKLY")));

        Reply wrongDay = delete("/calendar-events/" + series + "?scope=occurrence&date=" + TUE, owner);
        Reply beforeStart = delete("/calendar-events/" + series + "?scope=occurrence&date=" + MON.minusWeeks(1), owner);
        Reply noDate = delete("/calendar-events/" + series + "?scope=occurrence", owner);

        assertEquals(400, wrongDay.status(), wrongDay.body());
        assertEquals(400, beforeStart.status(), beforeStart.body());
        assertEquals(400, noDate.status(), noDate.body());
        assertEquals(0, exceptionsOf(series));
    }

    @Test
    void aDeletedOccurrenceCannotBeEdited() throws Exception {
        Account owner = register();
        long routine = routine(owner, "Daily", 30, false);
        long series = id(post("/calendar-events", owner, eventBody(routine, MON, "07:00", "DAILY")));
        delete("/calendar-events/" + series + "?scope=occurrence&date=" + WED, owner);

        Reply reply = put("/calendar-events/" + series + "?scope=occurrence&date=" + WED, owner, eventBody(routine, WED, "09:00", "NONE"));

        assertEquals(404, reply.status(), reply.body());
        assertEquals(0, onDay(week(owner, MON), WED).size());
    }

    // ---- entire series ----

    @Test
    void aRepeatingEventIsNeverRewrittenWithoutAChoice() throws Exception {
        Account owner = register();
        long routine = routine(owner, "Daily", 30, false);
        long series = id(post("/calendar-events", owner, eventBody(routine, MON, "07:00", "DAILY")));
        String before = seriesRow(series);

        Reply edit = put("/calendar-events/" + series, owner, eventBody(routine, WED, "09:00", "DAILY"));
        Reply remove = delete("/calendar-events/" + series, owner);

        assertEquals(400, edit.status(), edit.body());
        assertEquals("Choose whether to change one occurrence or the entire series", message(edit));
        assertEquals(400, remove.status(), remove.body());
        assertEquals(before, seriesRow(series));
        assertEquals(7, week(owner, MON).size());
    }

    @Test
    void editingTheEntireSeriesChangesEveryOccurrenceButKeepsItsStart() throws Exception {
        Account owner = register();
        long morning = routine(owner, "Morning", 30, false);
        long evening = routine(owner, "Evening", 30, false);
        long series = id(post("/calendar-events", owner, eventBody(morning, MON, "07:00", "DAILY")));
        put("/calendar-events/" + series + "?scope=occurrence&date=" + WED, owner, eventBody(morning, WED, "12:00", "NONE"));
        delete("/calendar-events/" + series + "?scope=occurrence&date=" + SAT, owner);

        Reply reply = put("/calendar-events/" + series + "?scope=series", owner, eventBody(evening, SUN, "21:00", "DAILY"));

        assertEquals(200, reply.status(), reply.body());
        assertEquals(MON.toString(), jdbc.queryForObject("select start_date::text from calendar_events where id = ?", String.class, series),
                "the series still starts where it did; past days are not lost");
        List<JsonNode> week = week(owner, MON);
        assertEquals(List.of("21:00"), times(onDay(week, MON)));
        assertEquals("Evening", onDay(week, MON).get(0).path("title").asString());
        assertEquals(List.of("12:00"), times(onDay(week, WED)), "a separately changed day keeps its change");
        assertEquals(0, onDay(week, SAT).size(), "a deleted day stays deleted");
        assertEquals(List.of("21:00"), times(onDay(week(owner, MON.plusWeeks(3)), MON.plusWeeks(3))));
    }

    @Test
    void deletingTheEntireSeriesRemovesEveryOccurrenceAndNothingElse() throws Exception {
        Account owner = register();
        long routine = routine(owner, "Daily", 30, false);
        long series = id(post("/calendar-events", owner, eventBody(routine, MON, "07:00", "DAILY")));
        long other = id(post("/calendar-events", owner, eventBody(routine, TUE, "19:00", "WEEKLY")));
        put("/calendar-events/" + series + "?scope=occurrence&date=" + WED, owner, eventBody(routine, WED, "12:00", "NONE"));
        delete("/calendar-events/" + series + "?scope=occurrence&date=" + SAT, owner);

        Reply reply = delete("/calendar-events/" + series + "?scope=series", owner);

        assertEquals(204, reply.status(), reply.body());
        List<JsonNode> week = week(owner, MON);
        assertEquals(1, week.size());
        assertEquals(other, week.get(0).path("id").asLong());
        assertEquals(0, exceptionsOf(series));
        assertEquals(1, eventsOf(owner));
        assertEquals(404, delete("/calendar-events/" + series + "?scope=series", owner).status());
    }

    @Test
    void aSingleEventIsEditedAndDeletedDirectly() throws Exception {
        Account owner = register();
        long routine = routine(owner, "Once", 30, false);
        long event = id(post("/calendar-events", owner, eventBody(routine, MON, "07:00", "NONE")));

        Reply moved = put("/calendar-events/" + event, owner, eventBody(routine, SAT, "08:00", "NONE"));

        assertEquals(200, moved.status(), moved.body());
        assertEquals(List.of("08:00"), times(onDay(week(owner, MON), SAT)));
        assertEquals(0, onDay(week(owner, MON), MON).size());
        assertEquals(204, delete("/calendar-events/" + event, owner).status());
        assertEquals(0, week(owner, MON).size());
    }

    // ---- boundaries ----

    @Test
    void lateEventsKeepTheirDayAndDuration() throws Exception {
        Account owner = register();
        long routine = routine(owner, "Night", 90, false);
        post("/calendar-events", owner, eventBody(routine, SUN, "23:30", "NONE"));
        post("/calendar-events", owner, eventBody(routine, MON, "00:00", "NONE"));

        List<JsonNode> week = week(owner, MON);

        assertEquals(List.of("00:00"), times(onDay(week, MON)));
        assertEquals(List.of("23:30"), times(onDay(week, SUN)));
        assertEquals(90, onDay(week, SUN).get(0).path("durationMinutes").asInt());
        assertEquals(0, week(owner, MON.plusWeeks(1)).size(), "the server lists an event on the day it starts");
    }

    @Test
    void theWeekDependsOnlyOnTheRequestedDates() throws Exception {
        Account owner = register();
        long routine = routine(owner, "Edge", 30, false);
        post("/calendar-events", owner, eventBody(routine, LocalDate.of(2030, 12, 31), "23:59", "NONE"));
        post("/calendar-events", owner, eventBody(routine, LocalDate.of(2031, 1, 1), "00:00", "NONE"));

        Reply oneDay = get("/calendar-events?from=2030-12-31&to=2030-12-31", owner);
        Reply next = get("/calendar-events?from=2031-01-01&to=2031-01-01", owner);

        assertEquals(1, json.readTree(oneDay.body()).size(), oneDay.body());
        assertEquals("23:59", json.readTree(oneDay.body()).get(0).path("time").asString().substring(0, 5));
        assertEquals(1, json.readTree(next.body()).size());
        assertEquals("2031-01-01", json.readTree(next.body()).get(0).path("day").asString());
    }

    // ---- existing data ----

    @Test
    void eventsSavedBeforeThisReleaseStillWork() throws Exception {
        Account owner = register();
        long routine = routine(owner, "Legacy", 30, false);
        long series = jdbc.queryForObject("""
                insert into calendar_events (user_id, routine_id, start_date, start_time, repeat_type)
                values (?, ?, ?, '06:45', 'WEEKLY') returning id
                """, Long.class, owner.id(), routine, TUE.minusWeeks(4));

        assertEquals(List.of("06:45"), times(onDay(week(owner, MON), TUE)));
        assertEquals(204, delete("/calendar-events/" + series + "?scope=occurrence&date=" + TUE, owner).status());
        assertEquals(0, onDay(week(owner, MON), TUE).size());
        assertEquals(1, onDay(week(owner, MON.plusWeeks(1)), TUE.plusWeeks(1)).size());
    }

    @Test
    void aDeletedRoutineLeavesTheCalendar() throws Exception {
        Account owner = register();
        long routine = routine(owner, "Gone", 30, false);
        post("/calendar-events", owner, eventBody(routine, MON, "07:00", "DAILY"));

        assertEquals(204, delete("/routines/" + routine, owner).status());

        assertEquals(0, week(owner, MON).size());
    }

    // ---- who may change what ----

    @Test
    void nobodyElseCanSeeOrChangeYourCalendar() throws Exception {
        Account owner = register();
        long routine = routine(owner, "Mine", 30, true);
        long series = id(post("/calendar-events", owner, eventBody(routine, MON, "07:00", "DAILY")));
        Account stranger = register();
        long strangersRoutine = routine(stranger, "Theirs", 30, false);
        String before = seriesRow(series);

        assertEquals(0, week(stranger, MON).size());
        assertEquals(404, put("/calendar-events/" + series + "?scope=series", stranger, eventBody(strangersRoutine, MON, "09:00", "DAILY")).status());
        assertEquals(404, put("/calendar-events/" + series + "?scope=occurrence&date=" + WED, stranger, eventBody(strangersRoutine, WED, "09:00", "NONE")).status());
        assertEquals(404, delete("/calendar-events/" + series + "?scope=occurrence&date=" + WED, stranger).status());
        assertEquals(404, delete("/calendar-events/" + series + "?scope=series", stranger).status());
        assertEquals(403, get("/calendar-events?from=" + MON + "&to=" + SUN, null).status());
        assertEquals(403, delete("/calendar-events/" + series + "?scope=series", null).status());
        assertEquals(404, put("/calendar-events/" + series + "?scope=series", owner, eventBody(strangersRoutine, MON, "09:00", "DAILY")).status(),
                "you cannot point your event at someone else's routine");
        assertEquals(before, seriesRow(series));
        assertEquals(0, exceptionsOf(series));
    }

    // ---- duplicates, concurrency and transactions ----

    @Test
    void addingTheSameEventTwiceKeepsOne() throws Exception {
        Account owner = register();
        long routine = routine(owner, "Once", 30, false);

        long first = id(post("/calendar-events", owner, eventBody(routine, MON, "07:00", "WEEKLY")));
        long second = id(post("/calendar-events", owner, eventBody(routine, MON, "07:00", "WEEKLY")));

        assertEquals(first, second);
        assertEquals(1, eventsOf(owner));
        assertEquals(200, post("/calendar-events", owner, eventBody(routine, MON, "08:00", "WEEKLY")).status());
        assertEquals(2, eventsOf(owner), "a different time is a different event");
    }

    @Test
    void simultaneousRequestsLeaveConsistentData() throws Exception {
        Account owner = register();
        long routine = routine(owner, "Busy", 30, false);

        List<Integer> creates = together(10, index -> post("/calendar-events", owner, eventBody(routine, MON, "07:00", "DAILY")).status());
        assertTrue(creates.stream().allMatch(status -> status == 200), creates.toString());
        assertEquals(1, eventsOf(owner));
        long series = jdbc.queryForObject("select id from calendar_events where user_id = ?", Long.class, owner.id());

        List<Integer> deletes = together(10, index -> delete("/calendar-events/" + series + "?scope=occurrence&date=" + WED, owner).status());
        assertTrue(deletes.stream().allMatch(status -> status == 204), deletes.toString());
        assertEquals(1, exceptionsOf(series));

        List<Integer> edits = together(10, index -> put("/calendar-events/" + series + "?scope=occurrence&date=" + TUE, owner,
                eventBody(routine, TUE, String.format("%02d:00", 8 + index), "NONE")).status());
        assertTrue(edits.stream().allMatch(status -> status == 200), edits.toString());
        assertEquals(1, onDay(week(owner, MON), TUE).size());
        assertEquals(2, exceptionsOf(series));
        assertEquals(2, eventsOf(owner));
    }

    @Test
    void theDatabaseRefusesASecondExceptionForTheSameDay() throws Exception {
        Account owner = register();
        long routine = routine(owner, "Daily", 30, false);
        long series = id(post("/calendar-events", owner, eventBody(routine, MON, "07:00", "DAILY")));
        delete("/calendar-events/" + series + "?scope=occurrence&date=" + WED, owner);

        assertThrows(DuplicateKeyException.class, () -> jdbc.update(
                "insert into calendar_event_exceptions (event_id, occurrence_date, created_at) values (?, ?, now())", series, WED));
    }

    @Test
    void aFailedOccurrenceEditLeavesEverythingAsItWas() throws Exception {
        Account owner = register();
        long routine = routine(owner, "Daily", 30, false);
        long series = id(post("/calendar-events", owner, eventBody(routine, MON, "07:00", "DAILY")));
        jdbc.execute("""
                create or replace function calendar_test_fail() returns trigger language plpgsql as $$
                begin raise exception 'calendar test failure'; end $$
                """);
        jdbc.execute("create trigger calendar_test_fail before insert on calendar_events for each row when (new.series_id = "
                + series + ") execute function calendar_test_fail()");
        try {
            Reply reply = put("/calendar-events/" + series + "?scope=occurrence&date=" + WED, owner, eventBody(routine, WED, "09:00", "NONE"));
            assertTrue(reply.status() >= 500, reply.status() + " " + reply.body());
        } finally {
            jdbc.execute("drop trigger if exists calendar_test_fail on calendar_events");
            jdbc.execute("drop function if exists calendar_test_fail()");
        }

        assertEquals(0, exceptionsOf(series), "the cancelled day was rolled back with the failed insert");
        assertEquals(List.of("07:00"), times(onDay(week(owner, MON), WED)));
    }

    @Test
    void schemaAddsExceptionsWithoutTouchingOldColumns() {
        List<String> constraints = jdbc.queryForList(
                "select conname from pg_constraint where conrelid = 'calendar_event_exceptions'::regclass", String.class);
        assertTrue(constraints.contains("uk_calendar_exception_day"), constraints.toString());
        String fk = jdbc.queryForObject("""
                select pg_get_constraintdef(oid) from pg_constraint
                where conrelid = 'calendar_event_exceptions'::regclass and contype = 'f'
                """, String.class);
        assertTrue(fk.contains("ON DELETE CASCADE"), fk);

        Map<String, String> columns = new HashMap<>();
        jdbc.queryForList("""
                select column_name, is_nullable from information_schema.columns
                where table_name = 'calendar_events' and column_name in ('series_id', 'original_date')
                """).forEach(row -> columns.put((String) row.get("column_name"), (String) row.get("is_nullable")));
        assertEquals(Map.of("series_id", "YES", "original_date", "YES"), columns);
    }

    // ---- the rest of the app still works ----

    @Test
    void routinesHabitsRunsAndPeopleStillWork() throws Exception {
        Account owner = register();
        long routine = routine(owner, "Together", 2, true);
        for (String title : List.of("One", "Two")) {
            assertEquals(200, post("/routines/" + routine + "/habits", owner, Map.of("title", title, "durationMinutes", 1)).status());
        }
        List<Long> steps = jdbc.queryForList("select id from routine_habits where routine_id = ? order by position", Long.class, routine);
        Map<String, Object> plan = new HashMap<>();
        plan.put("startTime", "07:00");
        // Every day, so the habit is in today's run whatever day the test runs on.
        plan.put("repeatDays", List.of("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN"));
        assertEquals(200, put("/routines/" + routine + "/routine-habits/" + steps.get(0), owner, plan).status());
        post("/calendar-events", owner, eventBody(routine, MON, "07:00", "DAILY"));

        assertEquals(200, post("/routines/" + routine + "/routine-habits/" + steps.get(1) + "/pause", owner, null).status());
        assertEquals(200, post("/routines/" + routine + "/routine-habits/" + steps.get(1) + "/resume", owner, null).status());
        assertEquals(200, post("/routine-runs/" + routine + "/start", owner, null).status());
        assertEquals(200, post("/routine-runs/" + routine + "/routine-habits/" + steps.get(0) + "/skip", owner, null).status());
        assertEquals(200, post("/routine-runs/" + routine + "/routine-habits/" + steps.get(1) + "/postpone", owner, null).status());
        assertEquals(200, post("/routine-runs/" + routine + "/stop", owner, null).status());
        assertEquals(200, get("/progress?period=week", owner).status());

        Account fan = register();
        assertEquals(200, post("/routines/" + routine + "/copy", fan, null).status());
        assertEquals(200, post("/users/" + owner.id() + "/follow", fan, null).status());
        assertEquals(200, post("/friends/request/" + owner.id(), fan, null).status());
        assertEquals(0, week(fan, MON).size(), "copying a routine does not copy its calendar");
        assertEquals(7, week(owner, MON).size());
    }

    // ---- real browser ----

    @BeforeAll
    void useTheCalendarInABrowser() throws Exception {
        String chrome = findChrome();
        if (chrome == null || !nodeAvailable()) {
            return;
        }

        Account owner = register();
        long morning = routine(owner, "Morning run", 45, false);
        routine(owner, "Evening read", 30, false);
        long night = routine(owner, "Night owl", 60, false);
        LocalDate monday = LocalDate.now().with(java.time.DayOfWeek.MONDAY);
        post("/calendar-events", owner, eventBody(morning, monday, "07:00", "DAILY"));
        post("/calendar-events", owner, eventBody(night, monday.plusDays(1), "23:30", "NONE"));

        Map<String, Object> fixtures = new LinkedHashMap<>();
        fixtures.put("base", "http://127.0.0.1:" + port);
        fixtures.put("token", owner.token());
        fixtures.put("monday", monday.toString());
        fixtures.put("emptyToken", register().token());

        Path dir = Files.createTempDirectory("calendar-browser");
        Files.writeString(dir.resolve("fixtures.json"), json.writeValueAsString(fixtures));
        Process process = new ProcessBuilder(
                "node", "src/test/e2e/weekly-calendar-check.mjs", chrome,
                dir.resolve("fixtures.json").toString(), dir.resolve("results.json").toString()
        ).redirectErrorStream(true).redirectOutput(dir.resolve("node.log").toFile()).start();

        if (!process.waitFor(4, TimeUnit.MINUTES)) {
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

    @Test
    void browserShowsTheWeekAndTheChosenDay() {
        JsonNode start = page("initial");

        assertEquals(7, start.path("dayHeaders").asInt(), browser.toString());
        assertEquals(7, start.path("blocks").path("Morning run").asInt());
        assertEquals(1, start.path("blocks").path("Night owl").asInt());
        assertEquals(1, start.path("continuations").asInt(), "the 23:30 hour carries on into Wednesday");

        JsonNode tuesday = page("tuesday");
        assertEquals(List.of("Morning run", "Night owl"), texts(tuesday.path("dayTitles")));
        assertTrue(tuesday.path("dayHeading").asString().startsWith("Tuesday"), tuesday.toString());
        assertTrue(tuesday.path("routineLinks").asInt() >= 2);
    }

    @Test
    void browserShowsAnEmptyDayAndAnEmptyWeek() {
        assertTrue(page("emptyWeek").path("dayEmpty").asString().contains("Nothing planned"), browser.toString());
    }

    @Test
    void browserAddsAnEventWithoutReloading() {
        JsonNode added = page("afterAdd");

        assertEquals(1, added.path("blocks").path("Evening read").asInt(), browser.toString());
        assertTrue(added.path("message").asString().contains("Added"), added.toString());
        assertFalse(added.path("reloaded").asBoolean());
    }

    @Test
    void browserEditsOneOccurrenceOnly() {
        JsonNode asked = page("editScope");
        assertTrue(asked.path("scopeVisible").asBoolean(), browser.toString());

        JsonNode edited = page("afterEditOne");
        assertEquals(List.of("9:15 AM"), texts(edited.path("wednesdayTimes")), edited.toString());
        assertEquals(6, edited.path("timesAt7").asInt());
        assertTrue(edited.path("message").asString().contains("Only"), edited.toString());

        assertEquals(6, page("editAfterReload").path("timesAt7").asInt());
    }

    @Test
    void browserAsksBeforeDeletingAndOffersOneOrAll() {
        JsonNode asked = page("deleteAsked");
        assertTrue(asked.path("modalVisible").asBoolean(), browser.toString());
        assertEquals(List.of("occurrence", "series"), texts(asked.path("choices")));

        JsonNode cancelled = page("deleteCancelled");
        assertFalse(cancelled.path("modalVisible").asBoolean());
        assertEquals(7, cancelled.path("blocks").path("Morning run").asInt(), "six at 07:00 and the moved Wednesday");

        JsonNode one = page("afterDeleteOne");
        assertEquals(6, one.path("blocks").path("Morning run").asInt(), one.toString());

        JsonNode all = page("afterDeleteSeries");
        assertEquals(0, all.path("blocks").path("Morning run").asInt(), all.toString());
        assertEquals(1, all.path("blocks").path("Night owl").asInt());
        assertEquals(0, page("deleteAfterReload").path("blocks").path("Morning run").asInt());
        assertEquals("[]", browser.path("dialogs").toString(), "messages appear on the page, not in alert boxes");
    }

    @Test
    void browserWorksInEveryDesignAndOnA390PixelPhone() {
        for (String layout : List.of("originalPhone", "autumnPhone", "autumnDesktop", "modalPhone")) {
            JsonNode view = page(layout);
            assertTrue(view.path("rendered").asBoolean(), layout + " " + view);
            assertTrue(view.path("overflow").asInt() <= 0, layout + " " + view);
            assertTrue(view.path("rightmost").asInt() <= view.path("width").asInt(), layout + " " + view);
            assertTrue(view.path("minContrast").asDouble() >= 4.5, layout + " " + view);
            assertTrue(view.path("smallestTarget").asInt() >= 32, layout + " " + view);
        }
        assertEquals(390, page("originalPhone").path("width").asInt());
        assertEquals("autumn", page("autumnPhone").path("theme").asString());
    }

    // ---- helpers ----

    private interface Attempt {
        int run(int index) throws Exception;
    }

    private List<Integer> together(int count, Attempt attempt) throws Exception {
        List<Callable<Integer>> attempts = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int index = i;
            attempts.add(() -> attempt.run(index));
        }
        ExecutorService pool = Executors.newFixedThreadPool(count);
        List<Integer> statuses = new ArrayList<>();
        try {
            for (Future<Integer> future : pool.invokeAll(attempts)) {
                statuses.add(future.get());
            }
        } finally {
            pool.shutdownNow();
        }
        return statuses;
    }

    private List<String> texts(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.asString()));
        return values;
    }

    private Map<String, Object> eventBody(Long routine, LocalDate day, String time, String repeat) {
        Map<String, Object> body = new HashMap<>();
        body.put("routineId", routine);
        body.put("day", day == null ? null : day.toString());
        body.put("time", time);
        body.put("repeat", repeat);
        return body;
    }

    private List<JsonNode> week(Account owner, LocalDate monday) throws Exception {
        Reply reply = get("/calendar-events?from=" + monday + "&to=" + monday.plusDays(6), owner);
        assertEquals(200, reply.status(), reply.body());
        List<JsonNode> events = new ArrayList<>();
        json.readTree(reply.body()).forEach(events::add);
        return events;
    }

    private List<JsonNode> onDay(List<JsonNode> events, LocalDate day) {
        return events.stream().filter(event -> event.path("day").asString().equals(day.toString())).toList();
    }

    private List<String> days(List<JsonNode> events) {
        return events.stream().map(event -> event.path("day").asString()).toList();
    }

    private List<String> times(List<JsonNode> events) {
        return events.stream().map(event -> event.path("time").asString().substring(0, 5)).sorted().toList();
    }

    private long id(Reply reply) throws Exception {
        assertEquals(200, reply.status(), reply.body());
        return json.readTree(reply.body()).path("id").asLong();
    }

    private String seriesRow(long id) {
        return jdbc.queryForList("select start_date, start_time, repeat_type, routine_id from calendar_events where id = ?", id).toString();
    }

    private int eventsOf(Account owner) {
        return jdbc.queryForObject("select count(*) from calendar_events where user_id = ?", Integer.class, owner.id());
    }

    private int exceptionsOf(long series) {
        return jdbc.queryForObject("select count(*) from calendar_event_exceptions where event_id = ?", Integer.class, series);
    }

    private String message(Reply reply) throws Exception {
        return json.readTree(reply.body()).path("message").asString();
    }

    private Account register() throws Exception {
        Reply reply = post("/auth/register", null, Map.of(
                "name", "Calendar QA",
                "email", "cal_" + UUID.randomUUID().toString().substring(0, 12) + "@audit.test",
                "password", "Audit#12345"));
        assertEquals(200, reply.status(), reply.body());
        JsonNode node = json.readTree(reply.body());
        return new Account(node.path("user").path("id").asLong(), node.path("token").asString());
    }

    private long routine(Account owner, String title, int minutes, boolean isPublic) throws Exception {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("title", title);
        fields.put("description", "Calendar check");
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

    private Reply get(String path, Account account) throws Exception {
        return send("GET", path, account, null);
    }

    private Reply post(String path, Account account, Map<String, ?> payload) throws Exception {
        return send("POST", path, account, payload);
    }

    private Reply put(String path, Account account, Map<String, ?> payload) throws Exception {
        return send("PUT", path, account, payload);
    }

    private Reply delete(String path, Account account) throws Exception {
        return send("DELETE", path, account, null);
    }

    private Reply send(String method, String path, Account account, Map<String, ?> payload) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path));
        if (account != null) {
            builder.header("Authorization", "Bearer " + account.token());
        }
        if (payload != null) {
            builder.header("Content-Type", "application/json");
        }
        builder.method(method, payload == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload)));
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
