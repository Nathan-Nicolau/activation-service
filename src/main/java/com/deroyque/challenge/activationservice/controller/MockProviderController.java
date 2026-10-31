package com.deroyque.challenge.activationservice.controller;

import com.deroyque.challenge.activationservice.client.ProviderClient;
import com.deroyque.challenge.activationservice.dto.ProviderActivationRequestDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@RestController
@RequestMapping("/provider/v1")
public class MockProviderController {

    private final Set<String> chavesProcessadas = ConcurrentHashMap.newKeySet();

    /**
     * Simula o provedor externo de ativação de produtos:<br>
     * - productCode iniciando com "ERR-400" -> retorna HTTP 400 Bad Request<br>
     * - productCode iniciando com "ERR-500" -> retorna HTTP 500 Internal Server Error<br>
     * - productCode iniciando com "ERR-TIMEOUT" -> aguarda 3s para simular Timeout de leitura<br>
     * - demais códigos (ex.: "INTERNET-500", "TV-PLAN-500") -> retorna HTTP 200 OK<br>
     */
    @PostMapping("/activate")
    public ResponseEntity<Map<String, String>> simularAtivacao(
            @RequestHeader(value = ProviderClient.IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @RequestBody ProviderActivationRequestDto request
    ) throws InterruptedException {
        if (idempotencyKey != null && chavesProcessadas.contains(idempotencyKey)) {
            log.info("Provedor externo: chave de idempotência '{}' já ativada anteriormente. Retornando 200 OK.", idempotencyKey);
            return ResponseEntity.ok(Map.of("status", "ALREADY_ACTIVATED"));
        }

        String codigoProduto = request.productCode() != null ? request.productCode().toUpperCase() : "";

        if (codigoProduto.startsWith("ERR-400")) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Código de produto inválido ou incompatível no provedor externo."));
        }

        if (codigoProduto.startsWith("ERR-500")) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Falha interna temporária no provedor externo."));
        }

        if (codigoProduto.startsWith("ERR-TIMEOUT")) {
            Thread.sleep(3000);
        }

        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            chavesProcessadas.add(idempotencyKey);
        }

        log.info("Provedor externo: produto '{}' ativado com sucesso para o cliente '{}'.",
                request.productCode(), request.customerId());

        //Aqui temos o retorno direto de que o serviço foi ativado, mas num cenário real, esse provedor externo poderia
        //chamar outras rotinas de validação
        return ResponseEntity.ok(Map.of("status", "ACTIVATED"));
    }
}
