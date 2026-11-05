package com.kitdesk.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.Collection;

/** One physical item, e.g. the camera labelled CAM-R5-03. Students book units, not models. */
@Entity
@Table(name = "units")
public class Unit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false, unique = true, updatable = false)
    private String publicId;

    @Column(name = "asset_tag", nullable = false, unique = true)
    private String assetTag;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "model_id", nullable = false)
    private EquipmentModel model;

    @Column(nullable = false)
    private boolean active = true;

    private String notes;

    protected Unit() {
        // for JPA
    }

    public Unit(String assetTag, EquipmentModel model) {
        this.publicId = PublicIds.newId("unt");
        this.assetTag = assetTag;
        this.model = model;
    }

    /**
     * Single-aggregate rule: the unit must be free on every requested day (both ends inclusive).
     * {@code existing} is this unit's reservations, loaded by the data source layer; only active
     * ones (RESERVED, PICKED_UP) occupy the unit.
     *
     * A unit taken out of service ({@code active = false}) is never available.
     *
     * @throws DomainRuleViolation UNIT_UNAVAILABLE naming the first clash
     */
    public void assertAvailable(LocalDate start, LocalDate end, Collection<Reservation> existing) {
        if (!active) {
            throw new DomainRuleViolation(DomainRuleViolation.UNIT_UNAVAILABLE,
                    assetTag + " is out of service.");
        }
        for (Reservation r : existing) {
            if (r.isActive() && r.overlaps(start, end)) {
                throw new DomainRuleViolation(DomainRuleViolation.UNIT_UNAVAILABLE,
                        assetTag + " is already booked from " + r.getStartDate() + " to " + r.getEndDate() + ".");
            }
        }
    }

    public Long getId() { return id; }
    public String getPublicId() { return publicId; }
    public String getAssetTag() { return assetTag; }
    public EquipmentModel getModel() { return model; }
    public boolean isActive() { return active; }
    public String getNotes() { return notes; }
}
