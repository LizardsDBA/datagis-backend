package br.gov.sp.cps.datagis.auditoria;

import br.gov.sp.cps.datagis.auditoria.LogAuditoriaRepositorio.FiltroConsultaAuditoria;
import br.gov.sp.cps.datagis.auditoria.LogAuditoriaRepositorio.PaginaAuditoria;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Consulta do log de auditoria (LIZARDS-48) - so leitura, exige {@code auditoria:visualizar}
 * ({@code SecurityConfig}). Nao existe endpoint de escrita: quem grava e o {@link FiltroAuditoria}.
 */
@RestController
@RequestMapping("/api/auditoria")
public class AuditoriaController {

    private static final int TAMANHO_MAXIMO = 200;

    private final LogAuditoriaRepositorio repositorio;

    public AuditoriaController(LogAuditoriaRepositorio repositorio) {
        this.repositorio = repositorio;
    }

    @GetMapping
    public PaginaAuditoria buscar(
            @RequestParam(required = false) String usuario,
            @RequestParam(required = false) String metodo,
            @RequestParam(required = false) Boolean sucesso,
            @RequestParam(required = false) String texto,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate de,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate ate,
            @RequestParam(defaultValue = "0") int pagina,
            @RequestParam(defaultValue = "50") int tamanho) {
        if (pagina < 0 || tamanho < 1) {
            throw new IllegalArgumentException("pagina deve ser >= 0 e tamanho >= 1");
        }
        return repositorio.buscar(new FiltroConsultaAuditoria(usuario, metodo, sucesso, texto, de, ate),
                pagina, Math.min(tamanho, TAMANHO_MAXIMO));
    }
}
