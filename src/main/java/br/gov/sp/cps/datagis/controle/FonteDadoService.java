package br.gov.sp.cps.datagis.controle;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

/** Cadastra, edita e lista as fontes de dados. */
@Service
public class FonteDadoService {

    private static final Set<String> TIPOS_ACEITOS =
            Set.of("UPLOAD_MANUAL", "API_REST", "DOWNLOAD_URL");

    /** Separadores de campo de CSV suportados pelo IngestaoCsvExecutor. */
    private static final Set<String> SEPARADORES_CSV_ACEITOS = Set.of(",", ";", "|", "\t");
    private static final String SEPARADOR_CSV_PADRAO = ",";

    private final FonteDadoRepository repository;

    public FonteDadoService(FonteDadoRepository repository) {
        this.repository = repository;
    }

    public FonteDado cadastrar(FonteDadoRequest dados) {
        validarTipoAquisicao(dados.tipoAquisicao());

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

        return repository.save(fonte);
    }

    public FonteDado buscarPorId(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Fonte de dado " + id + " não encontrada"));
    }

    public FonteDado atualizar(Long id, FonteDadoRequest dados) {
        validarTipoAquisicao(dados.tipoAquisicao());

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

        return repository.save(fonte);
    }

    public List<FonteDado> listar() {
        return repository.findAll();
    }

    private void validarTipoAquisicao(String tipoAquisicao) {
        if (!TIPOS_ACEITOS.contains(tipoAquisicao)) {
            throw new IllegalArgumentException(
                    "tipoAquisicao precisa ser um destes: " + TIPOS_ACEITOS);
        }
    }

    /** Sem separador informado, mantém "," (era o único suportado antes desse campo existir). */
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
