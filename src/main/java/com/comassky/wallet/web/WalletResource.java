package com.comassky.wallet.web;

import com.comassky.wallet.electrum.ElectrumClient;
import com.comassky.wallet.model.AddressCheckDto;
import com.comassky.wallet.model.AddressInfo;
import com.comassky.wallet.model.BalanceDto;
import com.comassky.wallet.model.ElectrumServerDto;
import com.comassky.wallet.model.FeeRatesDto;
import com.comassky.wallet.model.PriceRatesDto;
import com.comassky.wallet.model.BalancePointDto;
import com.comassky.wallet.model.ReceiveAddressDto;
import com.comassky.wallet.model.TransactionDetailsDto;
import com.comassky.wallet.model.TransactionDto;
import com.comassky.wallet.model.UtxoDto;
import com.comassky.wallet.model.WalletSnapshot;
import com.comassky.wallet.service.PriceService;
import com.comassky.wallet.service.BalanceHistoryService;
import com.comassky.wallet.service.FeeService;
import com.comassky.wallet.service.TransactionDetailsService;
import com.comassky.wallet.service.WalletLiveService;
import com.comassky.wallet.service.WalletService;
import com.comassky.wallet.util.QrGenerator;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;

@Path("/api/wallet")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Wallet", description = "Read-only wallet views derived from Electrum and public market data.")
public class WalletResource {

    @Inject
    WalletService service;

    @Inject
    WalletLiveService live;

    @Inject
    PriceService prices;

    @Inject
    BalanceHistoryService balanceHistory;

    @Inject
    FeeService feeService;

    @Inject
    TransactionDetailsService transactionDetails;

    @Inject
    ElectrumClient electrum;

