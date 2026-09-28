package br.gov.sp.cps.datagis.controle;

import br.gov.sp.cps.datagis.ingestao.AgendamentoFonteService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import org.quartz.CronExpression;
import org.quartz.SchedulerException;
import org.springframework.stereotype.Service;

/** Cadastra, edita e lista as fontes de dados. */
@Service
public class FonteDadoService {

    private static final Set<String> TIPOS_ACEITOS = Set.of("UPLOAD_MANUAL", "API_REST", "DOWNLOAD_URL");

    /** Separadores de campo de CSV suportados pelo IngestaoCsvExecutor. */
    private static final Set<String> SEPARADORES_CSV_ACEITOS = Set.of(",", ";", "|", "\t");
    private static final String SEPARADOR_CSV_PADRAO = ",";

    private final FonteDadoRepository repository;
    private final AgendamentoFonteService agendamentoFonteService;

    public FonteDadoService(FonteDadoRepository repository, AgendamentoFonteService agendamentoFonteService) {
        this.repository = repository;
        this.agendamentoFonteService = agendamentoFonteService;
    }

    public FonteDado cadastrar(FonteDadoRequest dados) {
        validarTipoAquisicao(dados.tipoAquisicao());
        validarCronExpressao(dados.cronExpressao());

        FonteDado fonte = FonteDado.builder()
                .nome(dados.nome())
                .orgao(dados.orgao())
                .tipoAquisicao(dados.tipoAquisicao())
                .urlOrigem(dados.urlOrigem())
                .cronExpressao(dados.cronExpressao())
                .formatoEsperado(dados.formatoEsperado())
                .campoGeometriaLat(dados.campoGeometriaLat())
                .campoGeometriaLon(dados.campoGeometriaLon())
                .separadorCsv(normalizarSeparadorCsv(dados.separadorCsv()))
                .ativo(dados.ativo() != null ? dados.ativo() : true)
                .dataAdd(LocalDateTime.now())
                .build();

        FonteDado salva = repository.save(fonte);
        sincronizarAgendamento(salva);

        return salva;
    }

    public FonteDado buscarPorId(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Fonte de dado " + id + " não encontrada"));
    }

    public FonteDado atualizar(Long id, FonteDadoRequest dados) {
        validarTipoAquisicao(dados.tipoAquisicao());
        validarCronExpressao(dados.cronExpressao());

        FonteDado fonte = buscarPorId(id);
        fonte.setNome(dados.nome());
        fonte.setOrgao(dados.orgao());
        fonte.setTipoAquisicao(dados.tipoAquisicao());
        fonte.setUrlOrigem(dados.urlOrigem());
        fonte.setCronExpressao(dados.cronExpressao());
        fonte.setFormatoEsperado(dados.formatoEsperado());
        fonte.setCampoGeometriaLat(dados.campoGeometriaLat());
        fonte.setCampoGeometriaLon(dados.campoGeometriaLon());
        fonte.setSeparadorCsv(normalizarSeparadorCsv(dados.separadorCsv()));
        fonte.setAtivo(dados.ativo() != null ? dados.ativo() : fonte.getAtivo());

        FonteDado salva = repository.save(fonte);
        sincronizarAgendamento(salva);

        return salva;
    }

    public List<FonteDado> listar() {
        return repository.findAll();
    }

    /**
     * Poe o Quartz em dia com o que acabou de ser gravado, para o agendamento
     * valer na hora - antes disso, so na proxima subida do backend (LIZARDS-10).
     */
    private void sincronizarAgendamento(FonteDado fonte) {
        try {
            agendamentoFonteService.sincronizar(fonte);
        } catch (SchedulerException e) {
            throw new IllegalStateException(
                    "Fonte " + fonte.getId() + " foi salva, mas o agendamento falhou: " + e.getMessage(), e);
        }
    }

    private void validarTipoAquisicao(String tipoAquisicao) {
        if (!TIPOS_ACEITOS.contains(tipoAquisicao)) {
            throw new IllegalArgumentException(
                    "tipoAquisicao precisa ser um destes: " + TIPOS_ACEITOS);
        }
    }

    /**
     * Recusa cron invalida antes de gravar - senao o erro so apareceria ao agendar.
     */
    private void validarCronExpressao(String cronExpressao) {
        if (cronExpressao == null || cronExpressao.isBlank()) {
            return;
        }
        if (!CronExpression.isValidExpression(cronExpressao)) {
            throw new IllegalArgumentException("cronExpressao inválida: " + cronExpressao);
        }
    }

    /**
     * Sem separador informado, mantém "," (era o único suportado antes desse campo
     * existir).
     */
    private String normalizarSeparadorCsv(String separadorCsv) {
        if (separadorCsv == null || separadorCsv.isEmpty()) {
            return SEPARADOR_CSV_PADRAO;
        }
        if (!SEPARADORES_CSV_ACEITOS.contains(separadorCsv)) {
            throw new IllegalArgumentException(
                    "separadorCsv precisa ser um destes: vírgula, ponto e vírgula, pipe ou tab");
        }
        return separadorCsv;
    }
}