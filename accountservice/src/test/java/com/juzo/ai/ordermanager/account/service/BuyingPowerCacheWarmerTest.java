package com.juzo.ai.ordermanager.account.service;

import com.juzo.ai.ordermanager.account.entity.Account;
import com.juzo.ai.ordermanager.account.repository.AccountRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BuyingPowerCacheWarmerTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private BuyingPowerCacheService cache;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private BuyingPowerCacheWarmer warmer;

    @Test
    void run_putsAllAccountsIntoCache() throws Exception {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        Account a1 = new Account(id1, "user-1", "John Smith — Trading",
                new BigDecimal("100000.0000"), new BigDecimal("5000.0000"), Instant.now(), Instant.now());
        Account a2 = new Account(id2, "user-2", "Jane Doe — Retirement",
                new BigDecimal("50000.0000"), BigDecimal.ZERO, Instant.now(), Instant.now());

        when(accountRepository.findAll()).thenReturn(List.of(a1, a2));

        warmer.run(new DefaultApplicationArguments());

        verify(cache).put(id1, new BigDecimal("95000.0000")); // 100000 - 5000
        verify(cache).put(id2, new BigDecimal("50000.0000")); // 50000 - 0
    }

    @Test
    void run_doesNothingWhenNoAccounts() throws Exception {
        when(accountRepository.findAll()).thenReturn(List.of());

        warmer.run(new DefaultApplicationArguments());

        // no interactions with cache
    }
}
