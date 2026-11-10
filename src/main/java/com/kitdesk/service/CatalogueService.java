package com.kitdesk.service;

import com.kitdesk.domain.CategoryCode;
import com.kitdesk.domain.DomainRuleViolation;
import com.kitdesk.domain.EquipmentModel;
import com.kitdesk.repository.CategoryRepository;
import com.kitdesk.repository.EquipmentModelRepository;
import com.kitdesk.repository.ReservationRepository;
import com.kitdesk.repository.UnitRepository;
import com.kitdesk.service.Views.CategoryView;
import com.kitdesk.service.Views.ModelView;
import com.kitdesk.service.Views.UnitView;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only catalogue: categories, models and units with their occupied dates. Any logged-in user. */
@Service
@Transactional(readOnly = true)
public class CatalogueService {

    private final CategoryRepository categories;
    private final EquipmentModelRepository models;
    private final UnitRepository units;
    private final ReservationRepository reservations;
    private final Clock clock;

    public CatalogueService(CategoryRepository categories, EquipmentModelRepository models,
                            UnitRepository units, ReservationRepository reservations, Clock clock) {
        this.clock = clock;
        this.categories = categories;
        this.models = models;
        this.units = units;
        this.reservations = reservations;
    }

    public List<CategoryView> listCategories() {
        return categories.findAllByOrderByIdAsc().stream().map(CategoryView::of).toList();
    }

    /** All models, or only one category's if {@code category} is given. */
    public List<ModelView> listModels(CategoryCode category) {
        List<EquipmentModel> found = category == null
                ? models.findAllWithCategory()
                : models.findByCategoryCode(category);
        return found.stream().map(m -> ModelView.of(m, models.countUnits(m))).toList();
    }

    /**
     * A model's units, each with the dates it is occupied (RESERVED or PICKED_UP bookings only),
     * including out-of-service units ({@code inService = false}) so staff can still see them.
     *
     * @throws DomainRuleViolation NOT_FOUND if no model has this id
     */
    public List<UnitView> listUnits(String modelId) {
        EquipmentModel model = models.findByPublicId(modelId).orElseThrow(
                () -> new DomainRuleViolation(ErrorCatalog.NOT_FOUND, "No model has the id " + modelId + "."));
        LocalDate today = LocalDate.now(clock);
        return units.findByModelOrderByAssetTagAsc(model).stream()
                .map(u -> UnitView.of(u, reservations.findActiveByUnit(u), today))
                .toList();
    }
}
