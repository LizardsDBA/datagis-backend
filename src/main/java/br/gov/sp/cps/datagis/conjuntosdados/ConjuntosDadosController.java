package br.gov.sp.cps.datagis.conjuntosdados;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tela de Conjunto de Dados: compara, por lote, o mesmo registro nas camadas Bronze e
 * Silver. A aba de Quarentena não tem endpoint aqui de propósito — a tela consome
 * {@code GET /api/quarentena?loteIngestaoId=...} direto, que já cobre o caso.
 */
@RestController
@RequestMapping("/api/conjuntos-dados")
public class ConjuntosDadosController {

    private final ConjuntosDadosService service;
    private final ComparacaoCamadasService comparacaoService;
    private final ComparacaoLotesService comparacaoLotesService;

    public ConjuntosDadosController(
            ConjuntosDadosService service,
            ComparacaoCamadasService comparacaoService,
            ComparacaoLotesService comparacaoLotesService) {
        this.service = service;
        this.comparacaoService = comparacaoService;
        this.comparacaoLotesService = comparacaoLotesService;
    }

    @GetMapping("/fontes/{fonteDadoId}/ultimo-lote")
    public ResponseEntity<LoteResumoDto> buscarUltimoLote(@PathVariable Long fonteDadoId) {
        return ResponseEntity.ok(service.buscarUltimoLotePromovido(fonteDadoId));
    }

    @GetMapping("/lotes/{loteId}/bronze")
    public ResponseEntity<PaginaDto<RegistroCamadaDto>> listarBronze(
            @PathVariable Long loteId,
            @RequestParam(defaultValue = "0") int pagina,
            @RequestParam(defaultValue = "25") int tamanho) {
        return ResponseEntity.ok(service.listarBronze(loteId, pagina, tamanho));
    }

    @GetMapping("/lotes/{loteId}/silver")
    public ResponseEntity<PaginaDto<RegistroCamadaDto>> listarSilver(
            @PathVariable Long loteId,
            @RequestParam(defaultValue = "0") int pagina,
            @RequestParam(defaultValue = "25") int tamanho) {
        return ResponseEntity.ok(service.listarSilver(loteId, pagina, tamanho));
    }

    @GetMapping("/registros/{registroBrutoId}/bronze")
    public ResponseEntity<RegistroCamadaDto> buscarRegistroBronze(@PathVariable Long registroBrutoId) {
        return ResponseEntity.ok(service.buscarRegistroBronze(registroBrutoId));
    }

    @GetMapping("/registros/{registroBrutoId}/silver")
    public ResponseEntity<RegistroCamadaDto> buscarRegistroSilver(@PathVariable Long registroBrutoId) {
        RegistroCamadaDto registro = service.buscarRegistroSilver(registroBrutoId);
        return registro != null ? ResponseEntity.ok(registro) : ResponseEntity.noContent().build();
    }

    /** Diff do registro entre camadas vizinhas: {@code origem=BRONZE&destino=SILVER} ou {@code origem=SILVER&destino=GOLD}. */
    @GetMapping("/registros/{registroBrutoId}/comparacao")
    public ResponseEntity<ComparacaoCamadasDto> compararCamadas(
            @PathVariable Long registroBrutoId,
            @RequestParam String origem,
            @RequestParam String destino) {
        return ResponseEntity.ok(comparacaoService.comparar(registroBrutoId, origem, destino));
    }

    /**
     * Diff entre os dois últimos lotes promovidos de uma fonte (atual vs anterior),
     * similar ao "git diff" entre dois commits. Suporta paginação e filtro por situação
     * (ADICIONADO, REMOVIDO, MODIFICADO, INALTERADO).
     */
    @GetMapping("/fontes/{fonteDadoId}/comparacao-lotes")
    public ResponseEntity<ComparacaoLotesDto> compararLotes(
            @PathVariable Long fonteDadoId,
            @RequestParam(defaultValue = "0") int pagina,
            @RequestParam(defaultValue = "25") int tamanho,
            @RequestParam(required = false) String filtroSituacao) {
        return ResponseEntity.ok(
                comparacaoLotesService.compararUltimosLotes(fonteDadoId, pagina, tamanho, filtroSituacao));
    }
}

