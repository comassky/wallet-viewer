package com.example.walletviewer;

import jakarta.websocket.HandshakeResponse;
import jakarta.websocket.server.HandshakeRequest;
import jakarta.websocket.server.ServerEndpointConfig;

import java.net.URI;

/** Prevent a foreign website from reading a localhost/private wallet through a browser socket. */
public class WalletSocketOrigin extends ServerEndpointConfig.Configurator {
    @Override
    public void modifyHandshake(ServerEndpointConfig config, HandshakeRequest request, HandshakeResponse response) {
        String origin = header(request, "Origin");
        String host = header(request, "Host");
        try {
            URI uri = URI.create(origin);
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

    private static String header(HandshakeRequest request, String name) {
        var values = request.getHeaders().entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(name))
            .flatMap(entry -> entry.getValue().stream()).toList();
        if (values.size() != 1) throw new SecurityException("WebSocket origin rejected");
        return values.getFirst();
    }
}