package com.copymylife.copymylife.service;

import com.copymylife.copymylife.assistant.ActivityKind;
import com.copymylife.copymylife.assistant.Clock;
import com.copymylife.copymylife.assistant.DailySummaryEngine;
import com.copymylife.copymylife.assistant.HabitDurationAnalyzer;
import com.copymylife.copymylife.assistant.HistorySignals;
import com.copymylife.copymylife.assistant.Intensity;
import com.copymylife.copymylife.assistant.PersonalizationEngine;
import com.copymylife.copymylife.assistant.PreferenceInput;
import com.copymylife.copymylife.assistant.RequestParser;
import com.copymylife.copymylife.assistant.ResolvedPreferences;
import com.copymylife.copymylife.assistant.RoutinePlanningEngine;
import com.copymylife.copymylife.assistant.RoutineReviewEngine;
import com.copymylife.copymylife.dto.AssistantChangesRequest;
import com.copymylife.copymylife.dto.AssistantPlanRequest;
import com.copymylife.copymylife.dto.AssistantPreferencesRequest;
import com.copymylife.copymylife.dto.AssistantSaveRequest;
import com.copymylife.copymylife.exception.AdaptedActivityException;
import com.copymylife.copymylife.model.*;
import com.copymylife.copymylife.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

// The personal routine assistant (version 1). It plans, reviews and summarises with the fixed
// rules in com.copymylife.copymylife.assistant, from the user's own data: their preferences, the
// routines they own and the runs they tracked. The Python AI service (multilingual MiniLM) is
// asked for one thing only, closeness of meaning: which public routines are examples close to
// what the user wants, and whether a routine has an activity close to each goal. Without it the
// assistant still works and says what it could not check.
// Nothing is changed without the user's request: a plan is only a preview until the user saves
// it as a new routine, and review changes are applied only when the user approves them.
// Other members' content is read only where anyone may see it (public, not hidden, not deleted,
// not banned, not on a hidden profile), and only titles, categories, descriptions and activity
// titles are sent to the AI service.
@Service
public class RoutineAssistantService {

    private static final Logger log = LoggerFactory.getLogger(RoutineAssistantService.class);

    static final int MAX_HABITS = 30;
    static final int MAX_ACTIVITIES = 40;
    static final int MAX_COMMITMENTS = 10;
    static final int MAX_CHANGES = 60;
    static final int MAX_EXAMPLES = 3;
    static final int EXAMPLE_CANDIDATES = 200;
    // Examples below this are no closer than unrelated routines; the same floor as US-26 matching.
    static final double EXAMPLE_MIN_SCORE = 0.30;
    static final String UNAVAILABLE = "the AI service is not available right now";
    private static final String ALL_DAYS = "MON,TUE,WED,THU,FRI,SAT,SUN";
    private static final Map<ActivityKind, String> KIND_CATEGORY = Map.of(
            ActivityKind.STUDY, "Study", ActivityKind.WORK, "Productivity",
            ActivityKind.EXERCISE, "Fitness", ActivityKind.PERSONAL, "Wellness");

    private final UserRepository userRepository;
    private final RoutineRepository routineRepository;
    private final HabitRepository habitRepository;
    private final RoutineHabitRepository routineHabitRepository;
    private final RoutineCompletionRepository routineCompletionRepository;
    private final HabitCompletionRepository habitCompletionRepository;
    private final AssistantPreferencesRepository preferencesRepository;
    private final AssistantDecisionRepository decisionRepository;
    private final CategoryRepository categoryRepository;
    private final AssistantHistoryService history;
    private final SemanticSearchClient semanticSearch;
    private final ObjectMapper json;
    private final TransactionTemplate readOnly;

    public RoutineAssistantService(
            UserRepository userRepository,
            RoutineRepository routineRepository,
            HabitRepository habitRepository,
            RoutineHabitRepository routineHabitRepository,
            RoutineCompletionRepository routineCompletionRepository,
            HabitCompletionRepository habitCompletionRepository,
            AssistantPreferencesRepository preferencesRepository,
            AssistantDecisionRepository decisionRepository,
            CategoryRepository categoryRepository,
            AssistantHistoryService history,
            SemanticSearchClient semanticSearch,
            ObjectMapper json,
            PlatformTransactionManager transactionManager
    ) {
        this.userRepository = userRepository;
        this.routineRepository = routineRepository;
        this.habitRepository = habitRepository;
        this.routineHabitRepository = routineHabitRepository;
        this.routineCompletionRepository = routineCompletionRepository;
        this.habitCompletionRepository = habitCompletionRepository;
        this.preferencesRepository = preferencesRepository;
        this.decisionRepository = decisionRepository;
        this.categoryRepository = categoryRepository;
        this.history = history;
        this.semanticSearch = semanticSearch;
        this.json = json;
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
    }

    // ---- preferences ----

    @Transactional(readOnly = true)
    public Map<String, Object> getPreferences(String email, ZoneId zone) {
        User user = member(email);
        Optional<AssistantPreferences> saved = preferencesRepository.findByUserId(user.getId());
        HistorySignals signals = history.signals(history.records(user, LocalDate.now(zone)), zone);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("saved", saved.map(this::describeSaved).orElse(null));
        result.put("updatedAt", saved.map(AssistantPreferences::getUpdatedAt).orElse(null));
        result.put("resolved", PersonalizationEngine.resolve(null, saved.map(RoutineAssistantService::input).orElse(null), signals).describe());

        Map<String, Object> seen = new LinkedHashMap<>();
        for (String key : List.of("breakfast", "lunch", "dinner", "exercise")) {
            HistorySignals.TimeSignal signal = signals.time(key);
            if (signal != null) {
                seen.put(key, Map.of("time", Clock.format(signal.minute()), "observations", signal.observations(),
                        "used", signal.observations() >= PersonalizationEngine.MIN_OBSERVATIONS));
            }
        }
        result.put("history", seen);
        result.put("minObservations", PersonalizationEngine.MIN_OBSERVATIONS);
        return result;
    }

