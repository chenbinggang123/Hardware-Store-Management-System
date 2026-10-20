package com.example.demo.billing;

import java.util.Set;

/** Removes decorative OCR marks while retaining punctuation that can change product or amount meaning. */
public final class RecognizedTextSanitizer {
    private static final Set<Integer> DECORATIVE = Set.of(
            0x221A, // square root, commonly misread from a handwritten tick
            0x2713, 0x2714, 0x2611, 0x2612, 0x2610, // ticks and boxes
            0x2705, 0x274E, 0x274C, // emoji-style confirmation marks
            0x2605, 0x2606, 0x2726, 0x2727, // decorative stars
            0x25C6, 0x25C7, 0x25A0, 0x25A1, // diamonds and squares
            0x25CF, 0x25CB, 0x2022, 0x25E6, 0x25AA, 0x25AB // decorative bullets
    );

    private RecognizedTextSanitizer() {}

    public static String clean(String value) {
        if (value == null || value.isEmpty()) return value;
        StringBuilder cleaned = new StringBuilder(value.length());
        value.codePoints().forEach(codePoint -> {
            int type = Character.getType(codePoint);
            if (!DECORATIVE.contains(codePoint) && type != Character.FORMAT && codePoint != 0xFE0F) {
                cleaned.appendCodePoint(codePoint);
            }
        });
        return cleaned.toString()
                .replaceAll("[\\t\\x0B\\f\\r ]+", " ")
                .replaceAll(" *\\n *", "\n")
                .trim();
    }
}
