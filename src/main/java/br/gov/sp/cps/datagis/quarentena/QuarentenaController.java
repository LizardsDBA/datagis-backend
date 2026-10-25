package br.gov.sp.cps.datagis.quarentena;

import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import java.util.List;
import java.util.Map;
import br.gov.sp.cps.datagis.config.OperacaoEmAndamentoException;
import br.gov.sp.cps.datagis.validacao.ProgressoTransicaoRegistry;
import br.gov.sp.cps.datagis.validacao.ProgressoTransicaoResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/quarentena")
public class QuarentenaController {

    private final QuarentenaService quarentenaService;
    private final ProgressoTransicaoRegistry progressoTransicaoRegistry;

    @Autowired
    public QuarentenaController(
            QuarentenaService quarentenaService,
            @Autowired(required = false) ProgressoTransicaoRegistry progressoTransicaoRegistry) {
        this.quarentenaService = quarentenaService;
        this.progressoTransicaoRegistry = progressoTransicaoRegistry;
    }

    public QuarentenaController(QuarentenaService quarentenaService) {
        this(quarentenaService, null);
    }

    @GetMapping
    public ResponseEntity<List<ItemQuarentenaDto>> listar(
            @RequestParam(required = false) String entidade,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String tipoErro,
            @RequestParam(required = false) Long fonteDadoId,
            @RequestParam(required = false) Long loteIngestaoId,
            @RequestParam(defaultValue = "0") int pagina,
            @RequestParam(defaultValue = "50") int tamanho) {

        int offset = Math.max(0, pagina) * Math.max(1, tamanho);
        List<ItemQuarentenaDto> itens = quarentenaService.listarItens(entidade, status, tipoErro, fonteDadoId, loteIngestaoId, offset, tamanho);
        long total = quarentenaService.contarItens(entidade, status, tipoErro, fonteDadoId, loteIngestaoId);

        return ResponseEntity.ok()
                .header("X-Total-Count", String.valueOf(total))
                .header("Access-Control-Expose-Headers", "X-Total-Count")
                .body(itens);
    }

    @GetMapping("/metricas")
    public ResponseEntity<MetricasQuarentenaDto> obterMetricas() {
        return ResponseEntity.ok(quarentenaService.obterMetricas());
    }

    @GetMapping("/tipos-erro")
    public ResponseEntity<List<TipoErroDto>> listarTiposErro() {
        return ResponseEntity.ok(quarentenaService.listarTiposErro());
    }

    @GetMapping("/status-analise")
    public ResponseEntity<List<StatusAnaliseDto>> listarStatusAnalise() {
        return ResponseEntity.ok(quarentenaService.listarStatusAnalise());
    }

    @GetMapping("/{entidade}/{id}")
    public ResponseEntity<ItemQuarentenaDto> buscarPorId(
            @PathVariable String entidade,
            @PathVariable Long id) {
        ItemQuarentenaDto item = quarentenaService.buscarPorId(entidade, id);
        if (item == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(item);
    }

    @GetMapping("/diagnostico")
    public ResponseEntity<List<DiagnosticoErroDto>> obterDiagnostico(
            @RequestParam(required = false) String dataset,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataInicio,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataFim) {

        List<DiagnosticoErroDto> diagnostico = quarentenaService.obterDiagnosticoPorErro(dataset, dataInicio, dataFim);
        return ResponseEntity.ok(diagnostico);
    }

    @PostMapping("/{entidade}/{id}/descartar")
    public ResponseEntity<Void> descartar(
            @PathVariable String entidade,
            @PathVariable Long id,
            @RequestBody(required = false) AcaoQuarentenaRequest request) {
        boolean sucesso = quarentenaService.descartar(entidade, id, request);
        return sucesso ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    @PostMapping("/{entidade}/{id}/iniciar-analise")
    public ResponseEntity<Void> iniciarAnalise(
            @PathVariable String entidade,
            @PathVariable Long id,
            @RequestBody(required = false) AcaoQuarentenaRequest request) {
        Long analistaId = request != null ? request.analistaId() : null;
        boolean sucesso = quarentenaService.iniciarAnalise(entidade, id, analistaId);
        return sucesso ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    @PostMapping("/{entidade}/{id}/reprocessar")
    public ResponseEntity<?> reprocessar(
            @PathVariable String entidade,
            @PathVariable Long id,
            @RequestBody(required = false) AcaoQuarentenaRequest request) {
        try {
            ResultadoRevalidacaoQuarentena resultado = quarentenaService.marcarCorrigido(entidade, id, request);
            if (resultado == null || !resultado.sucesso()) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(resultado);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("erro", e.getMessage()));
        }
    }

    @PostMapping("/lotes/{loteId}/liberar")
    public ResponseEntity<ProgressoTransicaoResponse> liberarLote(
            @PathVariable Long loteId,
            @RequestBody(required = false) AcaoQuarentenaRequest request) {
        if (progressoTransicaoRegistry != null && progressoTransicaoRegistry.isExecutando(loteId)) {
            throw new OperacaoEmAndamentoException("O lote #" + loteId + " já está sendo liberado.");
        }
        if (progressoTransicaoRegistry != null) {
            progressoTransicaoRegistry.iniciar(loteId, 0, "INICIANDO");
        }
        Long analistaId = request != null ? request.analistaId() : null;
        quarentenaService.liberarLoteAssincrono(loteId, analistaId);
        ProgressoTransicaoResponse resp = progressoTransicaoRegistry != null
                ? progressoTransicaoRegistry.obter(loteId).map(ProgressoTransicaoResponse::de).orElseGet(() -> ProgressoTransicaoResponse.ocioso(loteId))
                : ProgressoTransicaoResponse.ocioso(loteId);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(resp);
    }

    @PostMapping("/lotes/{loteId}/descartar")
    public ResponseEntity<Void> descartarLote(
            @PathVariable Long loteId,
            @RequestBody(required = false) AcaoQuarentenaRequest request) {
        Long analistaId = request != null ? request.analistaId() : null;
        String motivo = request != null ? request.motivo() : null;
        boolean sucesso = quarentenaService.descartarLote(loteId, motivo, analistaId);
        return sucesso ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    @PostMapping("/dominios")
    public ResponseEntity<java.util.Map<String, Object>> adicionarValorDominio(
            @RequestBody br.gov.sp.cps.datagis.validacao.NovoDominioRequest request) {
        try {
            java.util.Map<String, Object> resultado = quarentenaService.adicionarValorDominio(request);
            return ResponseEntity.ok(resultado);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(java.util.Map.of("erro", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(java.util.Map.of("erro", "Erro interno ao cadastrar domínio: " + e.getMessage()));
        }
    }

    @GetMapping("/dominios/tipos-area-protegida")
    public ResponseEntity<List<java.util.Map<String, Object>>> listarTiposAreaProtegida() {
        return ResponseEntity.ok(quarentenaService.listarTiposAreaProtegida());
    }
}

