package com.example.walletviewer.web;

import com.example.walletviewer.electrum.ElectrumClient;
import com.example.walletviewer.model.ElectrumServerDto;
import com.example.walletviewer.model.TransactionDetailsDto;
import com.example.walletviewer.service.TransactionDetailsService;
import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.Test;

import static com.example.walletviewer.support.TestFields.setField;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WalletResourceTest {
    @Test
    void serverEndpointReadsUninitializedTransportWithoutAnyRpc() {
        WalletResource resource = new WalletResource();
        resource.electrum = new ElectrumClient() {
            @Override public Uni<io.vertx.core.json.JsonObject> call(String method, Object... params) {
                throw new AssertionError("Metadata endpoint must never send an RPC");
            }
        };
        setField(ElectrumClient.class, resource.electrum, "host", "configured-host");
        setField(ElectrumClient.class, resource.electrum, "port", 50002);
        setField(ElectrumClient.class, resource.electrum, "ssl", true);
        ElectrumServerDto expected = new ElectrumServerDto("configured-host", 50002, true, false, null, null);
        for (int i = 0; i < 10; i++) assertEquals(expected, resource.server());
    }

    @Test
    void unknownMetadataIsSerializedAsExplicitNullEvenWhenMapperOmitsNulls() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        mapper.setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL);
        var tree = mapper.readTree(mapper.writeValueAsString(
                new ElectrumServerDto("host", 50002, true, false, null, null)));
        assertEquals(6, tree.size());
        assertEquals(true, tree.has("serverVersion") && tree.get("serverVersion").isNull());
        assertEquals(true, tree.has("protocolVersion") && tree.get("protocolVersion").isNull());
    }

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