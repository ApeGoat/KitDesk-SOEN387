package com.kitdesk.domain;

import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Makes the opaque ids the API shows, e.g. {@code res_3f9a1c0b7d2e4f61}: a prefix plus 16 random
 * hex characters. Random (not sequential) so clients can't guess other ids or count records.
 * Same shape as the database default used for seeded rows.
 */
public final class PublicIds {

    private static final SecureRandom RANDOM = new SecureRandom();

    private PublicIds() {
    }

    public static String newId(String prefix) {
        byte[] bytes = new byte[8];
        RANDOM.nextBytes(bytes);
        return prefix + "_" + HexFormat.of().formatHex(bytes);
    }
}
