package tech.httptoolkit.android.manual

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyAddressParserTest {

    @Test
    fun `parses a plain IPv4 address`() {
        assertEquals(
            ParsedProxyAddress("192.168.0.10", null),
            ProxyAddressParser.parse("192.168.0.10")
        )
    }

    @Test
    fun `parses an IPv4 address with a port`() {
        assertEquals(
            ParsedProxyAddress("192.168.0.10", 8000),
            ProxyAddressParser.parse("192.168.0.10:8000")
        )
    }

    @Test
    fun `parses a hostname`() {
        assertEquals(
            ParsedProxyAddress("my-laptop.local", null),
            ProxyAddressParser.parse("my-laptop.local")
        )
    }

    @Test
    fun `trims surrounding whitespace`() {
        assertEquals(
            ParsedProxyAddress("10.0.2.2", 8000),
            ProxyAddressParser.parse("  10.0.2.2:8000  ")
        )
    }

    @Test
    fun `strips schemes, paths, queries and fragments`() {
        assertEquals(
            ParsedProxyAddress("192.168.0.10", 8000),
            ProxyAddressParser.parse("http://192.168.0.10:8000/")
        )
        assertEquals(
            ParsedProxyAddress("192.168.0.10", null),
            ProxyAddressParser.parse("https://192.168.0.10/some/path?query=1#fragment")
        )
    }

    @Test
    fun `accepts a port in the address even if it has a trailing path`() {
        assertEquals(
            ParsedProxyAddress("192.168.0.10", 8080),
            ProxyAddressParser.parse("192.168.0.10:8080/path")
        )
    }

    @Test
    fun `parses bracketed IPv6 addresses`() {
        assertEquals(
            ParsedProxyAddress("::1", null),
            ProxyAddressParser.parse("[::1]")
        )
        assertEquals(
            ParsedProxyAddress("::1", 8000),
            ProxyAddressParser.parse("[::1]:8000")
        )
        assertEquals(
            ParsedProxyAddress("fe80::1%wlan0", 8000),
            ProxyAddressParser.parse("[fe80::1%wlan0]:8000")
        )
    }

    @Test
    fun `parses bare IPv6 addresses without treating their colons as a port`() {
        assertEquals(
            ParsedProxyAddress("fe80::1", null),
            ProxyAddressParser.parse("fe80::1")
        )
    }

    @Test
    fun `rejects empty and malformed input`() {
        listOf(
            "",
            "   ",
            "host:",
            "host:0",
            "host:65536",
            "host:not-a-port",
            "[::1",
            "[::1]8000",
            "[not-an-ipv6]",
            "user@host",
            "host name",
            "host:8000:9000"
        ).forEach { input ->
            assertNull("Expected '$input' to be rejected", ProxyAddressParser.parse(input))
        }
    }

    @Test
    fun `recognizes HTTP Toolkit connect URLs`() {
        assertTrue(ProxyAddressParser.isConnectUrl(
            "https://android.httptoolkit.tech/connect/?data=abc123"
        ))
        assertTrue(ProxyAddressParser.isConnectUrl(
            "http://android.httptoolkit.tech/connect?data=abc123"
        ))
        assertTrue(ProxyAddressParser.isConnectUrl(
            "https://android.httptoolkit.tech/connect/abc123"
        ))

        assertFalse(ProxyAddressParser.isConnectUrl("192.168.0.10:8000"))
        assertFalse(ProxyAddressParser.isConnectUrl("android.httptoolkit.tech"))
        assertFalse(ProxyAddressParser.isConnectUrl("https://example.com/connect/?data=abc"))
    }
}