    @GET
    @Path("/server")
    @Operation(summary = "Electrum server info",
            description = "Connection state and negotiated version of the configured Electrum server; purely local, no RPC.")
    @APIResponse(responseCode = "200", description = "Current Electrum server status",
            content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ElectrumServerDto.class)))
    public ElectrumServerDto server() {
        return electrum.serverInfo();
    }

    @GET
    @Path("/prices")
    @Operation(summary = "Current BTC price", description = "Latest EUR and USD Bitcoin quote from mempool.space.")
    @APIResponse(responseCode = "200", description = "Current BTC price",
            content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = PriceRatesDto.class)))
    @APIResponse(responseCode = "503", description = "Price provider temporarily unavailable")
    public Uni<PriceRatesDto> prices() {
        return prices.rates();
    }

    @GET
    @Path("/fees")
    @Operation(summary = "Recommended fees", description = "Recommended mempool fee estimates in sat/vB.")
    @APIResponse(responseCode = "200", description = "Recommended fee rates",
            content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = FeeRatesDto.class)))
    @APIResponse(responseCode = "503", description = "Fee provider temporarily unavailable")
    public Uni<FeeRatesDto> fees() {
        return feeService.fees();
    }

    @GET
    @Path("/balance-history")
    @Operation(summary = "Balance history", description = "Daily wallet balance valued at each day's BTC price.")
    @APIResponse(responseCode = "200", description = "Daily balance points",
            content = @Content(mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(type = SchemaType.ARRAY, implementation = BalancePointDto.class)))
    @APIResponse(responseCode = "503", description = "Price history temporarily unavailable")
    public Uni<List<BalancePointDto>> balanceHistory() {
        return balanceHistory.history();
    }

    @GET
    @Operation(summary = "Wallet snapshot",
            description = "Full wallet snapshot: balance, UTXOs, transactions and the next receive address.")
    @APIResponse(responseCode = "200", description = "Current wallet snapshot",
            content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = WalletSnapshot.class)))
    public Uni<WalletSnapshot> snapshot() {
        return live.snapshot();
    }

    @GET
    @Path("/balance")
    @Operation(summary = "Wallet balance", description = "Confirmed, unconfirmed and total balance in satoshis.")
    @APIResponse(responseCode = "200", description = "Current wallet balance",
            content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = BalanceDto.class)))
    public Uni<BalanceDto> balance() {
        return live.snapshot().map(WalletSnapshot::balance);
    }

    @GET
    @Path("/transactions")
    @Operation(summary = "Transactions", description = "Wallet transactions with their net effect, newest first.")
    @APIResponse(responseCode = "200", description = "Wallet transactions",
            content = @Content(mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(type = SchemaType.ARRAY, implementation = TransactionDto.class)))
    public Uni<List<TransactionDto>> transactions() {
        return live.snapshot().map(WalletSnapshot::transactions);
    }

    @GET
    @Path("/transactions/{txid}")
    @Operation(summary = "Transaction details", description = "On-demand decoded details for a wallet transaction.")
    @APIResponse(responseCode = "200", description = "Decoded transaction details",
            content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = TransactionDetailsDto.class)))
    @APIResponse(responseCode = "400", description = "Invalid transaction id")
    @APIResponse(responseCode = "404", description = "Transaction is not part of this wallet")
    public Uni<TransactionDetailsDto> transactionDetails(
            @Parameter(description = "Transaction id (hex)", required = true) @PathParam("txid") String txid) {
        return transactionDetails.details(txid);
    }

    @GET
    @Path("/utxos")
    @Operation(summary = "UTXOs", description = "Unspent transaction outputs owned by the wallet.")
    @APIResponse(responseCode = "200", description = "Wallet UTXOs",
            content = @Content(mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(type = SchemaType.ARRAY, implementation = UtxoDto.class)))
    public Uni<List<UtxoDto>> utxos() {
        return live.snapshot().map(WalletSnapshot::utxos);
    }

    @GET
    @Path("/receive")
    @Operation(summary = "Next receive address", description = "Next unused receive address of the wallet.")
    @APIResponse(responseCode = "200", description = "Next receive address",
            content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ReceiveAddressDto.class)))
    public Uni<ReceiveAddressDto> receive() {
        return live.snapshot().map(WalletSnapshot::receiveAddress);
    }

    @GET
    @Path("/verify")
    @Operation(summary = "Verify address",
            description = "Checks whether an address is derived from the configured wallet key; purely local.")
    @APIResponse(responseCode = "200", description = "Verification result",
            content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = AddressCheckDto.class)))
    @APIResponse(responseCode = "400", description = "Missing or oversized address")
    public AddressCheckDto verify(
            @Parameter(description = "Bitcoin address to verify", required = true)
            @QueryParam("address") @NotBlank @Size(max = 128) String address) {
        return service.verifyAddress(address);
    }

    @GET
    @Path("/receive/{index}")
    @Operation(summary = "Receive address at index", description = "Derives the receive address at the given index.")
    @APIResponse(responseCode = "200", description = "Derived receive address",
            content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ReceiveAddressDto.class)))
    @APIResponse(responseCode = "400", description = "Negative index")
    public ReceiveAddressDto receiveAt(
            @Parameter(description = "Receive address index (>= 0)", required = true) @PathParam("index") @Min(0) int index) {
        AddressInfo a = service.derive(0, index);
        return new ReceiveAddressDto(a.index, a.address, a.path);
    }

    @GET
    @Path("/receive/qr")
    @Produces("image/png")
    @Operation(summary = "Next receive address QR", description = "PNG QR code for the next receive address.")
    @APIResponse(responseCode = "200", description = "PNG QR image",
            content = @Content(mediaType = "image/png", schema = @Schema(type = SchemaType.STRING, format = "binary")))
    public Uni<byte[]> receiveQr() {
        return live.snapshot().map(s -> QrGenerator.png(s.receiveAddress().address(), 320));
    }

    @GET
    @Path("/receive/{index}/qr")
    @Produces("image/png")
    @Operation(summary = "Receive address QR at index", description = "PNG QR code for the receive address at the given index.")
    @APIResponse(responseCode = "200", description = "PNG QR image",
            content = @Content(mediaType = "image/png", schema = @Schema(type = SchemaType.STRING, format = "binary")))
    @APIResponse(responseCode = "400", description = "Negative index")
    public byte[] receiveQrAt(
            @Parameter(description = "Receive address index (>= 0)", required = true) @PathParam("index") @Min(0) int index) {
        return QrGenerator.png(receiveAt(index).address(), 320);
    }
}
