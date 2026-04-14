package com.urlshortener.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class Base62EncoderTest {

    private Base62Encoder encoder;

    @BeforeEach
    void setUp() {
        encoder = new Base62Encoder();
    }

    @Test
    @DisplayName("Encode 0 returns '0'")
    void encodeZero() {
        assertEquals("0", encoder.encode(0));
    }

    @Test
    @DisplayName("Encode small numbers produces short codes")
    void encodeSmallNumbers() {
        assertEquals("1", encoder.encode(1));
        assertEquals("a", encoder.encode(10));
        assertEquals("A", encoder.encode(36));
    }

    @Test
    @DisplayName("Encode 1000 returns expected Base62 string")
    void encodeLargeNumber() {
        String encoded = encoder.encode(1000);
        assertNotNull(encoded);
        assertFalse(encoded.isEmpty());
        assertEquals(1000, encoder.decode(encoded));
    }

    @Test
    @DisplayName("Encode and decode are inverse operations")
    void encodeDecodeRoundTrip() {
        long[] testValues = {1, 10, 62, 100, 999, 10000, 123456789L, 1000000000L};
        for (long value : testValues) {
            String encoded = encoder.encode(value);
            long decoded = encoder.decode(encoded);
            assertEquals(value, decoded, "Round-trip failed for value: " + value);
        }
    }

    @Test
    @DisplayName("Different inputs produce different outputs")
    void uniqueOutputs() {
        String a = encoder.encode(1);
        String b = encoder.encode(2);
        String c = encoder.encode(100);
        assertNotEquals(a, b);
        assertNotEquals(b, c);
    }

    @Test
    @DisplayName("Encoded values contain only valid Base62 characters")
    void validCharacters() {
        for (long i = 0; i < 1000; i++) {
            String encoded = encoder.encode(i);
            assertTrue(encoded.matches("[0-9a-zA-Z]+"),
                    "Invalid characters in encoded value: " + encoded);
        }
    }
}
