package br.gov.sp.cps.datagis.controle;

import br.gov.sp.cps.datagis.controle.monitoramento.MonitoramentoGlobalResponse;
import br.gov.sp.cps.datagis.controle.monitoramento.MonitoramentoGoldItem;
import br.gov.sp.cps.datagis.controle.monitoramento.MonitoramentoIngestaoItem;
import br.gov.sp.cps.datagis.controle.monitoramento.MonitoramentoSilverItem;
import br.gov.sp.cps.datagis.gold.GoldPublicacaoService;
import br.gov.sp.cps.datagis.ingestao.FaseExecucao;
import br.gov.sp.cps.datagis.ingestao.ProgressoExecucao;
import br.gov.sp.cps.datagis.ingestao.ProgressoExecucaoRegistry;
import br.gov.sp.cps.datagis.validacao.ProgressoTransicao;
import br.gov.sp.cps.datagis.validacao.ProgressoTransicaoRegistry;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Serviço agregador de monitoramento em tempo real para as esteiras
 * Bronze (ingestão), Silver (transição e quarentena) e Gold (publicação).
 */
@Service
@Transactional(readOnly = true)
public class MonitoramentoService {

    private final ProgressoExecucaoRegistry progressoExecucaoRegistry;
    private final ProgressoTransicaoRegistry progressoTransicaoRegistry;
    private final GoldPublicacaoService goldPublicacaoService;
    private final FonteDadoRepository fonteDadoRepository;
    private final LoteIngestaoRepository loteIngestaoRepository;

    @Autowired
    public MonitoramentoService(
            @Autowired(required = false) ProgressoExecucaoRegistry progressoExecucaoRegistry,
            @Autowired(required = false) ProgressoTransicaoRegistry progressoTransicaoRegistry,
            @Autowired(required = false) GoldPublicacaoService goldPublicacaoService,
            FonteDadoRepository fonteDadoRepository,
            LoteIngestaoRepository loteIngestaoRepository) {
        this.progressoExecucaoRegistry = progressoExecucaoRegistry;
        this.progressoTransicaoRegistry = progressoTransicaoRegistry;
        this.goldPublicacaoService = goldPublicacaoService;
        this.fonteDadoRepository = fonteDadoRepository;
        this.loteIngestaoRepository = loteIngestaoRepository;
    }

    public MonitoramentoGlobalResponse obterStatusGlobal() {
        List<MonitoramentoIngestaoItem> ingestoes = coletarIngestoes();
        List<MonitoramentoSilverItem> migracoesSilver = coletarMigracoesSilver();
        MonitoramentoGoldItem migracaoGold = coletarMigracaoGold();

        long ativasIngestao = ingestoes.stream().filter(MonitoramentoIngestaoItem::emExecucao).count();
        long ativasSilver = migracoesSilver.stream().filter(MonitoramentoSilverItem::emExecucao).count();
        int ativasGold = (migracaoGold != null && migracaoGold.emExecucao()) ? 1 : 0;

        int totalEmAndamento = (int) (ativasIngestao + ativasSilver + ativasGold);

        return new MonitoramentoGlobalResponse(totalEmAndamento, ingestoes, migracoesSilver, migracaoGold);
    }

    private List<MonitoramentoIngestaoItem> coletarIngestoes() {
        if (progressoExecucaoRegistry == null) {
            return List.of();
        }
        Map<Long, ProgressoExecucao> estados = progressoExecucaoRegistry.obterTodos();
        List<MonitoramentoIngestaoItem> lista = new ArrayList<>();

        for (Map.Entry<Long, ProgressoExecucao> entry : estados.entrySet()) {
            Long fonteId = entry.getKey();
            ProgressoExecucao prog = entry.getValue();
            if (prog == null) continue;

            boolean emExecucao = prog.fase() != FaseExecucao.CONCLUIDO && prog.fase() != FaseExecucao.ERRO;
            // Apenas o que está em processamento ativo
            if (!emExecucao) continue;

            String fonteNome = fonteDadoRepository != null
                    ? fonteDadoRepository.findById(fonteId)
                            .map(FonteDado::getNome)
                            .orElse("Fonte #" + fonteId)
                    : "Fonte #" + fonteId;

            int percentual = calcularPercentualIngestao(prog);

            lista.add(new MonitoramentoIngestaoItem(
                    fonteId,
                    fonteNome,
                    prog.fase().name(),
                    prog.registrosProcessados(),
                    prog.totalEstimado(),
                    percentual,
                    prog.detalhe(),
                    true
            ));
        }

        lista.sort(Comparator.comparing(MonitoramentoIngestaoItem::fonteId, Comparator.reverseOrder()));
        return lista;
    }

