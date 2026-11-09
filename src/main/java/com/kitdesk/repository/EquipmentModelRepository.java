package com.kitdesk.repository;

import com.kitdesk.domain.CategoryCode;
import com.kitdesk.domain.EquipmentModel;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EquipmentModelRepository extends JpaRepository<EquipmentModel, Long> {

    Optional<EquipmentModel> findByPublicId(String publicId);

    @Query("select m from EquipmentModel m join fetch m.category order by m.id")
    List<EquipmentModel> findAllWithCategory();

    @Query("select m from EquipmentModel m join fetch m.category c where c.code = :code order by m.id")
    List<EquipmentModel> findByCategoryCode(@Param("code") CategoryCode code);

    /** Number of physical units per model, for the contract's {@code unitCount}. */
    @Query("select count(u) from Unit u where u.model = :model")
    long countUnits(@Param("model") EquipmentModel model);
}
