package com.copymylife.copymylife.service;

import com.copymylife.copymylife.dto.LifestyleMatchResponse;
import com.copymylife.copymylife.model.Routine;
import com.copymylife.copymylife.model.RoutineHabit;
import com.copymylife.copymylife.model.User;
import com.copymylife.copymylife.repository.RoutineHabitRepository;
import com.copymylife.copymylife.repository.RoutineRepository;
import com.copymylife.copymylife.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

// US-26: Find Similar People. The user's own profile and routines are compared with what other
// members show on their public profile: profession, bio, interests, goals, and their public
// routines with the activities those run now. Banned members, profiles an administrator hid,
// private, deleted or hidden routines and removed or paused activities are never read for
// another member, and nothing else (no e-mail, notes or account data) is sent to the matching
// service. Everything is read fresh on each request, so a changed profile or routine counts at
// once. The ranking itself comes from the Python service (ai/ai_engine.py).
@Service
public class LifestyleMatchingService {

    private static final Logger log = LoggerFactory.getLogger(LifestyleMatchingService.class);

    static final int MAX_CANDIDATES = 300;
    static final int MAX_MATCHES = 12;
    // Below this a "match" is no more alike than two unrelated descriptions (about 0.16-0.22 in
    // the examples of ai/test_similarity.py), so it is not suggested.
    static final double MIN_SCORE = 0.30;
    private static final int ROUTINES_PER_PERSON = 20;
    private static final int ACTIVITIES_PER_ROUTINE = 50;
    private static final int ROUTINES_ON_CARD = 3;
    private static final Map<String, Integer> WEIGHTS = Map.of("semantic", 60, "schedule", 25, "interests", 15);
    private static final Set<String> PARTS_OF_DAY = Set.of("morning", "afternoon", "evening", "night");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    // The account the built-in Explore routines belong to (see ExploreRoutineSeeder).
    private static final String SYSTEM_EMAIL = "explore@copymylife.local";

    static final String UNAVAILABLE =
            "AI matching is not available right now. Everything else works as usual; please try again later.";

    private final UserRepository userRepository;
    private final RoutineRepository routineRepository;
    private final RoutineHabitRepository routineHabitRepository;
    private final LifestyleMatchingClient client;
    private final TransactionTemplate readOnly;

    public LifestyleMatchingService(
            UserRepository userRepository,
            RoutineRepository routineRepository,
            RoutineHabitRepository routineHabitRepository,
            LifestyleMatchingClient client,
            PlatformTransactionManager transactionManager
    ) {
        this.userRepository = userRepository;
        this.routineRepository = routineRepository;
        this.routineHabitRepository = routineHabitRepository;
        this.client = client;
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
    }

    // What one member offers for matching, read inside the transaction; the card part is shown.
    private record Lifestyle(Map<String, Object> data, boolean hasData, LifestyleMatchResponse.Card card) {
    }

    private record Gathered(Lifestyle me, Map<Long, Lifestyle> candidates) {
    }

    public LifestyleMatchResponse findMatches(String email) {
        // The database is read first and let go before the matching service is asked.
        Gathered gathered = readOnly.execute(status -> gather(email));

        if (!gathered.me().hasData()) {
            return state("NEEDS_DATA",
                    "Add your interests, goals or a routine with activities to your profile, then try again.");
        }

        if (gathered.candidates().isEmpty()) {
            return state("NO_CANDIDATES", "No other members share a lifestyle on their profile yet.");
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("user", gathered.me().data());
        payload.put("candidates", gathered.candidates().values().stream().map(Lifestyle::data).toList());
        payload.put("limit", MAX_MATCHES);
        payload.put("minScore", MIN_SCORE);

        JsonNode reply;
        try {
            reply = client.match(payload);
        } catch (LifestyleMatchingClient.AiUnavailableException exception) {
            log.warn("Lifestyle matching unavailable: {}", exception.getMessage());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, UNAVAILABLE);
        }

        if ("insufficient_data".equals(reply.path("status").asString())) {
            return state("NEEDS_DATA",
                    "Add your interests, goals or a routine with activities to your profile, then try again.");
        }

        List<LifestyleMatchResponse.Card> cards = cards(reply, gathered.candidates());

        if (cards.isEmpty()) {
            return state("NO_MATCHES", "No one with a lifestyle close to yours yet. Add more about your days, or try again later.");
        }

        return new LifestyleMatchResponse("OK", null, WEIGHTS, cards);
    }

