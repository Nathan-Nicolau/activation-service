package com.deroyque.challenge.activationservice.service;

import com.deroyque.challenge.activationservice.domain.ActivationRequest;
import com.deroyque.challenge.activationservice.dto.CreateActivationRequestDto;
import com.deroyque.challenge.activationservice.exception.IdempotencyConflictException;
import com.deroyque.challenge.activationservice.repository.ActivationRequestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * O objetivo desse helper é atuar como camada intermediária para retorno do Service as requisições que chegam no Controller.
 * */

@Slf4j
@Component
@RequiredArgsConstructor
public class ActivationRequestTransactionHelper {

    private final ActivationRequestRepository repository;

    // Tenta criar a solicitação em uma nova transação isolada ou retorna caso já exista no banco
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CreationResult tentarCriarEmNovaTransacao(CreateActivationRequestDto dto) {
        Optional<ActivationRequest> existente = repository.findByExternalId(dto.externalId());
        if (existente.isPresent()) {
            return validarERetornarExistente(existente.get(), dto);
        }

        ActivationRequest novaSolicitacao = new ActivationRequest(
                dto.externalId(),
                dto.customerId(),
                dto.productCode()
        );
        // saveAndFlush força o INSERT imediato no banco para acionar a constraint UNIQUE se houver concorrência
        ActivationRequest salva = repository.saveAndFlush(novaSolicitacao);
        log.info("[SERVICE] Nova solicitação registrada com sucesso: id={}, externalId={}, customerId={}, productCode={}, status={}",
                salva.getId(), salva.getExternalId(), salva.getCustomerId(), salva.getProductCode(), salva.getStatus());
        return new CreationResult(salva, true);
    }

    // Busca o registro vencedor em uma transação limpa após colisão de concorrência (DataIntegrityViolationException)
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public CreationResult buscarExistenteAposConflitoConcorrente(CreateActivationRequestDto dto) {
        ActivationRequest existente = repository.findByExternalId(dto.externalId())
                .orElseThrow(() -> new IdempotencyConflictException("Conflito concorrente detectado para o externalId: " + dto.externalId()));
        return validarERetornarExistente(existente, dto);
    }

    private CreationResult validarERetornarExistente(ActivationRequest existente, CreateActivationRequestDto dto) {
        if (!existente.possuiMesmoPayload(dto.customerId(), dto.productCode())) {
            log.warn("[SERVICE][ERRO] Conflito de idempotência para externalId={}: registrado(customerId={}, productCode={}) vs recebido(customerId={}, productCode={})",
                    dto.externalId(), existente.getCustomerId(), existente.getProductCode(), dto.customerId(), dto.productCode());
            throw new IdempotencyConflictException("Já existe uma solicitação para o externalId '" + dto.externalId() + "' com customerId ou productCode divergentes.");
        }
        log.info("[SERVICE] Repetição idempotente detectada para externalId={}. Retornando solicitação existente id={} (status={}).",
                existente.getExternalId(), existente.getId(), existente.getStatus());
        return new CreationResult(existente, false);
    }
}
