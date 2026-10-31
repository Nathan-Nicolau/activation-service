package com.deroyque.challenge.activationservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateActivationRequestDto(
        @NotBlank(message = "O campo externalId é obrigatório")
        @Size(max = 100, message = "O campo externalId deve ter no máximo 100 caracteres")
        String externalId,

        @NotBlank(message = "O campo customerId é obrigatório")
        @Size(max = 100, message = "O campo customerId deve ter no máximo 100 caracteres")
        String customerId,

        @NotBlank(message = "O campo productCode é obrigatório")
        @Size(max = 100, message = "O campo productCode deve ter no máximo 100 caracteres")
        String productCode
) {
}
