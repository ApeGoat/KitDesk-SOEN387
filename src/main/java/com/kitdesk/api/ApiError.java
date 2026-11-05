package com.kitdesk.api;

/**
 * The single error shape every failed request returns: a short machine-readable code and a
 * human-readable message. Never a stack trace.
 */
public record ApiError(String code, String message) {
}
