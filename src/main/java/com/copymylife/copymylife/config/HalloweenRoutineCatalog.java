package com.copymylife.copymylife.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class HalloweenRoutineCatalog {

    public static final String CATEGORY = "Halloween";

    private static final int[] HABIT_DURATIONS = {35, 30, 30, 30, 35, 30, 30};

    public record HabitSeed(String title, String description, int durationMinutes) {
    }

    public record RoutineSeed(
            String authorName,
            String title,
            String description,
            String imageUrl,
            List<HabitSeed> habits
    ) {
        public int durationMinutes() {
            return habits.stream().mapToInt(HabitSeed::durationMinutes).sum();
        }
    }

    public static final List<RoutineSeed> ROUTINES = List.of(
            routine(
                    "Billy",
                    "The Puzzle Master's Schedule",
                    "An imaginative day of puzzles, creative challenges, and problem-solving inspired by the iconic puppet.",
                    "img/halloween/01-billy.jpg",
                    "Wake up and plan the day", "Set your goals for the day and choose the challenges that matter most.",
                    "Solve challenging puzzles", "Warm up your mind with logic grids, riddles and brain teasers.",
                    "Design a new escape room", "Sketch a room layout, then connect its clues into one fair storyline.",
                    "Take a lunch break", "Step away from the workbench and refuel with a proper meal.",
                    "Test mechanical puzzles", "Try each mechanism by hand and note anything that jams or confuses.",
                    "Practice logical thinking", "Work through deduction exercises to sharpen careful decision-making.",
                    "Review today's challenges", "Look back at what you solved and pick one idea to improve tomorrow."
            ),
            routine(
                    "Ghostface",
                    "Behind the Mask",
                    "A movie-lover\u2019s routine filled with horror classics, voice acting, and mystery-themed entertainment.",
                    "img/halloween/02-ghostface.jpg",
                    "Wake up and check messages", "Start slowly, reply to anything urgent and plan your watch list.",
                    "Watch horror movie trailers", "Sample new trailers and note the ones worth seeing in full.",
                    "Explore famous movie mysteries", "Read about unsolved film mysteries and the stories behind them.",
                    "Have lunch", "Take a screen-free break and enjoy a relaxed midday meal.",
                    "Practice voice acting", "Rehearse tone, pacing and a few dramatic lines out loud.",
                    "Organize a horror movie marathon", "Choose a theme, line up the films and invite friends to join.",
                    "Watch a classic slasher movie", "Wind down with a genre favourite and notice how the tension is built."
            ),
            routine(
                    "Michael Myers",
                    "The Silent Routine",
                    "A quiet, minimalist day focused on walking, organization, and reflection.",
                    "img/halloween/03-michael-myers.jpg",
                    "Wake up in complete silence", "Leave your phone aside and enjoy the first minutes of a calm morning.",
                    "Take a morning walk", "Walk at a steady pace to wake up your body and clear your head.",
                    "Clean and organize the house", "Tidy one room at a time until the whole space feels in order.",
                    "Have a quiet lunch", "Eat slowly without screens or distractions and let your mind rest.",
                    "Explore the neighborhood", "Take an unhurried stroll and notice details you usually pass by.",
                    "Watch the sunset", "Find a quiet spot outdoors and watch the light fade in the evening.",
                    "Prepare for another quiet day", "Lay out what you need for tomorrow and settle in for an early night."
            ),
            routine(
                    "Chucky",
                    "Not Your Average Doll",
                    "A playful day of costumes, creative projects, and friendly games.",
                    "img/halloween/04-chucky.jpg",
                    "Wake up and fix my hair", "Start with a little grooming so you feel ready for a playful day.",
                    "Choose today's outfit", "Pick a bright, comfortable outfit that fits today's mood.",
                    "Play board games", "Set up a favourite game and enjoy some friendly strategy.",
                    "Take a snack break", "Pause for a light snack and a drink to keep your energy up.",
                    "Explore the toy collection", "Sort through your collection and rediscover some old favourites.",
                    "Challenge friends to games", "Invite friends for a few rounds and keep the competition lighthearted.",
                    "Watch a comedy horror movie", "Finish the day laughing along with a fun, not-too-scary film."
            ),
            routine(
                    "Pennywise",
                    "A Clown's Daily Life",
                    "A theatrical routine of makeup, balloon art, and Halloween decorations.",
                    "img/halloween/05-pennywise.jpg",
                    "Wake up and prepare my costume", "Check your outfit, mend small details and lay out everything you need.",
                    "Apply clown makeup", "Practise steady, expressive face painting in front of a mirror.",
                    "Practice balloon tricks", "Twist simple shapes first, then try one new balloon design.",
                    "Take a lunch break", "Rest your hands and enjoy a good meal between practice sessions.",
                    "Rehearse circus performances", "Run through your act from start to finish and polish the timing.",
                    "Create spooky decorations", "Craft handmade Halloween decorations from paper, cloth and paint.",
                    "Watch classic circus performances", "Study classic acts for inspiration and note ideas you can borrow."
            ),
            routine(
                    "Beetlejuice",
                    "Chaos Is My Lifestyle",
                    "A quirky and creative day packed with costume styling, magic tricks, and party planning.",
                    "img/halloween/06-beetlejuice.jpg",
                    "Wake up with messy hair", "Embrace the chaos, stretch and get moving at your own pace.",
                    "Put on my striped suit", "Get dressed in your boldest outfit and enjoy the bold stripes.",
                    "Practice supernatural magic tricks", "Practise sleight of hand until the simple tricks feel smooth.",
                    "Have a strange lunch", "Mix unusual flavours into a meal that is odd but still tasty.",
                    "Decorate my haunted house", "Rearrange a corner of your home with quirky, spooky decorations.",
                    "Practice dramatic entrances", "Rehearse a big, funny entrance using posture, timing and voice.",
                    "Host a ghostly party", "Bring friends together for music, costumes and playful games."
            ),
            routine(
                    "Freddy Krueger",
                    "Master of Dreams",
                    "A dream-inspired day of psychology, surreal storytelling, and film.",
                    "img/halloween/07-freddy-krueger.jpg",
                    "Wake up and write down my dreams", "Keep a journal by your bed and capture every detail you remember.",
                    "Read about dream psychology", "Read a short chapter on sleep and what dreams may mean.",
                    "Have breakfast", "Enjoy a simple, filling meal before your creative work begins.",
                    "Design surreal dream worlds", "Sketch or describe imaginary places that follow their own strange rules.",
                    "Watch psychological thrillers", "Watch a mind-bending film and think about how it shapes the plot.",
                    "Practice creative storytelling", "Write a short scene and experiment with suspense and surprise.",
                    "Prepare for dream exploration", "Dim the lights, relax your body and set up a calm bedtime."
            ),
            routine(
                    "Art the Clown",
                    "The Silent Performer's Day",
                    "A silent performance day of makeup, expressions, and mime.",
                    "img/halloween/08-art-the-clown.jpg",
                    "Wake up without saying a word", "Spend the first part of your morning communicating without speech.",
                    "Apply black and white makeup", "Apply bold monochrome face paint carefully and evenly.",
                    "Practice facial expressions", "Work in front of a mirror to make each emotion clear without words.",
                    "Have lunch", "Take a calm break and enjoy your meal away from rehearsal.",
                    "Rehearse silent comedy", "Practise physical gags and timing until they read clearly.",
                    "Prepare my clown costume", "Check each costume piece, make small repairs and set out your props.",
                    "Perform a spooky mime show", "Bring it all together in a short, wordless Halloween performance."
            ),
            routine(
                    "Count Dracula",
                    "The Vampire Lifestyle",
                    "An elegant nighttime routine of reading, stargazing, and quiet walks.",
                    "img/halloween/09-dracula.jpg",
                    "Wake up in my castle", "Begin the day slowly, with stillness and a moment of quiet reflection.",
                    "Get dressed in formal clothing", "Choose a polished outfit that makes you feel composed and confident.",
                    "Read classic literature", "Spend time with a Gothic novel or another timeless book.",
                    "Enjoy an elegant dinner", "Set the table nicely and enjoy an unhurried, refined meal.",
                    "Walk through the castle gardens", "Take a slow evening walk among trees, paths and quiet gardens.",
                    "Explore the night sky", "Look for constellations and planets and learn one new star pattern.",
                    "Return to my room before sunrise", "Wind down before dawn so that you can get some restful sleep."
            ),
            routine(
                    "Jason Voorhees",
                    "A Day at Crystal Lake",
                    "A nature-focused routine of camping, woodland walks, and lake views.",
                    "img/halloween/10-jason-voorhees.jpg",
                    "Wake up in the forest cabin", "Open the windows, breathe in the fresh air and greet the quiet morning.",
                    "Take a peaceful forest walk", "Follow a shaded path and take in the sounds of the woods.",
                    "Check the campsite", "Tidy the site, check the tents and make sure everything is safe.",
                    "Maintain camping equipment", "Clean, repair and pack your gear so it is ready for the next trip.",
                    "Have lunch by the lake", "Bring a simple picnic to the shore and enjoy the view of the water.",
                    "Explore forest trails", "Hike a new route and practise reading the map along the way.",
                    "Watch the lake under the moonlight", "End the day by the shore, watching the moonlight on calm water."
            )
    );

    private HalloweenRoutineCatalog() {
    }

    public static Optional<String> findAuthorName(String title) {
        return ROUTINES.stream()
                .filter(routine -> routine.title().equals(title))
                .map(RoutineSeed::authorName)
                .findFirst();
    }

    private static RoutineSeed routine(
            String authorName,
            String title,
            String description,
            String imageUrl,
            String... habitTitlesAndDescriptions
    ) {
        List<HabitSeed> habits = new ArrayList<>();

        for (int i = 0; i < HABIT_DURATIONS.length; i++) {
            habits.add(new HabitSeed(
                    habitTitlesAndDescriptions[i * 2],
                    habitTitlesAndDescriptions[i * 2 + 1],
                    HABIT_DURATIONS[i]
            ));
        }

        return new RoutineSeed(authorName, title, description, imageUrl, List.copyOf(habits));
    }
}
