package com.comassky.wallet.web;

import io.vertx.core.MultiMap;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WalletSocketOriginTest {

    @Test
    void allowsSameHostForHttpAndHttps() {
        assertDoesNotThrow(() -> handshake(headers("http://wallet.example", "wallet.example")));
        assertDoesNotThrow(() -> handshake(headers("https://wallet.example", "wallet.example")));
    }

    @Test
    void allowsMatchingExplicitPortAndIpv6Authority() {
        assertDoesNotThrow(() -> handshake(headers("http://localhost:8080", "localhost:8080")));
        assertDoesNotThrow(() -> handshake(headers("http://[::1]:8080", "[::1]:8080")));
    }

    @Test
    void headerNamesAndHostAreCaseInsensitive() {
        assertDoesNotThrow(() -> handshake(Map.of(
                "oRiGiN", List.of("https://WALLET.example:8443"),
                "hOsT", List.of("wallet.EXAMPLE:8443"))));
    }

    @Test
    void rejectsForeignHostAndDifferentPort() {
        assertAll(
                () -> rejected(headers("https://evil.example", "wallet.example")),
                () -> rejected(headers("https://wallet.example.evil.example", "wallet.example")),
                () -> rejected(headers("http://localhost:8081", "localhost:8080")));
    }

    @Test
    void rejectsMissingEmptyAndOpaqueNullOrigin() {
        assertAll(
                () -> rejected(Map.of("Host", List.of("wallet.example"))),
                () -> rejected(Map.of("Host", List.of("wallet.example"), "Origin", List.of())),
                () -> rejected(headers("", "wallet.example")),
                () -> rejected(headers("null", "wallet.example")));
    }

    @Test
    void rejectsMultipleOriginValuesEvenWhenOneMatches() {
        assertAll(
                () -> rejected(Map.of("Host", List.of("wallet.example"),
                        "Origin", List.of("https://wallet.example", "https://evil.example"))),
                () -> rejected(Map.of("Host", List.of("wallet.example"),
                        "Origin", List.of("https://evil.example", "https://wallet.example"))),
                () -> rejected(Map.of("Host", List.of("wallet.example"),
                        "Origin", List.of("https://wallet.example", "https://wallet.example"))));
    }

    @Test
    void rejectsCommaAndSpaceSeparatedOrigins() {
        assertAll(
                () -> rejected(headers("https://wallet.example,https://evil.example", "wallet.example")),
                () -> rejected(headers("https://wallet.example https://evil.example", "wallet.example")));
    }

    @Test
    void rejectsAmbiguousOriginHeadersWithDifferentCasing() {
        // Header names are case-insensitive: selecting the first matching entry is unsafe.
        Map<String, List<String>> headers = new LinkedHashMap<>();
        headers.put("Host", List.of("wallet.example"));
        headers.put("Origin", List.of("https://wallet.example"));
        headers.put("origin", List.of("https://evil.example"));
        rejected(headers);
    }

    @Test
    void rejectsMalformedOriginsUnsupportedSchemesAndUserInfo() {
        assertAll(
                () -> rejected(headers("not a URI", "wallet.example")),
                () -> rejected(headers("https://", "wallet.example")),
                () -> rejected(headers("file://wallet.example", "wallet.example")),
                () -> rejected(headers("ws://wallet.example", "wallet.example")),
                () -> rejected(headers("https://user@wallet.example", "wallet.example")));
    }

    @Test
    void rejectsMissingBlankAndMultipleHostValues() {
        assertAll(
                () -> rejected(Map.of("Origin", List.of("https://wallet.example"))),
                () -> rejected(headers("https://wallet.example", " ")),
                () -> rejected(Map.of("Origin", List.of("https://wallet.example"),
                        "Host", List.of("wallet.example", "evil.example"))));
    }

    private void rejected(Map<String, List<String>> headers) {
        assertThrows(SecurityException.class, () -> handshake(headers));
    }

    private void handshake(Map<String, List<String>> headers) {
        MultiMap values = MultiMap.caseInsensitiveMultiMap();
        headers.forEach(values::add);
        WalletSocketOrigin.validate(values);
    }

    private static Map<String, List<String>> headers(String origin, String host) {
        return Map.of("Origin", List.of(origin), "Host", List.of(host));
    }

}