package com.copymylife.copymylife.config;

import com.copymylife.copymylife.model.Habit;
import com.copymylife.copymylife.model.Routine;
import com.copymylife.copymylife.model.RoutineHabit;
import com.copymylife.copymylife.model.User;
import com.copymylife.copymylife.repository.HabitRepository;
import com.copymylife.copymylife.repository.RoutineHabitRepository;
import com.copymylife.copymylife.repository.RoutineRepository;
import com.copymylife.copymylife.repository.UserRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

@Component
public class HalloweenRoutineSeeder implements CommandLineRunner {

    private final RoutineRepository routineRepository;
    private final HabitRepository habitRepository;
    private final RoutineHabitRepository routineHabitRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final TransactionTemplate transactionTemplate;

    public HalloweenRoutineSeeder(RoutineRepository routineRepository, HabitRepository habitRepository, RoutineHabitRepository routineHabitRepository, UserRepository userRepository, PasswordEncoder passwordEncoder, PlatformTransactionManager transactionManager) {
        this.routineRepository = routineRepository;
        this.habitRepository = habitRepository;
        this.routineHabitRepository = routineHabitRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
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

        for (HalloweenRoutineCatalog.RoutineSeed seed : HalloweenRoutineCatalog.ROUTINES) {
            // One transaction per routine, so a routine is never left without its habits.
            transactionTemplate.executeWithoutResult(status -> seedRoutine(systemUser, seed));
        }
    }

    private void seedRoutine(User user, HalloweenRoutineCatalog.RoutineSeed seed) {

        // Existing rows, including soft-deleted ones, are never recreated or overwritten.
        if (routineRepository.existsByTitleAndBuiltInTrue(seed.title())) return;

        Routine routine = new Routine();

        routine.setTitle(seed.title());
        routine.setDescription(seed.description());
        routine.setDurationMinutes(seed.durationMinutes());
        routine.setCategory(HalloweenRoutineCatalog.CATEGORY);
        routine.setImageUrl(seed.imageUrl());
        routine.setPublic(true);
        routine.setBuiltIn(true);
        routine.setCompleted(false);
        routine.setUser(user);

        Routine savedRoutine = routineRepository.save(routine);

        int position = 1;

        for (HalloweenRoutineCatalog.HabitSeed habitSeed : seed.habits()) {

            Habit habit = new Habit();

            habit.setTitle(habitSeed.title());
            habit.setDescription(habitSeed.description());
            habit.setDurationMinutes(habitSeed.durationMinutes());
            habit.setRoutine(savedRoutine);
            habit.setUser(user);

            RoutineHabit link = new RoutineHabit();

            link.setRoutine(savedRoutine);
            link.setHabit(habitRepository.save(habit));
            link.setPosition(position++);

            routineHabitRepository.save(link);
        }
    }
}
