package com.kitdesk.repository;

import com.kitdesk.domain.Category;
import com.kitdesk.domain.CategoryCode;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CategoryRepository extends JpaRepository<Category, Long> {

    List<Category> findAllByOrderByIdAsc();

    Optional<Category> findByCode(CategoryCode code);
}
