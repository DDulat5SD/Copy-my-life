package com.copymylife.copymylife.repository;

import com.copymylife.copymylife.model.Category;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface CategoryRepository extends JpaRepository<Category, Long> {

    List<Category> findAllByOrderByPositionAscIdAsc();

    Optional<Category> findByNameKey(String nameKey);

    @Query("SELECT COALESCE(MAX(c.position), -1) FROM Category c")
    int findMaxPosition();
}
