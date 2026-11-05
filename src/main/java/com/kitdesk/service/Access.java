package com.kitdesk.service;

import com.kitdesk.domain.DomainRuleViolation;
import com.kitdesk.domain.Role;

/** Role checks, shared by the services. A wrong role is 403 FORBIDDEN. */
public final class Access {

    private Access() {
    }

    public static void require(Caller caller, Role role) {
        if (caller == null || caller.role() != role) {
            String who = role == Role.STAFF ? "staff" : "students";
            throw new DomainRuleViolation(ErrorCatalog.FORBIDDEN, "Only " + who + " may do this.");
        }
    }
}
