package com.juzo.ai.ordermanager.account.entity;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Table("accounts")
public record Account(
        @Id UUID id,
        @Column("user_id") String userId,
        @Column("display_name") String displayName,
        @Column("cash_balance") BigDecimal cashBalance,
        @Column("reserved_balance") BigDecimal reservedBalance,
        @CreatedDate @Column("created_at") Instant createdAt,
        @LastModifiedDate @Column("updated_at") Instant updatedAt
) {}
