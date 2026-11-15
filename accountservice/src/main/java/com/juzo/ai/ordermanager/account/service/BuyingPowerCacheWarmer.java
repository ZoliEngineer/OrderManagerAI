package com.juzo.ai.ordermanager.account.service;

import com.juzo.ai.ordermanager.account.repository.AccountRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class BuyingPowerCacheWarmer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BuyingPowerCacheWarmer.class);

    private final AccountRepository accountRepository;
    private final BuyingPowerCacheService cache;
    private final JdbcTemplate jdbcTemplate;
    private final AtomicInteger warmed = new AtomicInteger();

    public BuyingPowerCacheWarmer(AccountRepository accountRepository, BuyingPowerCacheService cache,
                                  JdbcTemplate jdbcTemplate, MeterRegistry meters) {
        this.accountRepository = accountRepository;
        this.cache = cache;
        this.jdbcTemplate = jdbcTemplate;
        Gauge.builder("account.cache.warmed.accounts", warmed, AtomicInteger::get)
                .description("Accounts written to Redis by the last startup warm-up")
                .register(meters);
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        // Set the system sentinel so the RLS policy allows all rows within this transaction.
        // The 'true' flag makes the setting transaction-local — it is cleared automatically on commit.
        jdbcTemplate.execute("SELECT set_config('app.current_user_id', '__system__', true)");

        AtomicInteger count = new AtomicInteger();
        accountRepository.findAll().forEach(account -> {
            BigDecimal buyingPower = account.cashBalance().subtract(account.reservedBalance());
            cache.put(account.id(), buyingPower);
            count.incrementAndGet();
        });
        warmed.set(count.get());
        log.info("Pre-warmed buying power cache for {} accounts", count.get());
    }
}
