package com.kitdesk.service;

import com.kitdesk.domain.Role;

/**
 * Who is calling, as read from a verified login token: the user's id and role. The id always
 * comes from the token, never from the request body. Anything that can change after login
 * (subscription, overdue gear) is read from the database at request time, not from here.
 */
public record Caller(Long userId, Role role) {

    public boolean isStudent() {
        return role == Role.STUDENT;
    }

    public boolean isStaff() {
        return role == Role.STAFF;
    }
}