    @Transactional
    public Map<String, Object> savePreferences(String email, AssistantPreferencesRequest request, ZoneId zone) {
        User user = member(email);
        if (request == null) {
            throw new IllegalArgumentException("Send the preferences to save");
        }
        PreferenceInput input = input(request);
        PersonalizationEngine.validate(input);
        List<RoutinePlanningEngine.Commitment> commitments = commitments(request.commitments());
        // The saved set alone must make a valid day.
        PersonalizationEngine.resolve(null, input, null);

        AssistantPreferences saved = preferencesRepository.findByUserId(user.getId()).orElseGet(() -> {
            AssistantPreferences created = new AssistantPreferences();
            created.setUser(user);
            return created;
        });
        saved.setWakeTime(time(input.wake()));
        saved.setSleepTime(time(input.sleep()));
        saved.setAvailableFrom(time(input.availableFrom()));
        saved.setAvailableTo(time(input.availableTo()));
        saved.setBreakfastTime(time(input.breakfast()));
        saved.setLunchTime(time(input.lunch()));
        saved.setDinnerTime(time(input.dinner()));
        saved.setMealMinutes(input.mealMinutes());
        saved.setExerciseTime(time(input.exercise()));
        saved.setFocusBlockMinutes(input.focusBlockMinutes());
        saved.setBreakEveryMinutes(input.breakEveryMinutes());
        saved.setBreakMinutes(input.breakMinutes());
        saved.setRestFrequency(input.restFrequency());
        saved.setIntensity(input.intensity() == null ? null : input.intensity().name());
        saved.setCommitments(commitments.isEmpty() ? null : writeCommitments(commitments));
        saved.setUpdatedAt(Instant.now());
        preferencesRepository.save(saved);

        return getPreferences(email, zone);
    }

    // ---- quick fill ----

