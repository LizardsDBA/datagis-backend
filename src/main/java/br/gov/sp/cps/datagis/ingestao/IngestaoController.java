package br.gov.sp.cps.datagis.ingestao;

import br.gov.sp.cps.datagis.controle.LoteIngestao;
import java.io.IOException;
import org.springframework.http.MediaType;
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

    public IngestaoController(IngestaoExecucaoService execucaoService) {
        this.execucaoService = execucaoService;
    }

    @PostMapping("/{id}/executar")
    public IngestaoResultado executar(@PathVariable Long id) {
        LoteIngestao lote = execucaoService.executar(id);
        return new IngestaoResultado(lote.getId(), lote.getQuantidadeRegistros(), lote.getStatus());
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
