package br.gov.sp.cps.datagis.ingestao;

import br.gov.sp.cps.datagis.config.OperacaoEmAndamentoException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Dispara a ingestão de qualquer fonte cadastrada, pelo id.
 * Os disparos manuais e uploads agora são processados de forma assíncrona
 * em segundo plano, retornando 202 Accepted imediatamente e bloqueando
 * requisições concorrentes com 409 Conflict.
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
    public ResponseEntity<ProgressoExecucaoResponse> executar(@PathVariable Long id) {
        if (progressoRegistry.isExecutando(id)) {
            throw new OperacaoEmAndamentoException("A fonte " + id + " já está com uma execução em andamento.");
        }
        progressoRegistry.iniciar(id);
        execucaoService.executarAssincrono(id);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(progressoRegistry.obter(id)
                        .map(ProgressoExecucaoResponse::de)
                        .orElseGet(ProgressoExecucaoResponse::ocioso));
    }

    /**
     * Progresso da execução em andamento (ou da última concluída) pra fonte, pra tela
     * poder sondar (polling) enquanto o processamento em segundo plano avança.
     */
    @GetMapping("/{id}/progresso")
    public ProgressoExecucaoResponse progresso(@PathVariable Long id) {
        return progressoRegistry.obter(id)
                .map(ProgressoExecucaoResponse::de)
                .orElseGet(ProgressoExecucaoResponse::ocioso);
    }

    /**
     * Só faz sentido pra fonte {@code UPLOAD_MANUAL} (ex.: Shapefile do CAR baixado
     * manualmente pelo usuário) — salva o arquivo em disco temporário para liberar
     * a conexão HTTP imediatamente e despacha em segundo plano.
     */
    @PostMapping(value = "/{id}/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ProgressoExecucaoResponse> upload(@PathVariable Long id, @RequestParam("arquivo") MultipartFile arquivo) {
        if (progressoRegistry.isExecutando(id)) {
            throw new OperacaoEmAndamentoException("A fonte " + id + " já está com uma execução em andamento.");
        }
        try {
            Path arquivoTemporario = Files.createTempFile("datagis-upload-" + id + "-", ".tmp");
            arquivo.transferTo(arquivoTemporario);
            progressoRegistry.iniciar(id);
            execucaoService.executarComUploadAssincrono(id, arquivo.getOriginalFilename(), arquivoTemporario);
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(progressoRegistry.obter(id)
                            .map(ProgressoExecucaoResponse::de)
                            .orElseGet(ProgressoExecucaoResponse::ocioso));
        } catch (IOException e) {
            throw new IllegalStateException("Falha ao salvar o arquivo enviado", e);
        }
    }
}
