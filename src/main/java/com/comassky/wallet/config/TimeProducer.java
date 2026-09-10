package com.comassky.wallet.config;

import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

import java.time.Clock;

@Singleton
public class TimeProducer {
    @Produces
    @Singleton
    Clock clock() {
        return Clock.systemUTC();
    }
}