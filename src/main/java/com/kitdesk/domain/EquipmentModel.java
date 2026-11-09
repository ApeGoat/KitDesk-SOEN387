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

/**
 * A catalogue model such as "Canon EOS R5". Named EquipmentModel because "Model" clashes with a
 * Spring class. The API's "model" is this entity.
 */
@Entity
@Table(name = "models")
public class EquipmentModel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false, unique = true, updatable = false)
    private String publicId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;

    @Column(nullable = false)
    private String brand;

    @Column(nullable = false)
    private String name;

    private String description;

    protected EquipmentModel() {
        // for JPA
    }

    public EquipmentModel(Category category, String brand, String name, String description) {
        this.publicId = PublicIds.newId("mdl");
        this.category = category;
        this.brand = brand;
        this.name = name;
        this.description = description;
    }

    /** "Canon EOS R5": what the contract calls the model's name. */
    public String getDisplayName() {
        return brand + " " + name;
    }

    public Long getId() { return id; }
    public String getPublicId() { return publicId; }
    public Category getCategory() { return category; }
    public String getBrand() { return brand; }
    public String getName() { return name; }
    public String getDescription() { return description; }
}
