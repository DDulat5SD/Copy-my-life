package com.copymylife.copymylife.assistant;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Fills the generator form from a sentence such as "I wake up at 07:00, study Java, go to the gym,
// need lunch at 13:00 and prefer frequent breaks". It is a short list of patterns, not a language
// model: it only recognises the phrases below and lists exactly what it took from the text, so
// the user can check and correct the form before anything is planned.
public final class RequestParser {

    public record Habit(String title, int minutes, ActivityKind kind, Integer preferredTime) {
    }

    public record Commitment(String title, int start, int end) {
    }

    public record Parsed(Map<String, Object> preferences, List<Habit> habits, List<Commitment> commitments,
                         List<String> recognised) {
    }

    private static final String TIME = "(\\d{1,2})(?:[:.](\\d{2}))?\\s*(am|pm)?";
    private static final Pattern WAKE = Pattern.compile("(?:wake(?:s)? up|get up|wake|rise|просыпаюсь|встаю|оянамын|тұрамын)\\D{0,12}?" + TIME);
    private static final Pattern SLEEP = Pattern.compile("(?:go to (?:bed|sleep)|sleep|bed|bedtime|ложусь спать|сплю|ұйықтаймын)\\D{0,12}?" + TIME);
    private static final Pattern MEAL = Pattern.compile("(breakfast|lunch|dinner|завтрак|обед|ужин)\\D{0,12}?" + TIME);
    private static final Pattern RANGE = Pattern.compile(
            "(work|job|school|university|uni|classes|lectures|college|office|работа|учеба|университет|жұмыс|университет|сабақ)"
                    + "\\D{0,16}?" + TIME + "\\s*(?:-|–|to|until|till|до)\\s*" + TIME);
    private static final Pattern STUDY = Pattern.compile("(?:study|studying|learn|learning|practi[sc]e)\\s+([\\p{L}+#]+(?:\\s+[\\p{L}+#]+)?)");
    private static final Pattern MINUTES = Pattern.compile("(\\d{1,3})\\s*(?:min|minutes|мин)");
    private static final Pattern HOURS = Pattern.compile("(\\d(?:[.,]5)?)\\s*(?:h|hours?|час)");
    private static final Pattern AT = Pattern.compile("\\bat\\s+" + TIME);
    private static final List<String> STOP = List.of("and", "or", "then", "for", "every", "at", "in", "with", "go", "need");

    private RequestParser() {
    }

