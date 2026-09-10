package com.comassky.wallet.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.hibernate.validator.constraints.time.DurationMin;

import java.time.Duration;

@ConfigMapping(prefix = "electrum")
public interface ElectrumConfig {
    @WithDefault("127.0.0.1") @NotBlank String host();
    @WithDefault("50001") @Min(1) @Max(65535) int port();
    @WithDefault("false") boolean ssl();
    @WithDefault("30s") @DurationMin(nanos = 1) Duration requestTimeout();
}