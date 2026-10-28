package com.noahwallet.btcmap

import org.junit.Assert.*
import org.junit.Test

class BtcMapLinksTest {
    @Test fun websitesOnlyAllowHTTPAndHTTPS() {
        listOf("javascript:alert(1)", "intent://host/#Intent;end", "file:///tmp/a", "tel:1234", "mailto:a@b.com", "https:///path",
            "https://user:password@example.com", "https://example.com\n").forEach {
            assertNull(it, BtcMapLinks.web(it))
        }
        assertEquals("https://example.com/a", BtcMapLinks.web("https://example.com/a"))
        assertEquals("http://example.com", BtcMapLinks.web("http://example.com"))
    }
    @Test fun phoneAndEmailCannotInjectSchemesOrHeaders() {
        assertEquals("tel:+15551234567", BtcMapLinks.phone("+1 (555) 123-4567"))
        listOf("*123#", "tel:123", "123;456", "123?body=bad").forEach { assertNull(BtcMapLinks.phone(it)) }
        assertEquals("mailto:hello@example.com", BtcMapLinks.email("hello@example.com"))
        listOf("a@b.com?bcc=other@b.com", "a@b.com\r\nbcc:other@b.com", "mailto:a@b.com").forEach { assertNull(BtcMapLinks.email(it)) }
    }
}
