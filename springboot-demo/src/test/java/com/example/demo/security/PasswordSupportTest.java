package com.example.demo.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PasswordSupportTest {

    @Test
    void bcryptHashMatchesCorrectPasswordOnly() {
        String encoded = PasswordSupport.encode("123456");

        assertTrue(PasswordSupport.isEncoded(encoded));
        assertTrue(PasswordSupport.matches("123456", encoded));
        assertFalse(PasswordSupport.matches("wrong", encoded));
        assertFalse(encoded.contains("123456"));
    }

    @Test
    void legacyPlaintextCanBeVerifiedForMigration() {
        assertTrue(PasswordSupport.matches("123456", "123456"));
        assertFalse(PasswordSupport.matches("wrong", "123456"));
    }
}
