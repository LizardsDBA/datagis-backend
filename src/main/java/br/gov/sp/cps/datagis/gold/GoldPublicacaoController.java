package br.gov.sp.cps.datagis.gold;

import java.util.List;
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

    /** Recalcula os 7 indicadores (ISAP/IAE/IRL/IAPP/IDesmat/IFC/ICV) e publica todo imóvel + todas as camadas de mapa. */
    @PostMapping("/publicar")
    public ResponseEntity<GoldPublicacaoService.ResultadoPublicacaoTudo> publicarTudo() {
        return ResponseEntity.ok(service.publicarTudo());
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
}