    private int calcularPercentualIngestao(ProgressoExecucao prog) {
        if (prog.fase() == FaseExecucao.CONCLUIDO) return 100;
        if (prog.fase() == FaseExecucao.ERRO) return 0;
        if (prog.totalEstimado() != null && prog.totalEstimado() > 0) {
            return (int) Math.min(100, Math.round(((double) prog.registrosProcessados() / prog.totalEstimado()) * 100));
        }
        return prog.registrosProcessados() > 0 ? 50 : 10;
    }

    private List<MonitoramentoSilverItem> coletarMigracoesSilver() {
        if (progressoTransicaoRegistry == null) {
            return List.of();
        }
        Map<Long, ProgressoTransicao> estados = progressoTransicaoRegistry.obterTodos();
        List<MonitoramentoSilverItem> lista = new ArrayList<>();

        for (Map.Entry<Long, ProgressoTransicao> entry : estados.entrySet()) {
            Long loteId = entry.getKey();
            ProgressoTransicao prog = entry.getValue();
            if (prog == null) continue;

            boolean emExecucao = "EM_PROCESSAMENTO".equals(prog.statusFinal())
                    && !"CONCLUIDO".equals(prog.fase())
                    && !"ERRO".equals(prog.fase())
                    && !"RETIDO_QUARENTENA".equals(prog.fase());

            // Apenas o que está em processamento ativo
            if (!emExecucao) continue;

            String fonteNome = loteIngestaoRepository != null
                    ? loteIngestaoRepository.findByIdComFonte(loteId)
                            .map(l -> l.getFonteDado() != null ? l.getFonteDado().getNome() : "Lote #" + loteId)
                            .orElse("Lote #" + loteId)
                    : "Lote #" + loteId;

            lista.add(new MonitoramentoSilverItem(
                    loteId,
                    fonteNome,
                    prog.fase(),
                    prog.registrosProcessados(),
                    prog.totalRegistros(),
                    prog.percentual(),
                    prog.errosEncontrados(),
                    prog.detalhe(),
                    prog.statusFinal(),
                    true
            ));
        }

        lista.sort(Comparator.comparing(MonitoramentoSilverItem::loteId, Comparator.reverseOrder()));
        return lista;
    }

    private MonitoramentoGoldItem coletarMigracaoGold() {
        if (goldPublicacaoService == null) {
            return null;
        }
        try {
            GoldPublicacaoService.StatusGoldConsolidado status = goldPublicacaoService.obterStatusConsolidado();
            if (status == null || !status.algumEmAndamento()) {
                // Apenas exibe se a publicacao Gold estiver em execucao ativa
                return null;
            }

            boolean emExecucao = true;
            String statusIndicadores = status.indicadores() != null ? status.indicadores().status() : "EM_ANDAMENTO";
            Long processados = status.indicadores() != null ? status.indicadores().totalProcessado() : 0L;
            Long total = status.indicadores() != null ? status.indicadores().totalEstimado() : 0L;

            List<MonitoramentoGoldItem.CamadaMapaStatusItem> camadas = new ArrayList<>();
            if (status.camadasMapa() != null) {
                for (GoldPublicacaoService.StatusPublicacaoCamada c : status.camadasMapa()) {
                    camadas.add(new MonitoramentoGoldItem.CamadaMapaStatusItem(
                            c.codigoCamada(),
                            c.status(),
                            c.totalProcessado(),
                            c.totalEstimado()
                    ));
                }
            }

            int percentualGeral = calcularPercentualGold(statusIndicadores, processados, total, camadas);

            return new MonitoramentoGoldItem(
                    emExecucao,
                    percentualGeral,
                    statusIndicadores,
                    processados,
                    total,
                    camadas
            );
        } catch (Exception e) {
            return null;
        }
    }

    private int calcularPercentualGold(String statusIndicadores, Long processados, Long total, List<MonitoramentoGoldItem.CamadaMapaStatusItem> camadas) {
        if ("CONCLUIDA".equalsIgnoreCase(statusIndicadores) && (camadas.isEmpty() || camadas.stream().allMatch(c -> "CONCLUIDA".equalsIgnoreCase(c.status())))) {
            return 100;
        }
        int somaPesos = 0;
        int totalPesos = 0;

        if (total != null && total > 0) {
            somaPesos += Math.min(100, Math.round(((double) processados / total) * 100));
            totalPesos += 1;
        }

        for (MonitoramentoGoldItem.CamadaMapaStatusItem camada : camadas) {
            totalPesos += 1;
            if ("CONCLUIDA".equalsIgnoreCase(camada.status())) {
                somaPesos += 100;
            } else if (camada.total() != null && camada.total() > 0) {
                somaPesos += Math.min(100, Math.round(((double) camada.processado() / camada.total()) * 100));
            } else if ("EM_ANDAMENTO".equalsIgnoreCase(camada.status())) {
                somaPesos += 20;
            }
        }

        return totalPesos > 0 ? Math.round((float) somaPesos / totalPesos) : 0;
    }
}
