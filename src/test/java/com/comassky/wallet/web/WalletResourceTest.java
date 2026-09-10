package com.comassky.wallet.web;

import com.comassky.wallet.electrum.ElectrumClient;
import com.comassky.wallet.model.ElectrumServerDto;
import com.comassky.wallet.model.TransactionDetailsDto;
import com.comassky.wallet.service.TransactionDetailsService;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import static com.comassky.wallet.support.TestFields.setField;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class WalletResourceTest {
    @Test
    void serverEndpointReadsUninitializedTransportWithoutAnyRpc() {
        WalletResource resource = new WalletResource();
        resource.electrum = new ElectrumClient() {
            @Override public Uni<JsonObject> call(String method, Object... params) {
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
        ObjectMapper mapper = new ObjectMapper();
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
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

}