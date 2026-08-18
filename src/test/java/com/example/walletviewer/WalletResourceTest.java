package com.example.walletviewer;

import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WalletResourceTest {
    @Test
    void negativeAddressIndicesReturnBadRequestBeforeDerivation() {
        WalletResource resource = new WalletResource();
        assertEquals(400, assertThrows(BadRequestException.class,
                () -> resource.receiveAt(-1)).getResponse().getStatus());
        assertEquals(400, assertThrows(BadRequestException.class,
                () -> resource.receiveQrAt(-1)).getResponse().getStatus());
    }
}