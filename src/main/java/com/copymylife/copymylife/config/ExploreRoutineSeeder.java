package com.copymylife.copymylife.config;

import com.copymylife.copymylife.model.Routine;
import com.copymylife.copymylife.model.User;
import com.copymylife.copymylife.repository.RoutineRepository;
import com.copymylife.copymylife.repository.UserRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class ExploreRoutineSeeder implements CommandLineRunner {

    private final RoutineRepository routineRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public ExploreRoutineSeeder(RoutineRepository routineRepository, UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.routineRepository = routineRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(String... args) {

        User systemUser = userRepository.findByEmail("explore@copymylife.local").orElseGet(() -> {

            User user = new User();

            user.setName("CopyMyLife Explore");
            user.setEmail("explore@copymylife.local");
            user.setUsername("explore");
            user.setPassword(passwordEncoder.encode(UUID.randomUUID().toString()));

            return userRepository.save(user);
        });

        seedRoutine(systemUser, "Elon Musk's Productivity System", "A structured day focused on deep work, learning, and long-term thinking.", 840, "Productivity", "https://i.ibb.co/C52sMxMH/photo-5282767902903836637-y.jpg");

        seedRoutine(systemUser, "Tim Cook Morning Routine", "An early-start routine inspired by planning, focused work, movement, and a quiet beginning.", 180, "Morning", "https://i.ibb.co.com/1fS1RMJs/photo-5325754199049970232-x-1.jpg");

        seedRoutine(systemUser, "Cristiano Ronaldo Training Routine", "Training, recovery, nutrition and a winning mindset.", 480, "Fitness", "https://i.ibb.co/bjDKgTKD/photo-5274141405255250419-x.jpg");

        seedRoutine(systemUser, "Ali Abdaal Routine", "Focused work, planning, learning, and sustainable productivity.", 420, "Productivity", "https://i.ibb.co.com/DHxJ942m/photo-5325754199049970242-x.jpg");

        seedRoutine(systemUser, "Arianna Huffington Routine", "Rest, recovery, mindfulness, and intentional daily habits.", 180, "Wellness", "https://i.ibb.co.com/fVLcwGdG/photo-5325754199049970237-y.jpg");

        seedRoutine(systemUser, "Marques Brownlee Routine", "Creative production, focused work, filming, and consistent output.", 480, "Creators", "https://i.ibb.co.com/xqL822fT/photo-5325754199049970253-y.jpg");

        seedRoutine(systemUser, "David Goggins Routine", "Mental toughness, early mornings, and relentless action.", 360, "Fitness", "https://i.ibb.co/5gfVFSq0/photo-5274141405255250420-x.jpg");

        seedRoutine(systemUser, "Taylor Swift Routine", "Creative work, songwriting, rehearsals, fitness, reading, and structured preparation.", 480, "Creators", "https://i.ibb.co/Z6zTmhkm/taylor-swift.jpg");

        seedRoutine(systemUser, "Michael Jordan Routine", "Discipline, practice, and the pursuit of greatness.", 600, "Fitness", "https://i.ibb.co/kV9m3b5t/photo-5262830132139860025-y.jpg");

        seedRoutine(systemUser, "Bill Gates Routine", "Reading, focused work, learning, planning, exercise, and reflection.", 600, "Study", "https://i.ibb.co/Yrpgvyk/photo-5274141405255250416-x.jpg");

        seedRoutine(systemUser, "Andrew Huberman Routine", "Movement, focus, preparation, and intentional work.", 150, "Wellness", "https://i.ibb.co.com/BVVR2xW2/photo-5325754199049970206-x.jpg");

        seedRoutine(systemUser, "Mel Robbins Routine", "A practical morning routine for building momentum and starting important work early.", 120, "Morning", "https://i.ibb.co.com/TXMkK4N/photo-5325754199049970234-x.jpg");

        seedRoutine(systemUser, "Richard Branson Routine", "Movement, planning, and an energetic start to the day.", 120, "Morning", "https://i.ibb.co.com/20tGmyT6/photo-5325754199049970235-x.jpg");

        seedRoutine(systemUser, "James Clear Routine", "Simple repeatable actions designed for consistency.", 90, "Productivity", "https://i.ibb.co.com/2YcdRBjT/photo-5325754199049970236-x.jpg");

        seedRoutine(systemUser, "Serena Williams Routine", "Strength training, preparation, recovery, and mental focus.", 240, "Fitness", "https://i.ibb.co.com/XxL5v9b9/photo-5325754199049970238-x.jpg");

        seedRoutine(systemUser, "Chris Hemsworth Routine", "Strength, mobility, and recovery for an active lifestyle.", 180, "Fitness", "https://i.ibb.co.com/1YVqnPdn/photo-5325754199049970239-y.jpg");

        seedRoutine(systemUser, "Eliud Kipchoge Routine", "Running, recovery, and consistent preparation.", 300, "Fitness", "https://i.ibb.co.com/SXJwLhkz/photo-5325754199049970240-x.jpg");

        seedRoutine(systemUser, "Rich Roll Routine", "Movement, nutrition, recovery, and sustainable wellness habits.", 240, "Wellness", "https://i.ibb.co.com/NgqGTwQF/photo-5325754199049970241-y.jpg");

        seedRoutine(systemUser, "Cal Newport Routine", "Distraction-free study blocks and deliberate concentration on difficult work.", 300, "Study", "https://i.ibb.co.com/ff70fZD/photo-5325754199049970243-x.jpg");

        seedRoutine(systemUser, "Barbara Oakley Routine", "Focused practice, breaks, recall, and techniques for mastering difficult concepts.", 240, "Study", "https://i.ibb.co.com/GvKMjJzC/photo-5325754199049970244-x.jpg");

        seedRoutine(systemUser, "Thomas Frank Routine", "Planning, focused study, assignments, and review.", 300, "Study", "https://i.ibb.co.com/S4Gt2nmc/photo-5325754199049970245-x.jpg");

        seedRoutine(systemUser, "Scott Young Routine", "Active practice and deliberate skill-building.", 300, "Study", "https://i.ibb.co.com/wrWjgxGV/photo-5325754199049970227-x.jpg");

        seedRoutine(systemUser, "Sal Khan Routine", "Learn concepts step by step, practice, then test understanding.", 240, "Study", "https://i.ibb.co.com/mrspjkj2/photo-5325754199049970246-x.jpg");

        seedRoutine(systemUser, "Andy Puddicombe Routine", "Meditation and mindful pauses designed to bring more attention into everyday life.", 90, "Wellness", "https://i.ibb.co.com/bjzPTjs4/photo-5325754199049970247-x.jpg");

        seedRoutine(systemUser, "Adriene Mishler Routine", "Gentle movement, breathing, mindfulness, and regular self-care.", 120, "Wellness", "https://i.ibb.co.com/rfv68N4y/photo-5325754199049970248-y.jpg");

        seedRoutine(systemUser, "Jay Shetty Routine", "Reflection, gratitude, meditation, and intentional time.", 120, "Wellness", "https://i.ibb.co.com/6RvnKNQc/photo-5325754199049970249-y.jpg");

        seedRoutine(systemUser, "Wim Hof Routine", "Breathing, controlled challenge, and consistent practice.", 90, "Wellness", "https://i.ibb.co.com/hFHZHCfW/photo-5325754199049970250-x.jpg");

        seedRoutine(systemUser, "David Allen Routine", "Capture, clarify, organize, review, and take action.", 300, "Productivity", "https://i.ibb.co.com/1tnJWxD1/photo-5325754199049970251-x.jpg");

        seedRoutine(systemUser, "Tiago Forte Routine", "Capture knowledge and turn information into useful output.", 360, "Productivity", "https://i.ibb.co.com/fY5gtdVY/photo-5325754199049970233-x.jpg");

        seedRoutine(systemUser, "Casey Neistat Routine", "Ideas, filming, movement, storytelling, and visual creation.", 480, "Creators", "https://i.ibb.co.com/sJmhY89c/photo-5325754199049970252-x.jpg");

        seedRoutine(systemUser, "Emma Chamberlain Routine", "Content creation, personal time, business work, and inspiration.", 360, "Creators", "https://i.ibb.co.com/8J5n14j/photo-5325754199049970254-y.jpg");

        seedRoutine(systemUser, "Matt D'Avella Routine", "Focused creative work and a slower approach to consistent output.", 420, "Creators", "https://i.ibb.co.com/21CW32rk/photo-5325754199049970255-y.jpg");

        seedRoutine(systemUser, "Peter McKinnon Routine", "Photography, filming, editing, and experimentation.", 480, "Creators", "https://i.ibb.co.com/bjdf9NPX/photo-5325754199049970256-y.jpg");
    }

    private void seedRoutine(User user, String title, String description, int durationMinutes, String category, String imageUrl) {

        if (routineRepository.existsByTitleAndBuiltInTrue(title)) return;

        Routine routine = new Routine();

        routine.setTitle(title);
        routine.setDescription(description);
        routine.setDurationMinutes(durationMinutes);
        routine.setCategory(category);
        routine.setImageUrl(imageUrl);
        routine.setPublic(true);
        routine.setBuiltIn(true);
        routine.setCompleted(false);
        routine.setUser(user);

        routineRepository.save(routine);
    }
}