    public static Parsed parse(String text) {
        String lower = text == null ? "" : text.toLowerCase(Locale.ROOT);
        Map<String, Object> preferences = new LinkedHashMap<>();
        List<Habit> habits = new ArrayList<>();
        List<Commitment> commitments = new ArrayList<>();
        List<String> recognised = new ArrayList<>();

        time(WAKE, lower, "wake", "Wake-up", preferences, recognised);
        time(SLEEP, lower, "sleep", "Sleep", preferences, recognised);

        Matcher meal = MEAL.matcher(lower);
        while (meal.find()) {
            String name = switch (meal.group(1)) {
                case "завтрак" -> "breakfast";
                case "обед" -> "lunch";
                case "ужин" -> "dinner";
                default -> meal.group(1);
            };
            Integer minute = toMinutes(meal, 2);
            if (minute != null && !preferences.containsKey(name)) {
                preferences.put(name, Clock.format(minute));
                recognised.add(capitalise(name) + " at " + Clock.format(minute));
            }
        }

        if (lower.matches("(?s).*(frequent|many|lots of|regular|short) breaks.*|.*break(s)? often.*|.*частые перерывы.*|.*жиі үзіліс.*")) {
            preferences.put("restFrequency", "FREQUENT");
            recognised.add("Frequent breaks");
        } else if (lower.matches("(?s).*(few|rare|long) breaks.*|.*long (work|focus|study) sessions.*|.*longer (work|focus|study) sessions.*")) {
            preferences.put("restFrequency", "RARE");
            recognised.add("Long focus sessions with fewer breaks");
        }

        Matcher range = RANGE.matcher(lower);
        while (range.find()) {
            Integer start = toMinutes(range, 2);
            Integer end = toMinutes(range, 5);
            if (start != null && end != null && end > start) {
                String title = capitalise(range.group(1));
                commitments.add(new Commitment(title, start, end));
                recognised.add(title + " " + Clock.format(start) + "–" + Clock.format(end) + " (fixed)");
            }
        }

        Matcher study = STUDY.matcher(lower);
        while (study.find()) {
            List<String> words = new ArrayList<>();
            for (String word : study.group(1).split("\\s+")) {
                if (STOP.contains(word)) {
                    break;
                }
                words.add(word);
            }
            if (words.isEmpty()) {
                continue;
            }
            String subject = String.join(" ", words.stream().map(RequestParser::capitalise).toList());
            String title = subject + " Study";
            int minutes = lengthNear(lower, study.end(), 60);
            habits.add(new Habit(title, minutes, ActivityKind.STUDY, null));
            recognised.add(title + " " + minutes + " min");
        }

        Matcher gym = Pattern.compile("\\b(gym|workout|work out|exercise|training|run|running|jog|yoga|swim|swimming|спортзал|тренировка|жаттығу)\\b")
                .matcher(lower);
        if (gym.find()) {
            String word = gym.group(1);
            String title = switch (word) {
                case "gym", "спортзал" -> "Gym";
                case "run", "running", "jog" -> "Running";
                case "yoga" -> "Yoga";
                case "swim", "swimming" -> "Swimming";
                default -> "Workout";
            };
            Integer at = timeNear(lower, gym.end());
            int minutes = lengthNear(lower, gym.end(), 60);
            habits.add(new Habit(title, minutes, ActivityKind.EXERCISE, at));
            recognised.add(title + " " + minutes + " min" + (at == null ? "" : " at " + Clock.format(at)));
        }

        for (String[] simple : new String[][]{
                {"read", "Reading", "PERSONAL"}, {"meditat", "Meditation", "PERSONAL"},
                {"homework", "Homework", "STUDY"}, {"walk", "Walk", "EXERCISE"}}) {
            if (lower.contains(simple[0]) && habits.stream().noneMatch(h -> h.title().equals(simple[1]))) {
                int index = lower.indexOf(simple[0]);
                int minutes = lengthNear(lower, index + simple[0].length(), 30);
                habits.add(new Habit(simple[1], minutes, ActivityKind.valueOf(simple[2]), null));
                recognised.add(simple[1] + " " + minutes + " min");
            }
        }

        return new Parsed(preferences, habits, commitments, recognised);
    }

    private static void time(Pattern pattern, String text, String key, String label,
                             Map<String, Object> preferences, List<String> recognised) {
        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            Integer minute = toMinutes(matcher, 1);
            if (minute != null) {
                preferences.put(key, Clock.format(minute));
                recognised.add(label + " " + Clock.format(minute));
            }
        }
    }

    // A length written right after the activity ("study Java for 2 hours", "gym 45 min").
    private static int lengthNear(String text, int from, int fallback) {
        String after = text.substring(from, Math.min(text.length(), from + 24));
        int cut = after.indexOf(',');
        if (cut >= 0) {
            after = after.substring(0, cut);
        }
        Matcher minutes = MINUTES.matcher(after);
        if (minutes.find()) {
            return Math.max(5, Math.min(600, Integer.parseInt(minutes.group(1))));
        }
        Matcher hours = HOURS.matcher(after);
        if (hours.find()) {
            return Math.round(Float.parseFloat(hours.group(1).replace(',', '.')) * 60);
        }
        return fallback;
    }

    private static Integer timeNear(String text, int from) {
        String after = text.substring(from, Math.min(text.length(), from + 20));
        int cut = after.indexOf(',');
        if (cut >= 0) {
            after = after.substring(0, cut);
        }
        Matcher at = AT.matcher(after);
        return at.find() ? toMinutes(at, 1) : null;
    }

    private static Integer toMinutes(Matcher matcher, int group) {
        int hour = Integer.parseInt(matcher.group(group));
        int minute = matcher.group(group + 1) == null ? 0 : Integer.parseInt(matcher.group(group + 1));
        String half = matcher.group(group + 2);
        if ("pm".equals(half) && hour < 12) {
            hour += 12;
        } else if ("am".equals(half) && hour == 12) {
            hour = 0;
        }
        return hour > 23 || minute > 59 ? null : hour * 60 + minute;
    }

    private static String capitalise(String word) {
        return word.isEmpty() ? word : word.substring(0, 1).toUpperCase(Locale.ROOT) + word.substring(1);
    }
}
