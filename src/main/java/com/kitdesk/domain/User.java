package com.kitdesk.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A student or staff account. Students book gear; staff confirm pickups and returns. */
@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    @Column(name = "subscription_active", nullable = false)
    private boolean subscriptionActive;

    protected User() {
        // for JPA
    }

    public User(String email, String passwordHash, String fullName, Role role, boolean subscriptionActive) {
        // Stored lower-case and trimmed, matching the case-insensitive unique index (V3).
        this.email = email.trim().toLowerCase(java.util.Locale.ROOT);
        this.passwordHash = passwordHash;
        this.fullName = fullName;
        this.role = role;
        this.subscriptionActive = subscriptionActive;
    }

    public boolean isStudent() {
        return role == Role.STUDENT;
    }

    public boolean isStaff() {
        return role == Role.STAFF;
    }

    public Long getId() { return id; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public String getFullName() { return fullName; }
    public Role getRole() { return role; }
    public boolean isSubscriptionActive() { return subscriptionActive; }
}
