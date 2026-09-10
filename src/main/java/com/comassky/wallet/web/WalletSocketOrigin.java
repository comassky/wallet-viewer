package com.comassky.wallet.web;

import io.quarkus.websockets.next.HttpUpgradeCheck;
import io.smallrye.mutiny.Uni;
import io.vertx.core.MultiMap;
import jakarta.enterprise.context.ApplicationScoped;

import java.net.URI;

/** Prevent a foreign website from reading a localhost/private wallet through a browser socket. */
@ApplicationScoped
public class WalletSocketOrigin implements HttpUpgradeCheck {
    @Override
    public Uni<CheckResult> perform(HttpUpgradeContext context) {
        try {
            validate(context.httpRequest().headers());
            return CheckResult.permitUpgrade();
        } catch (SecurityException failure) {
            return CheckResult.rejectUpgrade(403);
        }
    }

    static void validate(MultiMap headers) {
        final String origin = header(headers, "Origin");
        final String host = header(headers, "Host");
        try {
            final URI uri = URI.create(origin);
            if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                    || uri.getRawUserInfo() != null || host.isBlank()
                    || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || !uri.getRawPath().isEmpty()
                    || !host.equalsIgnoreCase(uri.getRawAuthority())) {
                throw new IllegalArgumentException("WebSocket origin rejected");
            }
        } catch (RuntimeException failure) {
            throw new SecurityException("WebSocket origin rejected");
        }
    }

    private static String header(MultiMap headers, String name) {
        final var values = headers.getAll(name);
        if (values.size() != 1) throw new SecurityException("WebSocket origin rejected");
        return values.getFirst();
    }
}