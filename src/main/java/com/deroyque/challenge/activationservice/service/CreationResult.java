package com.deroyque.challenge.activationservice.service;

import com.deroyque.challenge.activationservice.domain.ActivationRequest;

// Record para retorno da ActivationRequest criada
public record CreationResult(
        ActivationRequest request,
        boolean createdNow
) {}
