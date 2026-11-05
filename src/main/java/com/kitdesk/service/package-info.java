/**
 * Service (domain logic) layer: every business rule lives here.
 *
 * <p>Booking limits, date rules, subscription and overdue checks, per-user authorization and
 * transaction boundaries (including row locks) are all enforced in this package, so any new
 * path into the system (a new endpoint, a scheduled job) gets them automatically.
 */
package com.kitdesk.service;
