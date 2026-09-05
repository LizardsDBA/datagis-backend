package br.gov.sp.cps.datagis.controle;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

/** Cadastra e lista as fontes de dados. */
@Service
public class FonteDadoService {

    private static final Set<String> TIPOS_ACEITOS =
            Set.of("UPLOAD_MANUAL", "API_REST", "DOWNLOAD_URL");

    private final FonteDadoRepository repository;

    public FonteDadoService(FonteDadoRepository repository) {
        this.repository = repository;
    }

    public FonteDado cadastrar(FonteDadoRequest dados) {
        if (!TIPOS_ACEITOS.contains(dados.tipoAquisicao())) {
            throw new IllegalArgumentException(
                    "tipoAquisicao precisa ser um destes: " + TIPOS_ACEITOS);
        }

        FonteDado fonte = FonteDado.builder()
                .nome(dados.nome())
                .orgao(dados.orgao())
                .tipoAquisicao(dados.tipoAquisicao())
                .urlOrigem(dados.urlOrigem())
                .cronExpressao(dados.cronExpressao())
                .formatoEsperado(dados.formatoEsperado())
                .ativo(dados.ativo() != null ? dados.ativo() : true)
                .dataAdd(LocalDateTime.now())
                .build();

        return repository.save(fonte);
    }

    public List<FonteDado> listar() {
        return repository.findAll();
    }
}