    private Gathered gather(String email) {
        User me = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found"));

        if (!"USER".equals(me.getRole())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Administrator accounts cannot use lifestyle matching");
        }

        // The user's own routines, private ones too: only the user sees what is compared.
        List<Routine> myRoutines = routineRepository.findByUserIdAndDeletedFalse(me.getId()).stream()
                .sorted(Comparator.comparing(Routine::getId).reversed())
                .limit(ROUTINES_PER_PERSON)
                .toList();

        List<User> others = userRepository.findLifestyleMatchCandidates(me.getId(), SYSTEM_EMAIL, PageRequest.of(0, MAX_CANDIDATES));
        Map<Long, List<Routine>> publicRoutines = others.isEmpty() ? Map.of() :
                routineRepository.findPublicRoutinesByUserIdIn(others.stream().map(User::getId).toList()).stream()
                        .collect(Collectors.groupingBy(routine -> routine.getUser().getId()));

        List<Long> routineIds = new ArrayList<>(myRoutines.stream().map(Routine::getId).toList());
        publicRoutines.values().forEach(list -> list.stream().limit(ROUTINES_PER_PERSON).forEach(r -> routineIds.add(r.getId())));
        Map<Long, List<RoutineHabit>> activities = activities(routineIds);

        Lifestyle mine = lifestyle(me, myRoutines, activities);
        Map<Long, Lifestyle> candidates = new LinkedHashMap<>();

        for (User other : others) {
            List<Routine> shared = publicRoutines.getOrDefault(other.getId(), List.of()).stream()
                    .limit(ROUTINES_PER_PERSON)
                    .toList();
            Lifestyle lifestyle = lifestyle(other, shared, activities);

            if (lifestyle.hasData()) {
                candidates.put(other.getId(), lifestyle);
            }
        }

        return new Gathered(mine, candidates);
    }

    private Map<Long, List<RoutineHabit>> activities(Collection<Long> routineIds) {
        if (routineIds.isEmpty()) {
            return Map.of();
        }

        return routineHabitRepository.findRunningWithHabitByRoutineIdIn(routineIds).stream()
                .collect(Collectors.groupingBy(link -> link.getRoutine().getId()));
    }

