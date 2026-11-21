package com.copymylife.copymylife.config;

import com.copymylife.copymylife.model.Category;
import com.copymylife.copymylife.repository.CategoryRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

// US-25: the categories the site offered before administrators managed them, added once to an
// empty list. After that only administrators change it.
@Component
public class CategorySeeder implements CommandLineRunner {

    static final List<String> DEFAULTS = List.of("Morning", "Fitness", "Study", "Wellness", "Productivity", "Creators");

    private final CategoryRepository categoryRepository;

    public CategorySeeder(CategoryRepository categoryRepository) {
        this.categoryRepository = categoryRepository;
    }

    @Override
    public void run(String... args) {
        if (categoryRepository.count() > 0) {
            return;
        }

        for (int position = 0; position < DEFAULTS.size(); position++) {
            Category category = new Category();
            category.setName(DEFAULTS.get(position));
            category.setNameKey(DEFAULTS.get(position).toLowerCase(Locale.ROOT));
            category.setPosition(position);
            categoryRepository.save(category);
        }
    }
}
