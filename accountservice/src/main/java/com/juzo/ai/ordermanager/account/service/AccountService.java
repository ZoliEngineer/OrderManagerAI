package com.juzo.ai.ordermanager.account.service;

import com.juzo.ai.ordermanager.account.dto.AccountDetails;
import com.juzo.ai.ordermanager.account.dto.AccountSummary;
import com.juzo.ai.ordermanager.account.entity.Account;
import com.juzo.ai.ordermanager.account.repository.AccountRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class AccountService {

    private final AccountRepository accountRepository;
    private final BuyingPowerCacheService cache;

    public AccountService(AccountRepository accountRepository, BuyingPowerCacheService cache) {
        this.accountRepository = accountRepository;
        this.cache = cache;
    }

    public List<AccountSummary> getAccountsForUser(String userId) {
        return accountRepository.findByUserId(userId).stream()
                .map(a -> new AccountSummary(a.id(), a.displayName()))
                .toList();
    }

    public AccountDetails getAccountDetails(UUID id, String userId) {
        return accountRepository.findByIdAndUserId(id, userId)
                .map(a -> new AccountDetails(a.id(), a.displayName(), a.cashBalance(), resolveBuyingPower(id, a)))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private BigDecimal resolveBuyingPower(UUID id, Account account) {
        return cache.get(id).orElseGet(() -> {
            BigDecimal bp = account.cashBalance().subtract(account.reservedBalance());
            cache.put(id, bp);
            return bp;
        });
    }

}