    private Lifestyle lifestyle(User user, List<Routine> routines, Map<Long, List<RoutineHabit>> activities) {
        List<Map<String, Object>> routineData = new ArrayList<>();

        for (Routine routine : routines) {
            List<Map<String, Object>> activityData = new ArrayList<>();

            for (RoutineHabit link : activities.getOrDefault(routine.getId(), List.of())) {
                if (activityData.size() == ACTIVITIES_PER_ROUTINE) {
                    break;
                }

                Map<String, Object> activity = new LinkedHashMap<>();
                activity.put("title", cut(link.getHabit().getTitle(), 200));
                activity.put("startTime", link.getStartTime() == null ? null : link.getStartTime().format(TIME));
                activity.put("repeatDays", HabitSchedule.dayList(link.getRepeatDays()));
                activityData.add(activity);
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("title", cut(routine.getTitle(), 200));
            data.put("category", cut(routine.getCategory(), 100));
            data.put("description", cut(routine.getDescription(), 1000));
            data.put("activities", activityData);
            routineData.add(data);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", user.getId());
        data.put("interests", cut(user.getInterests(), 1000));
        data.put("goals", cut(user.getGoals(), 1000));
        data.put("bio", cut(user.getBio(), 1000));
        data.put("profession", cut(user.getProfession(), 200));
        data.put("routines", routineData);

        boolean hasData = !(blank(user.getInterests()) && blank(user.getGoals()) && blank(user.getBio())
                && blank(user.getProfession())) || !routineData.isEmpty();

        List<LifestyleMatchResponse.RoutineLink> links = routines.stream()
                .limit(ROUTINES_ON_CARD)
                .map(routine -> new LifestyleMatchResponse.RoutineLink(routine.getId(), routine.getTitle(), routine.getCategory()))
                .toList();

        LifestyleMatchResponse.Card card = new LifestyleMatchResponse.Card(
                user.getId(), user.getName(), user.getUsername(), user.getAvatarUrl(), user.getProfession(),
                0, Map.of(), List.of(), List.of(), List.of(), List.of(), links);

        return new Lifestyle(data, hasData, card);
    }

    // Only people who were sent, once each, with a sensible score; the service's order is kept.
    private List<LifestyleMatchResponse.Card> cards(JsonNode reply, Map<Long, Lifestyle> candidates) {
        List<LifestyleMatchResponse.Card> cards = new ArrayList<>();
        Set<Long> seen = new HashSet<>();

        for (JsonNode item : reply.path("matches")) {
            JsonNode id = item.path("userId");
            JsonNode score = item.path("score");

            if (!id.isIntegralNumber() || !score.isNumber()) {
                continue;
            }

            Lifestyle candidate = candidates.get(id.longValue());
            double value = score.doubleValue();

            if (candidate == null || !seen.add(id.longValue()) || !(value >= MIN_SCORE && value <= 1.0)) {
                continue;
            }

            Map<String, Integer> components = new LinkedHashMap<>();
            for (String part : List.of("semantic", "schedule", "interests")) {
                JsonNode component = item.path("components").path(part);
                components.put(part, component.isNumber() ? percent(component.doubleValue()) : null);
            }

            JsonNode factors = item.path("factors");
            LifestyleMatchResponse.Card card = candidate.card();

            cards.add(new LifestyleMatchResponse.Card(
                    card.userId(), card.name(), card.username(), card.avatarUrl(), card.profession(),
                    percent(value), components,
                    pairs(factors.path("sharedInterests")),
                    pairs(factors.path("similarActivities")),
                    words(factors.path("sharedTimesOfDay"), PARTS_OF_DAY::contains),
                    words(factors.path("sharedDays"), HabitSchedule.DAYS::contains),
                    card.publicRoutines()));

            if (cards.size() == MAX_MATCHES) {
                break;
            }
        }

        return cards;
    }

    private static List<LifestyleMatchResponse.Pair> pairs(JsonNode list) {
        List<LifestyleMatchResponse.Pair> pairs = new ArrayList<>();

        for (JsonNode pair : list) {
            String mine = cut(pair.path("mine").asString(), 200);
            String theirs = cut(pair.path("theirs").asString(), 200);

            if (!mine.isBlank() && !theirs.isBlank() && pairs.size() < 5) {
                pairs.add(new LifestyleMatchResponse.Pair(mine, theirs));
            }
        }

        return pairs;
    }

    private static List<String> words(JsonNode list, Function<String, Boolean> allowed) {
        List<String> words = new ArrayList<>();

        for (JsonNode word : list) {
            if (allowed.apply(word.asString()) && !words.contains(word.asString())) {
                words.add(word.asString());
            }
        }

        return words;
    }

    private static LifestyleMatchResponse state(String status, String message) {
        return new LifestyleMatchResponse(status, message, WEIGHTS, List.of());
    }

    private static int percent(double value) {
        return (int) Math.round(Math.max(0, Math.min(1, value)) * 100);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String cut(String value, int length) {
        if (value == null) {
            return "";
        }

        String trimmed = value.strip();
        return trimmed.length() <= length ? trimmed : trimmed.substring(0, length);
    }
}
