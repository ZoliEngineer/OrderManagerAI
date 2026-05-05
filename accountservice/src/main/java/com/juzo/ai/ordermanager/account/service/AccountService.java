package com.juzo.ai.ordermanager.account.service;

import com.juzo.ai.ordermanager.account.dto.AccountDetails;
import com.juzo.ai.ordermanager.account.dto.AccountSummary;
import com.juzo.ai.ordermanager.account.repository.AccountRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class AccountService {

    private final AccountRepository accountRepository;

    public AccountService(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    public List<AccountSummary> getAccountsForUser(String userId) {
        return accountRepository.findByUserId(userId).stream()
                .map(a -> new AccountSummary(a.id(), a.displayName()))
                .toList();
    }

    public AccountDetails getAccountDetails(UUID id, String userId) {
        return accountRepository.findByIdAndUserId(id, userId)
                .map(a -> new AccountDetails(a.id(), a.displayName(), a.cashBalance()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
}
