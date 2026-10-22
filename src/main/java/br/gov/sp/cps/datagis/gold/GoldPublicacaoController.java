package br.gov.sp.cps.datagis.gold;

import java.util.List;
import br.gov.sp.cps.datagis.config.OperacaoEmAndamentoException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Disparo manual da transição Silver→Gold (sem Quartz/cron ainda — mesmo espírito da
 * fatia 1 de ingestão, que nasceu sem agendamento; ver
 * {@code docs/gold-procedures-calculo-publicacao.md} §6).
 */
@RestController
@RequestMapping("/api/gold")
public class GoldPublicacaoController {

    private final GoldPublicacaoService service;

    public GoldPublicacaoController(GoldPublicacaoService service) {
        this.service = service;
    }

    /**
     * Recalcula os 7 indicadores (ISAP/IAE/IRL/IAPP/IDesmat/IFC/ICV) e publica todo imóvel
     * + todas as camadas de mapa de forma assíncrona em segundo plano.
     */
    @PostMapping("/publicar")
    public ResponseEntity<GoldPublicacaoService.StatusGoldConsolidado> publicar() {
        if (service.obterStatusConsolidado().algumEmAndamento()) {
            throw new OperacaoEmAndamentoException("Já existe uma publicação para a camada Gold em andamento.");
        }
        service.publicarTudoAssincrono();
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(service.obterStatusConsolidado());
    }

    /** Recalcula e publica só 1 imóvel. */
    @PostMapping("/imoveis/{codImovel}/recalcular")
    public ResponseEntity<Void> recalcular(@PathVariable String codImovel) {
        service.recalcularImovel(codImovel);
        return ResponseEntity.noContent().build();
    }

    /** Dispara a publicação em lotes de 1 camada de mapa (ver GoldPublicacaoService). */
    @PostMapping("/mapa-camadas/{codigo}/publicar")
    public ResponseEntity<Void> publicarCamadaMapa(@PathVariable String codigo) {
        service.publicarCamadaMapaEmLotes(codigo);
        return ResponseEntity.noContent().build();
    }

    /** Status/andamento da última execução de publicação de 1 camada de mapa. */
    @GetMapping("/mapa-camadas/{codigo}/status")
    public ResponseEntity<GoldPublicacaoService.StatusPublicacaoCamada> statusPublicacaoCamada(
            @PathVariable String codigo) {
        return ResponseEntity.ok(service.obterStatusPublicacao(codigo));
    }

    /** Registros pulados/com erro numa execução de publicação específica (sempre com motivo). */
    @GetMapping("/publicacoes/{publicacaoId}/erros")
    public ResponseEntity<List<GoldPublicacaoService.ErroPublicacao>> errosPublicacao(
            @PathVariable long publicacaoId) {
        return ResponseEntity.ok(service.listarErros(publicacaoId));
    }

    /** Status/andamento da última execução de recálculo de indicadores (todos os imóveis). */
    @GetMapping("/publicacoes-indicadores/status")
    public ResponseEntity<GoldPublicacaoService.StatusPublicacaoIndicadores> statusPublicacaoIndicadores() {
        return ResponseEntity.ok(service.obterStatusPublicacaoIndicadores());
    }

    /** Imóveis que falharam numa execução de recálculo de indicadores (sempre com motivo). */
    @GetMapping("/publicacoes-indicadores/{publicacaoIndicadoresId}/erros")
    public ResponseEntity<List<GoldPublicacaoService.ErroPublicacao>> errosPublicacaoIndicadores(
            @PathVariable long publicacaoIndicadoresId) {
        return ResponseEntity.ok(service.listarErrosIndicadores(publicacaoIndicadoresId));
    }

    /**
     * Status de tudo que é publicação Gold (recálculo de indicadores + as 6 camadas
     * de mapa) numa consulta só - pensado pra uma futura tela de acompanhamento de
     * processos (CLAUDE.md §9.38).
     */
    @GetMapping("/status")
    public ResponseEntity<GoldPublicacaoService.StatusGoldConsolidado> statusConsolidado() {
        return ResponseEntity.ok(service.obterStatusConsolidado());
    }
}
