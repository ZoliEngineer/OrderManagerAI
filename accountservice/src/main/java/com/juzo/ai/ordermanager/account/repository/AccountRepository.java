package com.juzo.ai.ordermanager.account.repository;

import com.juzo.ai.ordermanager.account.entity.Account;
import org.springframework.data.repository.ListCrudRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccountRepository extends ListCrudRepository<Account, UUID> {

    List<Account> findByUserId(String userId);

    Optional<Account> findByIdAndUserId(UUID id, String userId);
}
