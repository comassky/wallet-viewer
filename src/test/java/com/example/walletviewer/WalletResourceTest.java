package com.example.walletviewer;

import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WalletResourceTest {
    @Test
    void transactionDetailsDelegateToTheDetailsService() {
        WalletResource resource = new WalletResource();
        Uni<TransactionDetailsDto> response = Uni.createFrom().nullItem();
        resource.transactionDetails = new TransactionDetailsService() {
            @Override public Uni<TransactionDetailsDto> details(String txid) {
                assertEquals("requested-id", txid);
                return response;
            }
        };
        assertSame(response, resource.transactionDetails("requested-id"));
    }

    @Test
    void negativeAddressIndicesReturnBadRequestBeforeDerivation() {
        WalletResource resource = new WalletResource();
        assertEquals(400, assertThrows(BadRequestException.class,
                () -> resource.receiveAt(-1)).getResponse().getStatus());
        assertEquals(400, assertThrows(BadRequestException.class,
                () -> resource.receiveQrAt(-1)).getResponse().getStatus());
    }
}