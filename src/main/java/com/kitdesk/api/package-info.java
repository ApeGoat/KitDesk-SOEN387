/**
 * Interface layer: REST controllers and request/response objects.
 *
 * <p>Controllers only translate between HTTP and service calls: read the request, call one
 * service method, turn the result (or exception) into a response. No business rules live here,
 * so the same services could sit behind a different interface style.
 */
package com.kitdesk.api;
