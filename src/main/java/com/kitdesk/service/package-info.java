/**
 * Service layer (§5.6): the use cases and the rules that span many records.
 *
 * <p>Each use case opens its own transaction and decides the lock order (student, then unit).
 * Category limits, subscription, overdue lock-out, per-user access (own bookings only,
 * staff-only pickup and return) and idempotency keys are enforced here, so any new way into the
 * system (another endpoint, a scheduled job) gets them too. Rules about one reservation's own
 * data live on the domain entities, and the locks and version checks themselves live in the
 * repository layer.
 */
package com.kitdesk.service;
