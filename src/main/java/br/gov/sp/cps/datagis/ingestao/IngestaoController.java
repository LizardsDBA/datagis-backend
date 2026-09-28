package br.gov.sp.cps.datagis.ingestao;

import br.gov.sp.cps.datagis.controle.LoteIngestao;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Dispara a ingestão de qualquer fonte cadastrada, pelo id. Não sabe qual fonte é CAR,
 * IBGE, embargos etc. — isso é responsabilidade de cada {@link IngestaoExecutor} (ver
 * {@link IngestaoExecucaoService}).
 */
@RestController
@RequestMapping("/api/fontes")
public class IngestaoController {

    private final IngestaoExecucaoService execucaoService;
    private final ProgressoExecucaoRegistry progressoRegistry;

    public IngestaoController(IngestaoExecucaoService execucaoService, ProgressoExecucaoRegistry progressoRegistry) {
        this.execucaoService = execucaoService;
        this.progressoRegistry = progressoRegistry;
    }

    @PostMapping("/{id}/executar")
    public IngestaoResultado executar(@PathVariable Long id) {
        LoteIngestao lote = execucaoService.executar(id);
        return new IngestaoResultado(lote.getId(), lote.getQuantidadeRegistros(), lote.getStatus());
    }

    /**
     * Progresso da execução em andamento (ou da última concluída) pra fonte, pra tela
     * poder sondar (polling) enquanto {@code /executar}/{@code /upload} ainda estão
     * pendentes — os dois rodam de forma síncrona (a resposta HTTP só volta no fim de
     * tudo), então este é o único jeito de saber "em que fase está" antes disso.
     */
    @GetMapping("/{id}/progresso")
    public ProgressoExecucaoResponse progresso(@PathVariable Long id) {
        return progressoRegistry.obter(id)
                .map(ProgressoExecucaoResponse::de)
                .orElseGet(ProgressoExecucaoResponse::ocioso);
    }

    /**
     * Só faz sentido pra fonte {@code UPLOAD_MANUAL} (ex.: Shapefile do CAR baixado
     * manualmente pelo usuário) — a validação de tipo/formato mora no executor
     * correspondente, não aqui; esse endpoint só sabe receber o arquivo e disparar a
     * ingestão genérica de sempre.
     */
    @PostMapping(value = "/{id}/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public IngestaoResultado upload(@PathVariable Long id, @RequestParam("arquivo") MultipartFile arquivo) {
        try {
            LoteIngestao lote = execucaoService.executarComUpload(id, arquivo.getOriginalFilename(), arquivo.getInputStream());
            return new IngestaoResultado(lote.getId(), lote.getQuantidadeRegistros(), lote.getStatus());
        } catch (IOException e) {
            throw new IllegalStateException("Falha ao ler o arquivo enviado", e);
        }
    }
}
