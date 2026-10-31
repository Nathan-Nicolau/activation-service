package com.deroyque.challenge.activationservice.service;

import com.deroyque.challenge.activationservice.domain.ActivationRequest;
import com.deroyque.challenge.activationservice.domain.ActivationStatus;
import com.deroyque.challenge.activationservice.dto.ActivationRequestResponseDto;
import com.deroyque.challenge.activationservice.dto.CreateActivationRequestDto;
import com.deroyque.challenge.activationservice.exception.ResourceNotFoundException;
import com.deroyque.challenge.activationservice.repository.ActivationRequestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ActivationRequestService {

    private final ActivationRequestRepository repository;
    private final ActivationRequestTransactionHelper transactionHelper;

    /**
     * Cria uma nova solicitação ou retorna a existente garantindo idempotência por externalId,
     * inclusive quando múltiplas requisições idênticas chegam simultaneamente em paralelo.
     */
    public CreationResult criarOuObterSolicitacao(CreateActivationRequestDto dto) {
        log.info("[SERVICE] Recebida solicitação de ativação: externalId={}, customerId={}, productCode={}", dto.externalId(), dto.customerId(), dto.productCode());
        try {
            return transactionHelper.tentarCriarEmNovaTransacao(dto);
        } catch (DataIntegrityViolationException ex) {
            log.warn("[SERVICE] Colisão de concorrência detectada no banco para externalId={}. Recuperando registro vencedor.", dto.externalId());
            return transactionHelper.buscarExistenteAposConflitoConcorrente(dto);
        }
    }

    // Busca uma solicitação pelo seu ID único ou lança exceção 404 caso não exista
    @Transactional(readOnly = true)
    public ActivationRequestResponseDto buscarPorId(UUID id) {
        log.info("[SERVICE] Consultando solicitação por id={}", id);
        ActivationRequest solicitacao = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Solicitação de ativação não encontrada para o id: " + id));
        return ActivationRequestResponseDto.converterDeEntidade(solicitacao);
    }

    // Lista solicitações de forma paginada aplicando filtros opcionais de status e customerId
    @Transactional(readOnly = true)
    public Page<ActivationRequestResponseDto> listarPorFiltros(
            ActivationStatus status,
            String customerId,
            Pageable pageable
    ) {
        log.info("[SERVICE] Listando solicitações: filtroStatus={}, filtroCustomerId={}, pagina={}, tamanho={}", status, customerId, pageable.getPageNumber(), pageable.getPageSize());
        return repository.buscarPorFiltros(status, customerId, pageable).map(ActivationRequestResponseDto::converterDeEntidade);
    }
}
