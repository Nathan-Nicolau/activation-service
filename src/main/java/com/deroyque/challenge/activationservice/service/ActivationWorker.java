package com.deroyque.challenge.activationservice.service;

import com.deroyque.challenge.activationservice.client.ProviderClient;
import com.deroyque.challenge.activationservice.domain.ActivationRequest;
import com.deroyque.challenge.activationservice.domain.ActivationStatus;
import com.deroyque.challenge.activationservice.repository.ActivationRequestRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Essa classe atua como o componente de Scheduling, realizando a consulta ao banco de dados de forma parametrizada,
 * para que as requisições já salvas no banco de dados possam ser processadas
 * */

@Slf4j
@Component
@EnableScheduling
public class ActivationWorker {

    private final ActivationRequestRepository repository;
    private final ProviderClient providerClient;
    private final boolean schedulerAtivo;
    private final int tamanhoLote;
    private final int limiteTentativas;

    public ActivationWorker(
            ActivationRequestRepository repository,
            ProviderClient providerClient,
            @Value("${activation.worker.scheduler-enabled:true}") boolean schedulerAtivo,
            @Value("${activation.worker.batch-size:20}") int tamanhoLote,
            @Value("${activation.worker.max-retries:3}") int limiteTentativas
    ) {
        this.repository = repository;
        this.providerClient = providerClient;
        this.schedulerAtivo = schedulerAtivo;
        this.tamanhoLote = tamanhoLote;
        this.limiteTentativas = limiteTentativas;
    }

    // Executa periodicamente em background quando o agendamento automático está habilitado
    @Scheduled(fixedDelayString = "${activation.worker.delay-ms:2000}")
    public void executarCicloAgendado() {
        if (!schedulerAtivo) {
            return;
        }
        processarSolicitacoesPendentes();
    }

    // Busca solicitações PENDING em lote e processa a integração externa fora de transação longa de banco
    public void processarSolicitacoesPendentes() {
        List<ActivationRequest> pendentes = repository.findByStatusOrderByCreatedAtAsc(
                ActivationStatus.PENDING,
                PageRequest.of(0, tamanhoLote)
        );

        for (ActivationRequest pendente : pendentes) {
            processarSolicitacaoIndividual(pendente.getId());
        }
    }

    private void processarSolicitacaoIndividual(UUID id) {
        // Passo 1: Transação curta (via repository.saveAndFlush) para mudar de PENDING -> PROCESSING
        Optional<ActivationRequest> emProcessamentoOpt = iniciarProcessamentoNoBanco(id);
        if (emProcessamentoOpt.isEmpty()) {
            return;
        }

        ActivationRequest solicitacao = emProcessamentoOpt.get();
        log.info("[SERVICE] Iniciando integração com provedor externo: id={}, externalId={}, productCode={}",
                solicitacao.getId(), solicitacao.getExternalId(), solicitacao.getProductCode());

        // Passo 2: Chamada HTTP remota FORA de transação aberta no banco
        try {
            providerClient.ativarProduto(solicitacao);

            // Passo 3A: Sucesso (2xx) -> Marca como COMPLETED
            solicitacao.marcarComoConcluido();
            repository.saveAndFlush(solicitacao);
            log.info("[SERVICE] Solicitação concluída com sucesso no provedor: id={}, externalId={}, novoStatus={}",
                    solicitacao.getId(), solicitacao.getExternalId(), solicitacao.getStatus());

        } catch (HttpClientErrorException ex) {
            // Passo 3B: Erro 4xx (ex.: HTTP 400) -> Erro definitivo, marca como FAILED sem novas tentativas
            String motivo = "Erro definitivo retornado pelo provedor (HTTP " + ex.getStatusCode().value() + ")";
            solicitacao.marcarComoFalha(motivo);
            repository.saveAndFlush(solicitacao);
            log.error("[SERVICE][ERRO] Falha definitiva na ativação: id={}, externalId={}, motivo='{}', novoStatus={}",
                    solicitacao.getId(), solicitacao.getExternalId(), motivo, solicitacao.getStatus());

        } catch (HttpServerErrorException ex) {
            // Passo 3C: Erro 5xx (ex.: HTTP 500) -> Falha temporária, incrementa tentativas ou marca FAILED se esgotou
            String motivo = "Erro temporário no servidor do provedor (HTTP " + ex.getStatusCode().value() + ")";
            registrarFalhaTemporariaNoBanco(solicitacao, motivo);

        } catch (ResourceAccessException ex) {
            // Passo 3D: Timeout ou erro de conexão -> Falha temporária, incrementa tentativas ou marca FAILED se esgotou
            String motivo = "Timeout ou falha de comunicação com o provedor externo: " + ex.getMessage();
            registrarFalhaTemporariaNoBanco(solicitacao, motivo);

        } catch (Exception ex) {
            // Qualquer outra falha inesperada de integração é tratada com segurança
            String motivo = "Falha inesperada na integração com o provedor: " + ex.getMessage();
            registrarFalhaTemporariaNoBanco(solicitacao, motivo);
        }
    }

    private Optional<ActivationRequest> iniciarProcessamentoNoBanco(UUID id) {
        try {
            Optional<ActivationRequest> opt = repository.findById(id);
            if (opt.isEmpty() || opt.get().getStatus() != ActivationStatus.PENDING) {
                return Optional.empty();
            }
            ActivationRequest solicitacao = opt.get();
            solicitacao.marcarComoProcessando();
            return Optional.of(repository.saveAndFlush(solicitacao));
        } catch (OptimisticLockingFailureException ex) {
            // Outra instância/thread já assumiu o processamento deste registro simultaneamente
            log.debug("[SERVICE] Registro id={} já assumido por outra thread (Optimistic Lock).", id);
            return Optional.empty();
        }
    }

    private void registrarFalhaTemporariaNoBanco(ActivationRequest solicitacao, String motivo) {
        solicitacao.tratarFalhaTemporaria(limiteTentativas, motivo);
        repository.saveAndFlush(solicitacao);
        log.error("[SERVICE][ERRO] Falha temporária na solicitação id={} (externalId={}, tentativa {}/{}): motivo='{}', novoStatus={}",
                solicitacao.getId(), solicitacao.getExternalId(), solicitacao.getRetryCount(),
                limiteTentativas, motivo, solicitacao.getStatus());
    }
}
