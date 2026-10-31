package com.deroyque.challenge.activationservice.ui;

import com.deroyque.challenge.activationservice.domain.ActivationStatus;
import com.deroyque.challenge.activationservice.dto.ActivationRequestResponseDto;
import com.deroyque.challenge.activationservice.dto.CreateActivationRequestDto;
import com.deroyque.challenge.activationservice.exception.IdempotencyConflictException;
import com.deroyque.challenge.activationservice.exception.ResourceNotFoundException;
import com.deroyque.challenge.activationservice.service.ActivationRequestService;
import com.deroyque.challenge.activationservice.service.CreationResult;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Pre;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.tabs.Tab;
import com.vaadin.flow.component.tabs.Tabs;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.renderer.ComponentRenderer;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Route("")
@PageTitle("Serviço de Ativação de Produtos")
public class ActivationView extends VerticalLayout {

    private static final DateTimeFormatter FORMATADOR_DATA = DateTimeFormatter
            .ofPattern("dd/MM/yyyy HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    // Padrão das linhas do log padrão do Spring Boot (ISO timestamp, nível, thread, logger e mensagem)
    private static final Pattern PADRAO_LINHA_LOG = Pattern.compile(
            "^(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}[+-]\\d{2}:\\d{2})\\s+(\\w+)\\s+\\d+\\s+---\\s+\\[[^\\]]+\\]\\s+\\[([^\\]]+)\\]\\s+([^:]+?)\\s*:\\s*(.*)$"
    );

    private final ActivationRequestService service;
    private final Path caminhoArquivoLog;

    // Abas do menu de navegação superior
    private final Tab abaSolicitacoes = new Tab("Solicitações de Ativação");
    private final Tab abaLogs = new Tab("Logs de Auditoria ([SERVICE])");
    private final VerticalLayout containerSolicitacoes = new VerticalLayout();
    private final VerticalLayout containerLogs = new VerticalLayout();

    // Campos de criação de solicitação
    private final TextField campoExternalId = new TextField("External ID");
    private final TextField campoCustomerId = new TextField("Customer ID");
    private final ComboBox<String> campoProductCode = new ComboBox<>("Product Code (Cenário)");

    // Campos de filtro e listagem de solicitações
    private final ComboBox<ActivationStatus> filtroStatus = new ComboBox<>("Filtrar por Status");
    private final TextField filtroCustomerId = new TextField("Filtrar por Customer ID");
    private final TextField filtroBuscaId = new TextField("Buscar por ID (UUID)");
    private final Grid<ActivationRequestResponseDto> tabela = new Grid<>(ActivationRequestResponseDto.class, false);

    // Controle de paginação
    private int paginaAtual = 0;
    private static final int TAMANHO_PAGINA = 10;
    private int totalPaginas = 1;
    private final Span rotuloPaginacao = new Span("Página 1 de 1");
    private final Button botaoAnterior = new Button("Anterior");
    private final Button botaoProxima = new Button("Próxima");

    // Componentes da aba de Logs
    private final ComboBox<String> filtroEscopoLog = new ComboBox<>("Escopo");
    private final ComboBox<String> filtroNivelLog = new ComboBox<>("Nível");
    private final TextField filtroTextoLog = new TextField("Buscar texto / External ID / UUID");
    private final Grid<RegistroLog> tabelaLogs = new Grid<>(RegistroLog.class, false);
    private final Pre consoleLogs = new Pre();
    private final Span contadorTotalLogs = new Span("0 eventos");
    private final Span contadorErrosLogs = new Span("0 erros");

    public ActivationView(
            ActivationRequestService service,
            @Value("${logging.file.name:logs/activation-audit.log}") String arquivoLog
    ) {
        this.service = service;
        this.caminhoArquivoLog = Path.of(arquivoLog);

        setSizeFull();
        setPadding(true);
        setSpacing(true);

        HorizontalLayout barraNavegacao = construirMenuNavegacaoSuperior();

        // Monta conteúdo da Aba 1 (Solicitações)
        containerSolicitacoes.setSizeFull();
        containerSolicitacoes.setPadding(false);
        containerSolicitacoes.setSpacing(true);
        containerSolicitacoes.add(construirSecaoCriacao(), construirSecaoListagem());

        // Monta conteúdo da Aba 2 (Logs)
        containerLogs.setSizeFull();
        containerLogs.setPadding(false);
        containerLogs.setSpacing(true);
        containerLogs.add(construirSecaoLogs());
        containerLogs.setVisible(false);

        add(barraNavegacao, containerSolicitacoes, containerLogs);
        atualizarTabela();
    }

    // Constrói o cabeçalho superior com título e menu de navegação por abas
    private HorizontalLayout construirMenuNavegacaoSuperior() {
        H2 titulo = new H2("Painel do Serviço de Ativação");
        titulo.getStyle().set("margin", "0");

        Tabs menuAbas = new Tabs(abaSolicitacoes, abaLogs);
        menuAbas.setSelectedTab(abaSolicitacoes);

        menuAbas.addSelectedChangeListener(event -> {
            boolean exibirSolicitacoes = event.getSelectedTab() == abaSolicitacoes;
            containerSolicitacoes.setVisible(exibirSolicitacoes);
            containerLogs.setVisible(!exibirSolicitacoes);

            if (exibirSolicitacoes) {
                atualizarTabela();
            } else {
                carregarEFiltrarLogs();
            }
        });

        HorizontalLayout cabecalho = new HorizontalLayout(titulo, menuAbas);
        cabecalho.setWidthFull();
        cabecalho.setJustifyContentMode(FlexComponent.JustifyContentMode.BETWEEN);
        cabecalho.setDefaultVerticalComponentAlignment(FlexComponent.Alignment.CENTER);
        cabecalho.getStyle()
                .set("padding-bottom", "8px")
                .set("border-bottom", "1px solid var(--lumo-contrast-20pct)");

        return cabecalho;
    }

    // Monta o formulário para criar solicitações e testar idempotência/cenários do provedor
    private VerticalLayout construirSecaoCriacao() {
        VerticalLayout container = new VerticalLayout();
        container.setPadding(true);
        container.setSpacing(true);
        container.getStyle()
                .set("border", "1px solid var(--lumo-contrast-20pct)")
                .set("border-radius", "8px");

        H3 subtitulo = new H3("1. Nova Solicitação de Ativação");

        campoExternalId.setPlaceholder("Ex.: EXT-1001");
        campoExternalId.setClearButtonVisible(true);
        campoExternalId.setWidth("220px");

        campoCustomerId.setPlaceholder("Ex.: CUST-10001");
        campoCustomerId.setClearButtonVisible(true);
        campoCustomerId.setWidth("220px");

        campoProductCode.setItems(
                "INTERNET-500",
                "TV-PLAN-500",
                "ERR-400-INVALID",
                "ERR-500-SERVER",
                "ERR-TIMEOUT"
        );
        campoProductCode.setAllowCustomValue(true);
        campoProductCode.addCustomValueSetListener(e -> campoProductCode.setValue(e.getDetail()));
        campoProductCode.setValue("INTERNET-500");
        campoProductCode.setHelperText("Escolha um código ou digite (ERR-400, ERR-500 e ERR-TIMEOUT simulam falhas no provedor)");
        campoProductCode.setWidth("340px");

        Button botaoCriar = new Button("Enviar Solicitação", e -> enviarSolicitacao());
        botaoCriar.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

        HorizontalLayout linhaFormulario = new HorizontalLayout(
                campoExternalId, campoCustomerId, campoProductCode, botaoCriar
        );
        linhaFormulario.setDefaultVerticalComponentAlignment(FlexComponent.Alignment.BASELINE);
        linhaFormulario.setWidthFull();

        container.add(subtitulo, linhaFormulario);
        return container;
    }

    // Monta a seção de consulta com filtros por status, customerId, UUID e tabela paginada
    private VerticalLayout construirSecaoListagem() {
        VerticalLayout container = new VerticalLayout();
        container.setSizeFull();
        container.setPadding(true);
        container.setSpacing(true);
        container.getStyle()
                .set("border", "1px solid var(--lumo-contrast-20pct)")
                .set("border-radius", "8px");

        H3 subtitulo = new H3("2. Consultar Solicitações Existentes");

        filtroStatus.setItems(ActivationStatus.values());
        filtroStatus.setPlaceholder("Todos");
        filtroStatus.setClearButtonVisible(true);
        filtroStatus.addValueChangeListener(e -> {
            paginaAtual = 0;
            atualizarTabela();
        });

        filtroCustomerId.setPlaceholder("Ex.: CUST-10001");
        filtroCustomerId.setClearButtonVisible(true);
        filtroCustomerId.addValueChangeListener(e -> {
            paginaAtual = 0;
            atualizarTabela();
        });

        filtroBuscaId.setPlaceholder("Cole um UUID específico...");
        filtroBuscaId.setClearButtonVisible(true);
        filtroBuscaId.setWidth("300px");

        Button botaoBuscarId = new Button("Buscar UUID", e -> buscarPorUuidEspecifico());
        Button botaoAtualizar = new Button("Atualizar Lista", e -> atualizarTabela());
        botaoAtualizar.addThemeVariants(ButtonVariant.LUMO_CONTRAST);

        HorizontalLayout barraFiltros = new HorizontalLayout(
                filtroStatus, filtroCustomerId, filtroBuscaId, botaoBuscarId, botaoAtualizar
        );
        barraFiltros.setDefaultVerticalComponentAlignment(FlexComponent.Alignment.BASELINE);

        configurarColunasTabela();

        botaoAnterior.addClickListener(e -> {
            if (paginaAtual > 0) {
                paginaAtual--;
                atualizarTabela();
            }
        });

        botaoProxima.addClickListener(e -> {
            if (paginaAtual + 1 < totalPaginas) {
                paginaAtual++;
                atualizarTabela();
            }
        });

        HorizontalLayout barraPaginacao = new HorizontalLayout(botaoAnterior, rotuloPaginacao, botaoProxima);
        barraPaginacao.setDefaultVerticalComponentAlignment(FlexComponent.Alignment.CENTER);

        container.add(subtitulo, barraFiltros, tabela, barraPaginacao);
        return container;
    }

    // Monta a tela de visualização estruturada e formatada dos logs de auditoria
    private VerticalLayout construirSecaoLogs() {
        VerticalLayout container = new VerticalLayout();
        container.setSizeFull();
        container.setPadding(true);
        container.setSpacing(true);
        container.getStyle()
                .set("border", "1px solid var(--lumo-contrast-20pct)")
                .set("border-radius", "8px");

        H3 subtitulo = new H3("Visualizador de Logs (logs/activation-audit.log)");

        filtroEscopoLog.setItems("Apenas Eventos [SERVICE]", "Todos os Logs da Aplicação");
        filtroEscopoLog.setValue("Apenas Eventos [SERVICE]");
        filtroEscopoLog.setWidth("260px");
        filtroEscopoLog.addValueChangeListener(e -> carregarEFiltrarLogs());

        filtroNivelLog.setItems("TODOS", "INFO", "WARN", "ERROR");
        filtroNivelLog.setValue("TODOS");
        filtroNivelLog.setWidth("140px");
        filtroNivelLog.addValueChangeListener(e -> carregarEFiltrarLogs());

        filtroTextoLog.setPlaceholder("Ex.: EXT-0002, FAILED, HTTP 400...");
        filtroTextoLog.setClearButtonVisible(true);
        filtroTextoLog.setWidth("300px");
        filtroTextoLog.addValueChangeListener(e -> carregarEFiltrarLogs());

        Button botaoAtualizarLogs = new Button("Atualizar Logs", e -> carregarEFiltrarLogs());
        botaoAtualizarLogs.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

        contadorTotalLogs.getElement().getThemeList().add("badge");
        contadorTotalLogs.getElement().getThemeList().add("contrast");

        contadorErrosLogs.getElement().getThemeList().add("badge");
        contadorErrosLogs.getElement().getThemeList().add("error");

        HorizontalLayout barraFiltrosLog = new HorizontalLayout(
                filtroEscopoLog, filtroNivelLog, filtroTextoLog, botaoAtualizarLogs, contadorTotalLogs, contadorErrosLogs
        );
        barraFiltrosLog.setDefaultVerticalComponentAlignment(FlexComponent.Alignment.BASELINE);

        configurarColunasTabelaLogs();

        // Console estilizado em modo escuro para inspeção detalhada dos logs filtrados
        consoleLogs.setWidthFull();
        consoleLogs.getStyle()
                .set("background-color", "#1e1e2e")
                .set("color", "#cdd6f4")
                .set("padding", "12px")
                .set("border-radius", "6px")
                .set("font-family", "monospace")
                .set("font-size", "12px")
                .set("max-height", "200px")
                .set("overflow", "auto")
                .set("margin", "0");

        Span tituloConsole = new Span("Saída Bruta Recente (Terminal):");
        tituloConsole.getStyle().set("font-weight", "600").set("font-size", "13px");

        container.add(subtitulo, barraFiltrosLog, tabelaLogs, tituloConsole, consoleLogs);
        return container;
    }

    private void configurarColunasTabela() {
        tabela.addColumn(ActivationRequestResponseDto::externalId).setHeader("External ID").setAutoWidth(true);
        tabela.addColumn(ActivationRequestResponseDto::customerId).setHeader("Customer ID").setAutoWidth(true);
        tabela.addColumn(ActivationRequestResponseDto::productCode).setHeader("Product Code").setAutoWidth(true);
        tabela.addColumn(new ComponentRenderer<>(this::criarBadgeStatus)).setHeader("Status").setAutoWidth(true);
        tabela.addColumn(dto -> dto.id().toString()).setHeader("ID (UUID)").setAutoWidth(true);
        tabela.addColumn(dto -> FORMATADOR_DATA.format(dto.createdAt())).setHeader("Criado em").setAutoWidth(true);
        tabela.addColumn(dto -> FORMATADOR_DATA.format(dto.updatedAt())).setHeader("Atualizado em").setAutoWidth(true);

        tabela.addThemeVariants(GridVariant.LUMO_ROW_STRIPES, GridVariant.LUMO_COMPACT);
        tabela.setHeight("360px");
    }

    private void configurarColunasTabelaLogs() {
        tabelaLogs.addColumn(RegistroLog::horario).setHeader("Data / Hora").setWidth("180px").setFlexGrow(0);
        tabelaLogs.addColumn(new ComponentRenderer<>(this::criarBadgeNivelLog)).setHeader("Nível").setWidth("110px").setFlexGrow(0);
        tabelaLogs.addColumn(new ComponentRenderer<>(this::criarBadgeComponenteLog)).setHeader("Componente").setWidth("230px").setFlexGrow(0);
        tabelaLogs.addColumn(new ComponentRenderer<>(this::formatarMensagemLog)).setHeader("Mensagem do Evento").setFlexGrow(1);

        tabelaLogs.addThemeVariants(GridVariant.LUMO_ROW_STRIPES, GridVariant.LUMO_COMPACT, GridVariant.LUMO_WRAP_CELL_CONTENT);
        tabelaLogs.setHeight("320px");
    }

    private Span criarBadgeStatus(ActivationRequestResponseDto dto) {
        Span badge = new Span(dto.status().name());
        badge.getElement().getThemeList().add("badge");

        switch (dto.status()) {
            case COMPLETED -> badge.getElement().getThemeList().add("success");
            case FAILED -> badge.getElement().getThemeList().add("error");
            case PROCESSING -> badge.getElement().getThemeList().add("primary");
            case PENDING -> badge.getElement().getThemeList().add("contrast");
        }
        return badge;
    }

    private Span criarBadgeNivelLog(RegistroLog item) {
        Span badge = new Span(item.nivel());
        badge.getElement().getThemeList().add("badge");

        switch (item.nivel()) {
            case "ERROR" -> badge.getElement().getThemeList().add("error");
            case "WARN" -> badge.getElement().getThemeList().add("warning");
            case "INFO" -> badge.getElement().getThemeList().add("success");
            default -> badge.getElement().getThemeList().add("contrast");
        }
        return badge;
    }

    private Span criarBadgeComponenteLog(RegistroLog item) {
        Span badge = new Span(item.componente());
        badge.getStyle()
                .set("font-family", "monospace")
                .set("font-size", "12px")
                .set("background-color", "var(--lumo-contrast-10pct)")
                .set("padding", "2px 6px")
                .set("border-radius", "4px");
        return badge;
    }

    private Div formatarMensagemLog(RegistroLog item) {
        Div container = new Div();
        container.getStyle().set("font-family", "monospace").set("font-size", "13px");

        String msg = item.mensagem();
        if (msg.startsWith("[SERVICE][ERRO]")) {
            Span tagErro = new Span("[SERVICE][ERRO]");
            tagErro.getElement().getThemeList().add("badge");
            tagErro.getElement().getThemeList().add("error");
            tagErro.getStyle().set("margin-right", "8px");

            Span texto = new Span(msg.substring("[SERVICE][ERRO]".length()).trim());
            container.add(tagErro, texto);
        } else if (msg.startsWith("[SERVICE]")) {
            Span tagService = new Span("[SERVICE]");
            tagService.getElement().getThemeList().add("badge");
            tagService.getElement().getThemeList().add("primary");
            tagService.getStyle().set("margin-right", "8px");

            Span texto = new Span(msg.substring("[SERVICE]".length()).trim());
            container.add(tagService, texto);
        } else {
            container.setText(msg);
        }
        return container;
    }

    // Lê o arquivo de log e aplica os filtros selecionados na interface
    private void carregarEFiltrarLogs() {
        if (!Files.exists(caminhoArquivoLog)) {
            tabelaLogs.setItems(List.of());
            consoleLogs.setText("Arquivo de log ainda não encontrado em: " + caminhoArquivoLog.toAbsolutePath());
            contadorTotalLogs.setText("0 eventos");
            contadorErrosLogs.setText("0 erros");
            return;
        }

        try {
            List<String> todasLinhas = Files.readAllLines(caminhoArquivoLog, StandardCharsets.UTF_8);
            boolean apenasService = "Apenas Eventos [SERVICE]".equals(filtroEscopoLog.getValue());
            String nivelFiltro = filtroNivelLog.getValue() != null ? filtroNivelLog.getValue() : "TODOS";
            String buscaTexto = filtroTextoLog.getValue() != null ? filtroTextoLog.getValue().trim().toLowerCase(Locale.ROOT) : "";

            List<RegistroLog> filtrados = new ArrayList<>();
            List<String> linhasBrutasFiltradas = new ArrayList<>();
            int qtdErros = 0;

            for (String linha : todasLinhas) {
                if (linha.isBlank()) {
                    continue;
                }

                Matcher matcher = PADRAO_LINHA_LOG.matcher(linha);
                if (!matcher.matches()) {
                    continue;
                }

                String timestamp = OffsetDateTime.parse(matcher.group(1)).format(FORMATADOR_DATA);
                String nivel = matcher.group(2).trim();
                String thread = matcher.group(3).trim();
                String loggerCompleto = matcher.group(4).trim();
                String mensagem = matcher.group(5).trim();

                if (apenasService && !mensagem.contains("[SERVICE]")) {
                    continue;
                }

                if (!"TODOS".equals(nivelFiltro) && !nivel.equalsIgnoreCase(nivelFiltro)) {
                    continue;
                }

                if (!buscaTexto.isEmpty() && !linha.toLowerCase(Locale.ROOT).contains(buscaTexto)) {
                    continue;
                }

                String nomeCurtoComponente = extrairNomeCurtoLogger(loggerCompleto);
                filtrados.add(new RegistroLog(timestamp, nivel, thread, nomeCurtoComponente, mensagem, linha));
                linhasBrutasFiltradas.add(linha);

                if ("ERROR".equalsIgnoreCase(nivel)) {
                    qtdErros++;
                }
            }

            // Exibe os eventos mais recentes no topo da tabela
            Collections.reverse(filtrados);
            Collections.reverse(linhasBrutasFiltradas);

            tabelaLogs.setItems(filtrados);
            contadorTotalLogs.setText(filtrados.size() + " eventos");
            contadorErrosLogs.setText(qtdErros + " erros");

            int limiteTerminal = Math.min(linhasBrutasFiltradas.size(), 50);
            consoleLogs.setText(
                    linhasBrutasFiltradas.isEmpty()
                            ? "Nenhum registro encontrado para os filtros selecionados."
                            : String.join("\n", linhasBrutasFiltradas.subList(0, limiteTerminal))
            );

        } catch (IOException ex) {
            exibirNotificacao("Erro ao ler arquivo de log: " + ex.getMessage(), NotificationVariant.LUMO_ERROR);
        }
    }

    private String extrairNomeCurtoLogger(String loggerCompleto) {
        int ultimoPonto = loggerCompleto.lastIndexOf('.');
        return ultimoPonto >= 0 ? loggerCompleto.substring(ultimoPonto + 1) : loggerCompleto;
    }

    private void enviarSolicitacao() {
        String extId = campoExternalId.getValue() != null ? campoExternalId.getValue().trim() : "";
        String custId = campoCustomerId.getValue() != null ? campoCustomerId.getValue().trim() : "";
        String prodCode = campoProductCode.getValue() != null ? campoProductCode.getValue().trim() : "";

        if (extId.isEmpty() || custId.isEmpty() || prodCode.isEmpty()) {
            exibirNotificacao("Preencha External ID, Customer ID e Product Code.", NotificationVariant.LUMO_ERROR);
            return;
        }

        try {
            CreateActivationRequestDto dto = new CreateActivationRequestDto(extId, custId, prodCode);
            CreationResult resultado = service.criarOuObterSolicitacao(dto);

            if (resultado.createdNow()) {
                exibirNotificacao(
                        "201 Created: Solicitação registrada com status PENDING (ID: " + resultado.request().getId() + ")",
                        NotificationVariant.LUMO_SUCCESS
                );
            } else {
                exibirNotificacao(
                        "200 OK (Idempotente): Solicitação já existia com os mesmos dados! Status atual: " + resultado.request().getStatus(),
                        NotificationVariant.LUMO_PRIMARY
                );
            }

            paginaAtual = 0;
            atualizarTabela();

        } catch (IdempotencyConflictException ex) {
            exibirNotificacao("409 Conflict: " + ex.getMessage(), NotificationVariant.LUMO_ERROR);
        } catch (Exception ex) {
            exibirNotificacao("Erro ao criar solicitação: " + ex.getMessage(), NotificationVariant.LUMO_ERROR);
        }
    }

    private void buscarPorUuidEspecifico() {
        String textoUuid = filtroBuscaId.getValue() != null ? filtroBuscaId.getValue().trim() : "";
        if (textoUuid.isEmpty()) {
            atualizarTabela();
            return;
        }

        try {
            UUID id = UUID.fromString(textoUuid);
            ActivationRequestResponseDto encontrado = service.buscarPorId(id);
            tabela.setItems(List.of(encontrado));
            rotuloPaginacao.setText("Exibindo busca direta por ID (1 registro)");
            botaoAnterior.setEnabled(false);
            botaoProxima.setEnabled(false);
        } catch (IllegalArgumentException ex) {
            exibirNotificacao("Formato de UUID inválido.", NotificationVariant.LUMO_ERROR);
        } catch (ResourceNotFoundException ex) {
            tabela.setItems(List.of());
            exibirNotificacao("404 Not Found: " + ex.getMessage(), NotificationVariant.LUMO_WARNING);
        }
    }

    private void atualizarTabela() {
        ActivationStatus statusSelecionado = filtroStatus.getValue();
        String customerFiltro = filtroCustomerId.getValue() != null && !filtroCustomerId.getValue().isBlank()
                ? filtroCustomerId.getValue().trim()
                : null;

        Pageable pageable = PageRequest.of(
                paginaAtual,
                TAMANHO_PAGINA,
                Sort.by(Sort.Direction.DESC, "createdAt")
        );

        Page<ActivationRequestResponseDto> pagina = service.listarPorFiltros(statusSelecionado, customerFiltro, pageable);

        tabela.setItems(pagina.getContent());
        totalPaginas = Math.max(pagina.getTotalPages(), 1);
        rotuloPaginacao.setText("Página " + (paginaAtual + 1) + " de " + totalPaginas
                + " (Total: " + pagina.getTotalElements() + ")");

        botaoAnterior.setEnabled(paginaAtual > 0);
        botaoProxima.setEnabled(paginaAtual + 1 < totalPaginas);
    }

    private void exibirNotificacao(String mensagem, NotificationVariant variante) {
        Notification notification = Notification.show(mensagem, 4500, Notification.Position.TOP_END);
        notification.addThemeVariants(variante);
    }

    private record RegistroLog(
            String horario,
            String nivel,
            String thread,
            String componente,
            String mensagem,
            String linhaBruta
    ) {
    }
}
