package br.gov.sp.cps.datagis.gold;

import org.springframework.http.ResponseEntity;
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

    /** Recalcula os 4 indicadores reais (ISAP/IAE/IDesmat/IFC) e publica todo imóvel + todas as camadas de mapa. */
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
}
