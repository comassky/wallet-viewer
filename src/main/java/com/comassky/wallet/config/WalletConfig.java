package com.comassky.wallet.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

@ConfigMapping(prefix = "wallet")
public interface WalletConfig {
    @NotBlank String xpub();
    @WithDefault("mainnet") @Pattern(regexp = "(?i)mainnet|testnet") String network();
    @WithDefault("auto") String scriptType();
    @WithDefault("20") @Min(1) int gapLimit();
    @WithDefault("200") @Min(1) int maxAddresses();
    @WithDefault("8") @Min(1) int rpcConcurrency();
    @WithDefault("false") boolean demo();
}