package com.kitdesk.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;

/**
 * What "the same request" means for an Idempotency-Key (contract, "Idempotency"): same method,
 * path, If-Match-Version and body, with the body compared as parsed JSON, so field order and
 * whitespace don't matter. Returned as a SHA-256 hex string to store next to the key.
 */
public final class RequestFingerprint {

    private static final ObjectMapper PLAIN = new ObjectMapper();

    private RequestFingerprint() {
    }

    public static String of(String method, String path, String ifMatchVersion, String body) {
        String canonical = method + "\n" + path + "\n" + (ifMatchVersion == null ? "" : ifMatchVersion.trim())
                + "\n" + canonicalBody(body);
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** JSON with object keys sorted; text that isn't JSON is compared as it is. */
    private static String canonicalBody(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        try {
            return PLAIN.writeValueAsString(sorted(PLAIN.readTree(body)));
        } catch (Exception notJson) {
            return "raw:" + body;
        }
    }

    private static JsonNode sorted(JsonNode node) {
        if (node.isObject()) {
            Map<String, JsonNode> fields = new TreeMap<>();
            for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                fields.put(e.getKey(), sorted(e.getValue()));
            }
            ObjectNode out = JsonNodeFactory.instance.objectNode();
            fields.forEach(out::set);
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = JsonNodeFactory.instance.arrayNode();
            node.forEach(child -> out.add(sorted(child)));
            return out;
        }
        return node;
    }
}
