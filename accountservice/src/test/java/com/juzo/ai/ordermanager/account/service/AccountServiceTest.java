package com.juzo.ai.ordermanager.account.service;

import com.juzo.ai.ordermanager.account.dto.AccountDetails;
import com.juzo.ai.ordermanager.account.dto.AccountSummary;
import com.juzo.ai.ordermanager.account.entity.Account;
import com.juzo.ai.ordermanager.account.repository.AccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private BuyingPowerCacheService cache;

    private AccountService accountService;

    private final String userId = "user-123";
    private final UUID accountId = UUID.randomUUID();

    private Account buildAccount(UUID id, String name, BigDecimal cash, BigDecimal reserved) {
        return new Account(id, userId, name, cash, reserved, Instant.now(), Instant.now());
    }

    private Account buildAccount(UUID id, String name, BigDecimal balance) {
        return buildAccount(id, name, balance, BigDecimal.ZERO);
    }

    @BeforeEach
    void setUp() {
        accountService = new AccountService(accountRepository, cache);
    }

    // --- getAccountsForUser ---

    @Test
    void getAccountsForUser_returnsMappedSummaries() {
        Account a1 = buildAccount(accountId, "ISA", new BigDecimal("1000.00"));
        Account a2 = buildAccount(UUID.randomUUID(), "SIPP", new BigDecimal("5000.00"));
        when(accountRepository.findByUserId(userId)).thenReturn(List.of(a1, a2));

        List<AccountSummary> result = accountService.getAccountsForUser(userId);

        assertThat(result).hasSize(2);
        assertThat(result.get(0)).isEqualTo(new AccountSummary(a1.id(), a1.displayName()));
        assertThat(result.get(1)).isEqualTo(new AccountSummary(a2.id(), a2.displayName()));
    }

    @Test
    void getAccountsForUser_returnsEmptyListWhenNoAccounts() {
        when(accountRepository.findByUserId(userId)).thenReturn(List.of());

        List<AccountSummary> result = accountService.getAccountsForUser(userId);

        assertThat(result).isEmpty();
    }

    // --- getAccountDetails ---

    @Test
    void getAccountDetails_returnsMappedDetailsWithBuyingPowerFromCache() {
        BigDecimal balance = new BigDecimal("2500.50");
        BigDecimal cached = new BigDecimal("2000.00");
        Account account = buildAccount(accountId, "Trading", balance);
        when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account));
        when(cache.get(accountId)).thenReturn(Optional.of(cached));

        AccountDetails result = accountService.getAccountDetails(accountId, userId);

        assertThat(result).isEqualTo(new AccountDetails(accountId, "Trading", balance, cached));
        verifyNoMoreInteractions(cache);
    }

    @Test
    void getAccountDetails_computesBuyingPowerAndPopulatesCacheOnMiss() {
        BigDecimal cash = new BigDecimal("2500.50");
        BigDecimal reserved = new BigDecimal("500.50");
        BigDecimal expected = new BigDecimal("2000.00");
        Account account = buildAccount(accountId, "Trading", cash, reserved);
        when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account));
        when(cache.get(accountId)).thenReturn(Optional.empty());

        AccountDetails result = accountService.getAccountDetails(accountId, userId);

        assertThat(result).isEqualTo(new AccountDetails(accountId, "Trading", cash, expected));
        verify(cache).put(accountId, expected);
    }

    @Test
    void getAccountDetails_throwsNotFoundWhenAccountMissing() {
        when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accountService.getAccountDetails(accountId, userId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
    }

    @Test
    void getAccountDetails_throwsNotFoundWhenAccountBelongsToDifferentUser() {
        when(accountRepository.findByIdAndUserId(accountId, "other-user")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accountService.getAccountDetails(accountId, "other-user"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
    }

}
