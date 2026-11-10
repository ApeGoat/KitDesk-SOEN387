package com.kitdesk.support;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.model.Request;
import com.atlassian.oai.validator.model.SimpleRequest;
import com.atlassian.oai.validator.model.SimpleResponse;
import com.atlassian.oai.validator.report.ValidationReport;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The contract conformance check (handout §4): every request an API test sends, and every
 * response the running service gives back, is checked against {@code openapi.yaml}. A mismatch
 * fails the test, so every API test is also a conformance test.
 *
 * <ul>
 *   <li><b>Responses</b> are always checked: the status must be one the operation documents, and
 *       the body must match its schema.</li>
 *   <li><b>Requests</b> are checked when the service accepted them (2xx). Many tests send a bad
 *       request on purpose to see it refused; the check is that the service never accepts a
 *       request the contract forbids.</li>
 *   <li>Paths outside the contract (Swagger UI, the YAML file itself) are skipped, and so is a
 *       path or method the contract doesn't have when the service rightly answered 404 or 405.</li>
 * </ul>
 */
final class Conformance {

    static final Path CONTRACT = Path.of("src/main/resources/static/openapi.yaml");

    private static final Set<String> OUTSIDE_THE_CONTRACT = Set.of("/openapi.yaml", "/swagger-ui", "/v3/api-docs");

    private static final OpenApiInteractionValidator VALIDATOR = OpenApiInteractionValidator
            .createForSpecificationUrl(CONTRACT.toAbsolutePath().toUri().toString())
            .build();

    private Conformance() {
    }

    static void check(String method, String pathAndQuery, HttpRequest sent, String body, HttpResponse<String> got) {
        URI uri = URI.create(pathAndQuery);
        String path = uri.getRawPath();
        if (OUTSIDE_THE_CONTRACT.stream().anyMatch(path::startsWith)) {
            return;
        }
        int status = got.statusCode();
        SimpleRequest.Builder request = new SimpleRequest.Builder(method, path);
        sent.headers().map().forEach(request::withHeader);
        sent.headers().firstValue("Content-Type").ifPresent(request::withContentType);
        if (body != null) {
            request.withBody(body);
        }
        if (uri.getRawQuery() != null) {
            for (String pair : uri.getRawQuery().split("&")) {
                String[] kv = pair.split("=", 2);
                request.withQueryParam(kv[0], kv.length > 1 ? kv[1] : "");
            }
        }
        SimpleResponse.Builder response = SimpleResponse.Builder.status(status);
        got.headers().map().forEach(response::withHeader);
        // The JDK client gives header names in lower case; the validator needs the content type
        // set explicitly, or it can't tell which schema applies and skips the body.
        got.headers().firstValue("Content-Type").ifPresent(response::withContentType);
        if (got.body() != null && !got.body().isEmpty()) {
            response.withBody(got.body());
        }

        Request req = request.build();
        ValidationReport report = VALIDATOR.validate(req, response.build());
        List<ValidationReport.Message> problems = report.getMessages().stream()
                .filter(m -> m.getLevel() == ValidationReport.Level.ERROR)
                .filter(m -> !ignored(m, status))
                .toList();
        if (!problems.isEmpty()) {
            throw new AssertionError("Contract mismatch for " + method + " " + pathAndQuery + " -> " + status + ":\n"
                    + problems.stream().map(m -> "  [" + m.getKey() + "] " + m.getMessage())
                            .collect(Collectors.joining("\n"))
                    + "\nResponse body: " + got.body());
        }
    }

    private static boolean ignored(ValidationReport.Message m, int status) {
        String key = m.getKey();
        boolean aboutRequest = key.startsWith("validation.request");
        if (aboutRequest && (status < 200 || status >= 300)) {
            return true;   // a refused bad request is the service doing its job
        }
        return switch (key) {
            // The seeded accounts use the reserved .test domain (RFC 2606). Its addresses are
            // well-formed, but the validator also checks the domain against the public list of
            // top-level domains, where .test is deliberately absent.
            case "validation.request.body.schema.format.email",
                 "validation.response.body.schema.format.email" -> true;
            // The contract's one stated exception: a client that won't accept JSON gets a bare 406.
            case "validation.response.status.unknown" -> status == 406;
            case "validation.request.path.missing" -> status == 404 || status == 401;
            case "validation.request.operation.notAllowed" -> status == 405 || status == 401;
            default -> false;
        };
    }
}
