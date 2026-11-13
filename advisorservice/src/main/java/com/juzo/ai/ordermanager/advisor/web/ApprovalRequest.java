package com.juzo.ai.ordermanager.advisor.web;

import com.juzo.ai.ordermanager.advisor.agent.ApprovalDecision;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record ApprovalRequest(@NotEmpty List<@Valid ApprovalDecision> decisions) {}
