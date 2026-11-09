package com.kitdesk.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** An equipment category, with how many active rentals one student may hold in it. */
@Entity
@Table(name = "categories")
public class Category {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false, unique = true, updatable = false)
    private String publicId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, unique = true)
    private CategoryCode code;

    @Column(nullable = false)
    private String name;

    @Column(name = "rental_limit", nullable = false)
    private int rentalLimit;

    protected Category() {
        // for JPA
    }

    public Category(CategoryCode code, String name, int rentalLimit) {
        this.publicId = PublicIds.newId("cat");
        this.code = code;
        this.name = name;
        this.rentalLimit = rentalLimit;
    }

    public Long getId() { return id; }
    public String getPublicId() { return publicId; }
    public CategoryCode getCode() { return code; }
    public String getName() { return name; }
    public int getRentalLimit() { return rentalLimit; }
}
