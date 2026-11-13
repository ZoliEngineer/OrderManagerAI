package com.juzo.ai.ordermanager.advisor.agent;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ApprovalDecision(@NotBlank String toolUseId, boolean approved, @Size(max = 500) String reason) {}
