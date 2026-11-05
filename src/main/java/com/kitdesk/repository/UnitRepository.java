package com.kitdesk.repository;

import com.kitdesk.domain.EquipmentModel;
import com.kitdesk.domain.Unit;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UnitRepository extends JpaRepository<Unit, Long> {

    Optional<Unit> findByPublicId(String publicId);

    List<Unit> findByModelOrderByAssetTagAsc(EquipmentModel model);

    /**
     * Loads the unit and locks its row ({@code SELECT ... FOR UPDATE}) until the current
     * transaction ends. Two bookings of the same unit therefore run one after the other: the
     * second only checks availability after the first has committed.
     * Must be called inside a transaction, after {@link UserRepository#lockStudent}.
     *
     * <p>Deliberately no "join fetch" here: with joins, PostgreSQL's FOR UPDATE would also lock
     * the model and category rows, so every booking in the same category would queue behind each
     * other. The model and category load lazily afterwards, unlocked.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from Unit u where u.publicId = :publicId")
    Optional<Unit> lockUnit(@Param("publicId") String publicId);
}
