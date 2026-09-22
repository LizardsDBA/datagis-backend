package br.gov.sp.cps.datagis.quarentena;

import java.util.List;
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

    public QuarentenaController(QuarentenaService quarentenaService) {
        this.quarentenaService = quarentenaService;
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
    public ResponseEntity<Void> reprocessar(
            @PathVariable String entidade,
            @PathVariable Long id,
            @RequestBody(required = false) AcaoQuarentenaRequest request) {
        boolean sucesso = quarentenaService.marcarCorrigido(entidade, id, request);
        return sucesso ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    @PostMapping("/lotes/{loteId}/liberar")
    public ResponseEntity<Void> liberarLote(
            @PathVariable Long loteId,
            @RequestBody(required = false) AcaoQuarentenaRequest request) {
        Long analistaId = request != null ? request.analistaId() : null;
        boolean sucesso = quarentenaService.liberarLote(loteId, analistaId);
        return sucesso ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
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
}