    public Map<String, Object> parse(String email, String text) {
        member(email);
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Write a sentence about your day first");
        }
        if (text.length() > 1000) {
            throw new IllegalArgumentException("Keep the description under 1000 characters");
        }
        RequestParser.Parsed parsed = RequestParser.parse(text);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("preferences", parsed.preferences());
        result.put("habits", parsed.habits().stream().map(habit -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("title", habit.title());
            item.put("minutes", habit.minutes());
            item.put("kind", habit.kind().name());
            item.put("priority", "MEDIUM");
            item.put("preferredTime", Clock.formatNullable(habit.preferredTime()));
            return item;
        }).toList());
        result.put("commitments", parsed.commitments().stream().map(c -> Map.of("title", c.title(),
                "start", Clock.format(c.start()), "end", Clock.format(c.end()))).toList());
        result.put("recognised", parsed.recognised());
        result.put("note", "Recognised with simple word patterns, not a language model. Check the form before planning.");
        return result;
    }

    // ---- generate ----

    private record PlanData(Map<String, Object> response, String query, List<String> categories, ActivityKind mainKind,
                            ExampleData examples) {
    }

    private record ExampleData(List<SemanticSearchClient.Item> items, Map<Long, Map<String, Object>> cards) {
    }

    public Map<String, Object> generate(String email, AssistantPlanRequest request, ZoneId zone) {
        if (request == null) {
            throw new IllegalArgumentException("Tell the assistant what to plan");
        }
        PlanData data = readOnly.execute(status -> plan(email, request, zone));
        Map<String, Object> response = data.response();
        Map<String, Object> examples = examples(data.query(), data.examples());
        response.put("examples", examples);

        String category = null;
        for (String name : data.categories()) {
            if (name.equalsIgnoreCase(KIND_CATEGORY.getOrDefault(data.mainKind(), ""))) {
                category = name;
            }
        }
        response.put("suggestedCategory", category != null ? category : data.categories().isEmpty() ? null : data.categories().get(0));
        response.put("categories", data.categories());
        return response;
    }

    private PlanData plan(String email, AssistantPlanRequest request, ZoneId zone) {
        User user = member(email);
        List<RoutinePlanningEngine.HabitRequest> habits = habits(request.habits());
        Optional<AssistantPreferences> saved = preferencesRepository.findByUserId(user.getId());
        PreferenceInput asked = request.preferences() == null ? null : input(request.preferences());
        PersonalizationEngine.validate(asked);

        List<HabitCompletion> records = history.records(user, LocalDate.now(zone));
        HistorySignals signals = history.signals(records, zone);
        ResolvedPreferences prefs = PersonalizationEngine.resolve(asked, saved.map(RoutineAssistantService::input).orElse(null), signals);

        List<RoutinePlanningEngine.Commitment> commitments = request.commitments() != null
                ? commitments(request.commitments())
                : saved.map(p -> readCommitments(p.getCommitments())).orElse(List.of());
        Set<String> meals = meals(request.meals());
        String days = HabitSchedule.parseDays(request.days());

        RoutinePlanningEngine.PlanResult plan = RoutinePlanningEngine.plan(new RoutinePlanningEngine.PlanRequest(
                prefs, commitments, habits, meals, request.splitFocus() == null || request.splitFocus(), signals));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "PREVIEW");
        response.put("note", "This is a preview. Nothing is saved until you press Save as new routine; your existing routines are never changed.");
        response.put("preferences", prefs.describe());
        response.put("days", HabitSchedule.dayList(days));
        response.put("activities", plan.activities().stream().map(RoutineAssistantService::plannedActivity).toList());
        response.put("unscheduled", plan.unscheduled());
        response.put("explanations", plan.explanations());
        response.put("warnings", plan.warnings());
        response.put("plannedMinutes", plan.plannedMinutes());
        response.put("focusMinutes", plan.focusMinutes());

        ActivityKind mainKind = habits.stream()
                .collect(Collectors.groupingBy(RoutinePlanningEngine.HabitRequest::kind,
                        Collectors.summingInt(RoutinePlanningEngine.HabitRequest::minutes)))
                .entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(ActivityKind.OTHER);
        List<String> categories = categoryRepository.findAll().stream()
                .sorted(Comparator.comparing(c -> c.getPosition() == null ? Integer.MAX_VALUE : c.getPosition()))
                .map(Category::getName).toList();

        String query = String.join(". ", nonBlank(user.getGoals(), user.getInterests(),
                habits.stream().map(RoutinePlanningEngine.HabitRequest::title).collect(Collectors.joining(", "))));
        return new PlanData(response, query, categories, mainKind, exampleData(user));
    }

    // Public routines anyone may see, as texts for the model and as the cards shown for them.
    private ExampleData exampleData(User user) {
        List<Routine> candidates = routineRepository.findAssistantExamples(user.getId(), PageRequest.of(0, EXAMPLE_CANDIDATES));
        Map<Long, List<RoutineHabit>> activities = candidates.isEmpty() ? Map.of()
                : routineHabitRepository.findRunningWithHabitByRoutineIdIn(candidates.stream().map(Routine::getId).toList())
                .stream().collect(Collectors.groupingBy(link -> link.getRoutine().getId()));

        List<SemanticSearchClient.Item> items = new ArrayList<>();
        Map<Long, Map<String, Object>> cards = new HashMap<>();

        for (Routine routine : candidates) {
            List<RoutineHabit> links = activities.getOrDefault(routine.getId(), List.of());
            String text = String.join(". ", nonBlank(routine.getTitle(), routine.getCategory(), routine.getDescription(),
                    links.stream().map(link -> link.getHabit().getTitle()).collect(Collectors.joining(", "))));
            items.add(new SemanticSearchClient.Item(routine.getId(), cut(text, 2000)));

            Map<String, Object> card = new LinkedHashMap<>();
            card.put("routineId", routine.getId());
            card.put("title", routine.getTitle());
            card.put("category", routine.getCategory());
            card.put("builtIn", routine.isBuiltIn());
            card.put("authorName", routine.isBuiltIn() ? "CopyMyLife" : routine.getUser().getName());
            card.put("averageRating", routine.getRatingCount() == 0 ? null : routine.getAverageRating());
            card.put("ratingCount", routine.getRatingCount());
            card.put("activities", links.stream().limit(8).map(link -> {
                Map<String, Object> activity = new LinkedHashMap<>();
                activity.put("title", link.getHabit().getTitle());
                activity.put("minutes", link.getHabit().getDurationMinutes());
                activity.put("startTime", link.getStartTime() == null ? null : HabitSchedule.format(link.getStartTime()));
                return activity;
            }).toList());
            cards.put(routine.getId(), card);
        }
        return new ExampleData(items, cards);
    }

    private Map<String, Object> examples(String query, ExampleData data) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("note", "Public routines whose meaning is closest to your goals and activities, found with a multilingual"
                + " language model. They are ideas to take from, not copied into your plan; ratings are shown but do not decide.");

        if (query.isBlank() || data.items().isEmpty()) {
            result.put("status", "NONE");
            result.put("message", data.items().isEmpty() ? "There are no public routines to compare with yet."
                    : "Add goals or activities to find similar public routines.");
            result.put("items", List.of());
            return result;
        }

        List<SemanticSearchClient.Scored> ranked;
        try {
            ranked = semanticSearch.rank(List.of(cut(query, 2000)), data.items(), MAX_EXAMPLES, EXAMPLE_MIN_SCORE).get(0);
        } catch (SemanticSearchClient.Unavailable exception) {
            log.warn("Routine examples unavailable: {}", exception.getMessage());
            result.put("status", "UNAVAILABLE");
            result.put("message", "Examples from public routines are not available because " + UNAVAILABLE
                    + ". Your plan above does not depend on them.");
            result.put("items", List.of());
            return result;
        }

        List<Map<String, Object>> items = new ArrayList<>();
        for (SemanticSearchClient.Scored scored : ranked) {
            Map<String, Object> card = new LinkedHashMap<>(data.cards().get(scored.id()));
            card.put("similarity", Math.round(scored.score() * 100) / 100.0);
            items.add(card);
        }
        result.put("status", items.isEmpty() ? "NONE" : "OK");
        result.put("message", items.isEmpty() ? "No public routine is close enough in meaning to what you want." : null);
        result.put("items", items);
        return result;
    }

    // ---- save a plan ----

    @Transactional
    public Map<String, Object> saveGenerated(String email, AssistantSaveRequest request) {
        User user = member(email);
        if (request == null || request.activities() == null || request.activities().isEmpty()) {
            throw new IllegalArgumentException("Keep at least one activity to save");
        }
        if (request.activities().size() > MAX_ACTIVITIES) {
            throw new IllegalArgumentException("A routine can have at most " + MAX_ACTIVITIES + " activities here");
        }

        String days = HabitSchedule.parseDays(request.days());
        List<RoutineHabit> planned = new ArrayList<>();
        int total = 0;

        for (int index = 0; index < request.activities().size(); index++) {
            AssistantSaveRequest.Activity activity = request.activities().get(index);
            String title = activity == null ? null : activity.title();
            try {
                if (activity == null) {
                    throw refused("This activity is empty");
                }
                HabitRules.validate(activity.title(), activity.description(), activity.minutes());
                LocalTime start = HabitSchedule.parseStartTime(activity.startTime());
                String linkDays = start != null && days == null ? ALL_DAYS : days;
                HabitSchedule.checkOverlaps(planned, start, activity.minutes(), linkDays);
                planned.add(link(activity.title().trim(), activity.description(), activity.minutes(), start, linkDays));
                total += activity.minutes();
            } catch (ResponseStatusException refusal) {
                throw new AdaptedActivityException(index + 1,
                        "Activity " + (index + 1) + (title == null ? "" : " \"" + title + "\"") + ": " + refusal.getReason());
            }
        }

        String description = request.description() == null || request.description().isBlank()
                ? "Planned with the CopyMyLife routine assistant." : request.description();
        RoutineRules.validate(request.title(), description, request.category(), total);

        Routine routine = new Routine();
        routine.setTitle(request.title().trim());
        routine.setDescription(description);
        routine.setCategory(request.category().trim());
        routine.setDurationMinutes(total);
        routine.setPublic(Boolean.TRUE.equals(request.isPublic()));
        routine.setUser(user);
        Routine saved = routineRepository.save(routine);

        for (int position = 0; position < planned.size(); position++) {
            RoutineHabit link = planned.get(position);
            Habit habit = link.getHabit();
            habit.setUser(user);
            habit.setRoutine(saved);
            habitRepository.save(habit);
            link.setRoutine(saved);
            link.setPosition(position);
            routineHabitRepository.save(link);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("routineId", saved.getId());
        result.put("title", saved.getTitle());
        result.put("durationMinutes", saved.getDurationMinutes());
        result.put("activities", planned.size());
        result.put("isPublic", saved.isPublic());
        return result;
    }

    // ---- review ----

    private record ReviewData(Routine routine, List<RoutineReviewEngine.Activity> activities, List<String> paused,
                              ResolvedPreferences prefs, Map<Long, HabitDurationAnalyzer.Insight> insights,
                              List<String> goals, boolean running, int habitMinutes) {
    }

    public Map<String, Object> review(String email, Long routineId, ZoneId zone) {
        ReviewData data = readOnly.execute(status -> reviewData(email, routineId, zone, false));
        List<RoutineReviewEngine.GoalCheck> goals = null;
        String goalsUnavailable = null;

        if (data.goals().isEmpty()) {
            goalsUnavailable = "you have not written goals on your profile";
        } else if (data.activities().isEmpty()) {
            goalsUnavailable = "the routine has no activities";
        } else {
            try {
                List<SemanticSearchClient.Item> items = data.activities().stream()
                        .map(a -> new SemanticSearchClient.Item(a.id(), cut(a.title(), 2000))).toList();
                List<List<SemanticSearchClient.Scored>> ranked = semanticSearch.rank(data.goals(), items, 1, -1.0);
                goals = new ArrayList<>();
                for (int i = 0; i < data.goals().size(); i++) {
                    List<SemanticSearchClient.Scored> best = ranked.get(i);
                    String closest = best.isEmpty() ? null : data.activities().stream()
                            .filter(a -> a.id() == best.get(0).id()).map(RoutineReviewEngine.Activity::title).findFirst().orElse(null);
                    goals.add(new RoutineReviewEngine.GoalCheck(data.goals().get(i), closest, best.isEmpty() ? 0 : best.get(0).score()));
                }
            } catch (SemanticSearchClient.Unavailable exception) {
                log.warn("Routine review goal check unavailable: {}", exception.getMessage());
                goalsUnavailable = UNAVAILABLE;
            }
        }

        RoutineReviewEngine.Result result = RoutineReviewEngine.review(new RoutineReviewEngine.Input(
                data.activities(), data.prefs(), data.insights(), goals, goalsUnavailable));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("routine", routineSummary(data.routine(), data.habitMinutes()));
        response.put("running", data.running());
        response.put("preferences", data.prefs().describe());
        response.put("issues", result.issues());
        response.put("changes", result.changes().stream().map(RoutineAssistantService::change).toList());
        response.put("current", result.current().stream().map(RoutineAssistantService::activity).toList());
        response.put("proposed", result.proposed().stream().map(RoutineAssistantService::activity).toList());
        response.put("checked", result.checked());
        List<String> notChecked = new ArrayList<>(result.notChecked());
        if (!data.paused().isEmpty()) {
            notChecked.add("Paused activities are left out: " + String.join(", ", data.paused()));
        }
        response.put("notChecked", notChecked);
        response.put("note", "Suggestions only. Nothing changes until you choose changes and press Apply.");
        return response;
    }

    private ReviewData reviewData(String email, Long routineId, ZoneId zone, boolean withPaused) {
        User user = member(email);
        Routine routine = ownRoutine(user, routineId);
        List<RoutineHabit> links = routineHabitRepository.findByRoutineIdAndRemovedAtIsNullOrderByPositionAsc(routineId);
        List<HabitCompletion> records = history.records(user, LocalDate.now(zone));
        Map<Long, HabitDurationAnalyzer.Insight> insights = history.insights(user, links, records, zone);
        ResolvedPreferences prefs = PersonalizationEngine.resolve(null,
                preferencesRepository.findByUserId(user.getId()).map(RoutineAssistantService::input).orElse(null),
                history.signals(records, zone));

        List<RoutineReviewEngine.Activity> activities = new ArrayList<>();
        List<String> paused = new ArrayList<>();
        int habitMinutes = 0;
        for (RoutineHabit link : links) {
            habitMinutes += minutes(link);
            if (link.isPaused() && !withPaused) {
                paused.add(link.getHabit().getTitle());
                continue;
            }
            activities.add(toActivity(link));
        }

        List<String> goals = Arrays.stream((user.getGoals() == null ? "" : user.getGoals()).split("[,;\\n\\r]+"))
                .map(String::strip).filter(goal -> !goal.isEmpty()).map(goal -> cut(goal, 200)).distinct().limit(10).toList();
        boolean running = routineCompletionRepository.findActiveRun(user.getId())
                .filter(run -> run.getRoutine().getId().equals(routineId)).isPresent();

        return new ReviewData(routine, activities, paused, prefs, insights, goals, running, habitMinutes);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> previewChanges(String email, Long routineId, AssistantChangesRequest request) {
        User user = member(email);
        Routine routine = ownRoutine(user, routineId);
        List<RoutineHabit> links = routineHabitRepository.findByRoutineIdAndRemovedAtIsNullOrderByPositionAsc(routineId);
        List<RoutineReviewEngine.Activity> current = links.stream().map(this::toActivity).toList();
        List<RoutineReviewEngine.Change> changes = changes(request, links);
        List<RoutineReviewEngine.Activity> after = RoutineReviewEngine.apply(current, changes);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("routine", routineSummary(routine, links.stream().mapToInt(RoutineAssistantService::minutes).sum()));
        response.put("activities", after.stream().map(RoutineAssistantService::activity).toList());
        response.put("conflicts", RoutineReviewEngine.conflicts(after));
        response.put("newDurationMinutes", newDuration(routine, links, after));
        return response;
    }

    @Transactional
    public Map<String, Object> applyChanges(String email, Long routineId, AssistantChangesRequest request) {
        User user = userRepository.findByEmailForUpdate(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found"));
        requireMember(user);
        Routine routine = ownRoutine(user, routineId);

        routineCompletionRepository.findActiveRun(user.getId())
                .filter(run -> run.getRoutine().getId().equals(routineId))
                .ifPresent(run -> {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Cannot edit a running routine");
                });

        List<RoutineHabit> links = routineHabitRepository.findByRoutineIdAndRemovedAtIsNullOrderByPositionAsc(routineId);
        List<RoutineReviewEngine.Activity> current = links.stream().map(this::toActivity).toList();
        List<RoutineReviewEngine.Change> changes = changes(request, links);
        if (changes.isEmpty()) {
            throw new IllegalArgumentException("Choose at least one change to apply");
        }
        List<RoutineReviewEngine.Activity> after = RoutineReviewEngine.apply(current, changes);
        List<String> conflicts = RoutineReviewEngine.conflicts(after);
        if (!conflicts.isEmpty()) {
            throw new IllegalArgumentException("These changes would make activities overlap: " + String.join("; ", conflicts)
                    + ". Choose the related changes together.");
        }
        int duration = newDuration(routine, links, after);
        if (duration > RoutineRules.MAX_DURATION_MINUTES) {
            throw new IllegalArgumentException("The routine would last " + duration + " minutes, more than a day");
        }

        Map<Long, RoutineHabit> byId = new HashMap<>();
        links.forEach(link -> byId.put(link.getId(), link));
        Instant now = Instant.now();
        int position = routineHabitRepository.findMaxPositionByRoutineId(routineId) + 1;

        for (RoutineReviewEngine.Activity activity : after) {
            if (activity.id() < 0) {
                RoutineHabit link = link(activity.title(), "Added by the CopyMyLife routine assistant.", activity.minutes(),
                        time(activity.start()), days(activity));
                Habit habit = link.getHabit();
                habit.setUser(user);
                habit.setRoutine(routine);
                habitRepository.save(habit);
                link.setRoutine(routine);
                link.setPosition(position++);
                routineHabitRepository.save(link);
                continue;
            }

            RoutineHabit link = byId.get(activity.id());
            Habit habit = link.getHabit();
            if (activity.minutes() != minutes(link)) {
                if (routineHabitRepository.findByHabitIdAndRemovedAtIsNull(habit.getId()).size() > 1) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "\"" + habit.getTitle()
                            + "\" is used in multiple routines, so its length can't change here");
                }
                AssistantDecision decision = new AssistantDecision();
                decision.setUser(user);
                decision.setRoutineHabit(link);
                decision.setKind(AssistantHistoryService.DURATION);
                decision.setDecision("ACCEPTED");
                decision.setPlannedMinutes(minutes(link));
                decision.setSuggestedMinutes(activity.minutes());
                decision.setDecidedAt(now);
                decisionRepository.save(decision);
                habit.setDurationMinutes(activity.minutes());
                habitRepository.save(habit);
            }
            LocalTime start = time(activity.start());
            if (!Objects.equals(start, link.getStartTime()) || !Objects.equals(days(activity), link.getRepeatDays())) {
                link.setStartTime(start);
                link.setRepeatDays(days(activity));
                routineHabitRepository.save(link);
            }
        }

        int previous = routine.getDurationMinutes() == null ? 0 : routine.getDurationMinutes();
        routine.setDurationMinutes(duration);
        routineRepository.save(routine);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("routineId", routine.getId());
        response.put("previousDurationMinutes", previous);
        response.put("durationMinutes", duration);
        response.put("applied", changes.size());
        response.put("activities", after.stream().map(RoutineAssistantService::activity).toList());
        return response;
    }

    // ---- durations ----

    @Transactional(readOnly = true)
    public Map<String, Object> durationInsights(String email, Long routineId, ZoneId zone) {
        User user = member(email);
        List<Routine> routines = routineId == null
                ? routineRepository.findByUserIdAndDeletedFalse(user.getId())
                : List.of(ownRoutine(user, routineId));
        List<HabitCompletion> records = history.records(user, LocalDate.now(zone));
        List<Map<String, Object>> items = new ArrayList<>();

        for (Routine routine : routines) {
            List<RoutineHabit> links = routineHabitRepository.findByRoutineIdAndRemovedAtIsNullOrderByPositionAsc(routine.getId());
            Map<Long, HabitDurationAnalyzer.Insight> insights = history.insights(user, links, records, zone);
            for (RoutineHabit link : links) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("routineId", routine.getId());
                item.put("routineTitle", routine.getTitle());
                item.put("routineHabitId", link.getId());
                item.put("sharedHabit", routineHabitRepository.findByHabitIdAndRemovedAtIsNull(link.getHabit().getId()).size() > 1);
                item.put("insight", insights.get(link.getId()));
                items.add(item);
            }
        }

        items.sort(Comparator.comparingInt(item -> rank(((HabitDurationAnalyzer.Insight) item.get("insight")).status())));
        long measured = items.stream().filter(i -> ((HabitDurationAnalyzer.Insight) i.get("insight")).measuredRuns() > 0).count();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("items", items);
        response.put("measuredActivities", measured);
        response.put("activities", items.size());
        response.put("minRuns", HabitDurationAnalyzer.MIN_RUNS);
        response.put("note", "A length is known only from the run timer (Start to Complete) or a length you entered after"
                + " completing a habit. Nothing changes until you accept a suggestion.");
        return response;
    }

    @Transactional
    public Map<String, Object> keepDuration(String email, Long routineHabitId, Integer suggestedMinutes, ZoneId zone) {
        User user = member(email);
        RoutineHabit link = routineHabitRepository.findById(routineHabitId)
                .filter(found -> !found.isRemoved() && !found.getRoutine().isDeleted()
                        && found.getRoutine().getUser().getId().equals(user.getId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Activity not found"));
        if (suggestedMinutes != null && (suggestedMinutes < 1 || suggestedMinutes > 1440)) {
            throw new IllegalArgumentException("The suggested length must be between 1 and 1440 minutes");
        }

        AssistantDecision decision = new AssistantDecision();
        decision.setUser(user);
        decision.setRoutineHabit(link);
        decision.setKind(AssistantHistoryService.DURATION);
        decision.setDecision("DECLINED");
        decision.setPlannedMinutes(minutes(link));
        decision.setSuggestedMinutes(suggestedMinutes);
        decision.setDecidedAt(Instant.now());
        decisionRepository.saveAndFlush(decision);

        HabitDurationAnalyzer.Insight insight = history.insights(user, List.of(link),
                history.records(user, LocalDate.now(zone)), zone).get(link.getId());
        return Map.of("routineHabitId", link.getId(), "insight", insight);
    }

    @Transactional
    public Map<String, Object> reportActualMinutes(String email, Long runId, Long routineHabitId, Integer minutes) {
        User user = member(email);
        RoutineCompletion run = routineCompletionRepository.findById(runId)
                .filter(found -> found.getUser().getId().equals(user.getId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Run not found"));
        HabitCompletion record = habitCompletionRepository.findByRoutineCompletionId(run.getId()).stream()
                .filter(found -> found.getRoutineHabit() != null && found.getRoutineHabit().getId().equals(routineHabitId))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "This habit has no record in this run"));

        if (record.currentStatus() != HabitCompletionStatus.COMPLETED) {
            throw new IllegalArgumentException("Enter the actual time after completing the habit");
        }
        if (minutes != null && (minutes < 1 || minutes > 1440)) {
            throw new IllegalArgumentException("The actual time must be between 1 and 1440 minutes");
        }
        record.setReportedMinutes(minutes);
        habitCompletionRepository.save(record);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("runId", run.getId());
        response.put("routineHabitId", routineHabitId);
        response.put("reportedMinutes", record.getReportedMinutes());
        response.put("timerMinutes", AssistantHistoryService.timerMinutes(record));
        return response;
    }

    // ---- daily summary ----

    @Transactional(readOnly = true)
    public DailySummaryEngine.Summary summary(String email, LocalDate date, ZoneId zone) {
        User user = member(email);
        LocalDate today = LocalDate.now(zone);
        LocalDate day = date == null ? today : date;
        if (day.isAfter(today)) {
            throw new IllegalArgumentException("A summary can only be made for today or an earlier day");
        }
        return history.summary(user, day, today, zone);
    }

    // ---- helpers ----

    private User member(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found"));
        requireMember(user);
        return user;
    }

    private static void requireMember(User user) {
        if (!"USER".equals(user.getRole())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Administrator accounts cannot use the routine assistant");
        }
    }

    // A routine of someone else answers exactly like one that does not exist.
    private Routine ownRoutine(User user, Long routineId) {
        return routineRepository.findByIdAndUserIdAndDeletedFalse(routineId, user.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Routine not found"));
    }

    private List<RoutinePlanningEngine.HabitRequest> habits(List<AssistantPlanRequest.Habit> habits) {
        if (habits == null || habits.isEmpty()) {
            throw new IllegalArgumentException("Add at least one activity you want in your day");
        }
        if (habits.size() > MAX_HABITS) {
            throw new IllegalArgumentException("Plan at most " + MAX_HABITS + " activities at a time");
        }
        List<RoutinePlanningEngine.HabitRequest> result = new ArrayList<>();
        for (int index = 0; index < habits.size(); index++) {
            AssistantPlanRequest.Habit habit = habits.get(index);
            String label = "Activity " + (index + 1);
            if (habit == null || habit.title() == null || habit.title().isBlank()) {
                throw new IllegalArgumentException(label + ": give it a name");
            }
            if (habit.title().strip().length() > 100) {
                throw new IllegalArgumentException(label + ": the name must be at most 100 characters");
            }
            if (habit.minutes() == null || habit.minutes() < 5 || habit.minutes() > 720) {
                throw new IllegalArgumentException(label + " \"" + habit.title().strip() + "\": the length must be between 5 and 720 minutes");
            }
            ActivityKind kind = ActivityKind.parse(habit.kind());
            if (kind == ActivityKind.COMMITMENT) {
                throw new IllegalArgumentException(label + ": add fixed commitments under Commitments");
            }
            result.add(new RoutinePlanningEngine.HabitRequest(habit.title().strip(), habit.minutes(), kind,
                    RoutinePlanningEngine.Priority.parse(habit.priority()), Clock.parse(habit.preferredTime(), label + " time")));
        }
        return result;
    }

    private static Set<String> meals(List<String> meals) {
        if (meals == null) {
            return new LinkedHashSet<>(RoutinePlanningEngine.MEALS);
        }
        Set<String> chosen = new LinkedHashSet<>();
        for (String meal : meals) {
            String name = meal == null ? "" : meal.trim().toLowerCase(Locale.ROOT);
            if (!RoutinePlanningEngine.MEALS.contains(name)) {
                throw new IllegalArgumentException("Meals are breakfast, lunch and dinner");
            }
            chosen.add(name);
        }
        return chosen;
    }

    private List<RoutinePlanningEngine.Commitment> commitments(List<AssistantPreferencesRequest.Commitment> commitments) {
        if (commitments == null) {
            return List.of();
        }
        if (commitments.size() > MAX_COMMITMENTS) {
            throw new IllegalArgumentException("Add at most " + MAX_COMMITMENTS + " commitments");
        }
        List<RoutinePlanningEngine.Commitment> result = new ArrayList<>();
        for (AssistantPreferencesRequest.Commitment commitment : commitments) {
            if (commitment == null || commitment.title() == null || commitment.title().isBlank()) {
                throw new IllegalArgumentException("Give every commitment a name");
            }
            String title = commitment.title().strip();
            if (title.length() > 100) {
                throw new IllegalArgumentException("A commitment name must be at most 100 characters");
            }
            Integer start = Clock.parse(commitment.start(), title + " start");
            Integer end = Clock.parse(commitment.end(), title + " end");
            if (start == null || end == null) {
                throw new IllegalArgumentException("Give " + title + " a start and an end time");
            }
            if (end <= start) {
                throw new IllegalArgumentException(title + " must end after it starts, on the same day");
            }
            result.add(new RoutinePlanningEngine.Commitment(title, start, end));
        }
        return result;
    }

    private String writeCommitments(List<RoutinePlanningEngine.Commitment> commitments) {
        try {
            return json.writeValueAsString(commitments.stream().map(c -> Map.of("title", c.title(),
                    "start", Clock.format(c.start()), "end", Clock.format(c.end()))).toList());
        } catch (JacksonException exception) {
            throw new IllegalStateException("Could not store the commitments", exception);
        }
    }

    private List<RoutinePlanningEngine.Commitment> readCommitments(String stored) {
        return commitments(readCommitmentRequests(stored));
    }

    private List<AssistantPreferencesRequest.Commitment> readCommitmentRequests(String stored) {
        if (stored == null || stored.isBlank()) {
            return List.of();
        }
        try {
            List<Map<String, String>> list = json.readValue(stored, new TypeReference<>() {
            });
            return list.stream().map(item -> new AssistantPreferencesRequest.Commitment(item.get("title"),
                    item.get("start"), item.get("end"))).toList();
        } catch (JacksonException exception) {
            log.warn("Stored assistant commitments could not be read");
            return List.of();
        }
    }

    private static PreferenceInput input(AssistantPreferencesRequest request) {
        return new PreferenceInput(
                Clock.parse(request.wake(), "Wake-up time"),
                Clock.parse(request.sleep(), "Sleep time"),
                Clock.parse(request.availableFrom(), "Available from"),
                Clock.parse(request.availableTo(), "Available until"),
                Clock.parse(request.breakfast(), "Breakfast time"),
                Clock.parse(request.lunch(), "Lunch time"),
                Clock.parse(request.dinner(), "Dinner time"),
                request.mealMinutes(),
                Clock.parse(request.exercise(), "Exercise time"),
                request.focusBlockMinutes(),
                request.breakEveryMinutes(),
                request.breakMinutes(),
                PersonalizationEngine.normaliseRestFrequency(request.restFrequency()),
                Intensity.parse(request.intensity()));
    }

    private static PreferenceInput input(AssistantPreferences saved) {
        return new PreferenceInput(minute(saved.getWakeTime()), minute(saved.getSleepTime()),
                minute(saved.getAvailableFrom()), minute(saved.getAvailableTo()), minute(saved.getBreakfastTime()),
                minute(saved.getLunchTime()), minute(saved.getDinnerTime()), saved.getMealMinutes(),
                minute(saved.getExerciseTime()), saved.getFocusBlockMinutes(), saved.getBreakEveryMinutes(),
                saved.getBreakMinutes(), saved.getRestFrequency(),
                saved.getIntensity() == null ? null : Intensity.parse(saved.getIntensity()));
    }

    private Map<String, Object> describeSaved(AssistantPreferences saved) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("wake", format(saved.getWakeTime()));
        map.put("sleep", format(saved.getSleepTime()));
        map.put("availableFrom", format(saved.getAvailableFrom()));
        map.put("availableTo", format(saved.getAvailableTo()));
        map.put("breakfast", format(saved.getBreakfastTime()));
        map.put("lunch", format(saved.getLunchTime()));
        map.put("dinner", format(saved.getDinnerTime()));
        map.put("mealMinutes", saved.getMealMinutes());
        map.put("exercise", format(saved.getExerciseTime()));
        map.put("focusBlockMinutes", saved.getFocusBlockMinutes());
        map.put("breakEveryMinutes", saved.getBreakEveryMinutes());
        map.put("breakMinutes", saved.getBreakMinutes());
        map.put("restFrequency", saved.getRestFrequency());
        map.put("intensity", saved.getIntensity());
        map.put("commitments", readCommitmentRequests(saved.getCommitments()));
        return map;
    }

    // Turns approved changes into the engine's, checking every field like an edit of the routine.
    private static List<RoutineReviewEngine.Change> changes(AssistantChangesRequest request, List<RoutineHabit> links) {
        List<AssistantChangesRequest.Change> asked = request == null || request.changes() == null ? List.of() : request.changes();
        if (asked.size() > MAX_CHANGES) {
            throw new IllegalArgumentException("Apply at most " + MAX_CHANGES + " changes at a time");
        }
        Set<Long> known = new HashSet<>();
        links.forEach(link -> known.add(link.getId()));
        List<RoutineReviewEngine.Change> result = new ArrayList<>();
        long nextNew = -1;

        for (AssistantChangesRequest.Change change : asked) {
            if (change == null || change.type() == null) {
                throw new IllegalArgumentException("A change has no type");
            }
            String type = change.type().trim().toUpperCase(Locale.ROOT);
            List<String> days = HabitSchedule.dayList(HabitSchedule.parseDays(change.days()));
            switch (type) {
                case "MOVE" -> {
                    requireKnown(change, known);
                    Integer start = Clock.parse(change.toStart(), "New start time");
                    result.add(new RoutineReviewEngine.Change(change.id(), type, change.activityId(), null, null, start,
                            null, null, start != null && days.isEmpty() ? RoutineReviewEngine.DAYS : days, null));
                }
                case "RESIZE" -> {
                    requireKnown(change, known);
                    HabitRules.validateDuration(change.toMinutes());
                    result.add(new RoutineReviewEngine.Change(change.id(), type, change.activityId(), null, null, null,
                            null, change.toMinutes(), null, null));
                }
                case "ADD" -> {
                    ActivityKind kind = ActivityKind.parse(change.kind());
                    if (kind != ActivityKind.BREAK && kind != ActivityKind.MEAL) {
                        throw new IllegalArgumentException("The assistant only adds breaks and meals");
                    }
                    HabitRules.validate(change.title(), null, change.toMinutes());
                    if (change.title().strip().length() > 100) {
                        throw new IllegalArgumentException("A new activity's name must be at most 100 characters");
                    }
                    Integer start = Clock.parse(change.toStart(), "Start time");
                    if (start == null) {
                        throw new IllegalArgumentException("A new activity needs a start time");
                    }
                    result.add(new RoutineReviewEngine.Change(change.id(), type, nextNew--, change.title().strip(), null,
                            start, null, change.toMinutes(), days, kind));
                }
                default -> throw new IllegalArgumentException("Unknown change type \"" + change.type() + "\"");
            }
        }
        return result;
    }

    private static void requireKnown(AssistantChangesRequest.Change change, Set<Long> known) {
        if (change.activityId() == null || !known.contains(change.activityId())) {
            throw new IllegalArgumentException("A change names an activity that is not in this routine");
        }
    }

    // The routine stays as long as its activities when it was before; it grows when they outgrow it.
    private static int newDuration(Routine routine, List<RoutineHabit> links, List<RoutineReviewEngine.Activity> after) {
        int before = links.stream().mapToInt(RoutineAssistantService::minutes).sum();
        int total = after.stream().mapToInt(RoutineReviewEngine.Activity::minutes).sum();
        int duration = routine.getDurationMinutes() == null ? 0 : routine.getDurationMinutes();
        return duration == before || total > duration ? total : duration;
    }

    private RoutineReviewEngine.Activity toActivity(RoutineHabit link) {
        String title = link.getHabit().getTitle();
        ActivityKind kind = ActivityKind.recognise(title);
        boolean shared = routineHabitRepository.findByHabitIdAndRemovedAtIsNull(link.getHabit().getId()).size() > 1;
        return new RoutineReviewEngine.Activity(link.getId(), title, kind, true, minutes(link), minute(link.getStartTime()),
                HabitSchedule.dayList(link.getRepeatDays()), shared);
    }

    private static Map<String, Object> activity(RoutineReviewEngine.Activity activity) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", activity.id());
        map.put("isNew", activity.id() < 0);
        map.put("title", activity.title());
        map.put("kind", activity.kind().name());
        map.put("kindRecognised", activity.kindRecognised());
        map.put("minutes", activity.minutes());
        map.put("start", Clock.formatNullable(activity.start()));
        map.put("end", activity.start() == null ? null : Clock.format(activity.start() + activity.minutes()));
        map.put("days", activity.days() == null ? List.of() : activity.days());
        map.put("sharedHabit", activity.sharedHabit());
        return map;
    }

    private static Map<String, Object> change(RoutineReviewEngine.Change change) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", change.id());
        map.put("type", change.type());
        map.put("activityId", change.activityId());
        map.put("title", change.title());
        map.put("fromStart", Clock.formatNullable(change.fromStart()));
        map.put("toStart", Clock.formatNullable(change.toStart()));
        map.put("fromMinutes", change.fromMinutes());
        map.put("toMinutes", change.toMinutes());
        map.put("days", change.days() == null ? List.of() : change.days());
        map.put("kind", change.kind() == null ? null : change.kind().name());
        return map;
    }

    private static Map<String, Object> plannedActivity(RoutinePlanningEngine.PlannedActivity activity) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("title", activity.title());
        map.put("kind", activity.kind().name());
        map.put("start", Clock.format(activity.start()));
        map.put("end", Clock.format(activity.end()));
        map.put("minutes", activity.minutes());
        map.put("endsNextDay", activity.end() > Clock.DAY);
        map.put("fixed", activity.fixed());
        map.put("save", activity.save());
        map.put("reason", activity.reason());
        return map;
    }

    private static Map<String, Object> routineSummary(Routine routine, int habitMinutes) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", routine.getId());
        map.put("title", routine.getTitle());
        map.put("durationMinutes", routine.getDurationMinutes());
        map.put("habitMinutes", habitMinutes);
        return map;
    }

    private static RoutineHabit link(String title, String description, int minutes, LocalTime start, String days) {
        Habit habit = new Habit();
        habit.setTitle(title.trim());
        habit.setDescription(description);
        habit.setDurationMinutes(minutes);
        RoutineHabit link = new RoutineHabit();
        link.setHabit(habit);
        link.setStartTime(start);
        link.setRepeatDays(days);
        return link;
    }

    private static String days(RoutineReviewEngine.Activity activity) {
        List<String> days = activity.days() == null ? List.of() : activity.days();
        if (days.isEmpty()) {
            return activity.start() == null ? null : ALL_DAYS;
        }
        return HabitSchedule.parseDays(days);
    }

    private static int rank(HabitDurationAnalyzer.Status status) {
        return switch (status) {
            case OFTEN_LONGER, OFTEN_SHORTER -> 0;
            case ON_PLAN, KEPT_BY_YOU -> 1;
            case NOT_ENOUGH_DATA -> 2;
            case NO_DATA -> 3;
        };
    }

    private static int minutes(RoutineHabit link) {
        Integer value = link.getHabit().getDurationMinutes();
        return value == null ? 0 : value;
    }

    private static Integer minute(LocalTime time) {
        return time == null ? null : time.getHour() * 60 + time.getMinute();
    }

    private static LocalTime time(Integer minute) {
        return minute == null ? null : LocalTime.of(Math.floorMod(minute, Clock.DAY) / 60, Math.floorMod(minute, Clock.DAY) % 60);
    }

    private static String format(LocalTime time) {
        return time == null ? null : HabitSchedule.format(time);
    }

    private static ResponseStatusException refused(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static List<String> nonBlank(String... values) {
        return Arrays.stream(values).filter(value -> value != null && !value.isBlank()).map(String::strip).toList();
    }

    private static String cut(String value, int length) {
        String text = value == null ? "" : value.strip();
        return text.length() <= length ? text : text.substring(0, length);
    }
}
