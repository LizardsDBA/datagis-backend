package br.gov.sp.cps.datagis.controle;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Avalia e valida a integridade do mapeamento de colunas de uma fonte de dados
 * em relação ao catálogo canônico da camada Silver.
 */
@Service
public class ValidacaoMapeamentoFonteService {

    private final CatalogoSilverService catalogoSilverService;
    private final MapeamentoColunaRepository mapeamentoRepository;

    public record StatusMapeamentoFonte(
            boolean completo,
            String entidadeSilver,
            String nomeSilver,
            int totalColunasEsperadas,
            int totalColunasMapeadas,
            List<String> colunasFaltantes,
            List<String> colunasMapeadas) {}

    public ValidacaoMapeamentoFonteService(
            CatalogoSilverService catalogoSilverService,
            MapeamentoColunaRepository mapeamentoRepository) {
        this.catalogoSilverService = catalogoSilverService;
        this.mapeamentoRepository = mapeamentoRepository;
    }

    /**
     * Avalia o status do mapeamento de uma fonte em relação ao seu esquema Silver.
     */
    public StatusMapeamentoFonte avaliarStatus(FonteDado fonte) {
        if (fonte == null || fonte.getEntidadeSilver() == null || fonte.getEntidadeSilver().isBlank()) {
            return new StatusMapeamentoFonte(false, null, null, 0, 0, Collections.emptyList(), Collections.emptyList());
        }

        CatalogoSilverService.EsquemaSilverDto esquema = catalogoSilverService.buscarPorId(fonte.getEntidadeSilver());
        if (esquema == null) {
            return new StatusMapeamentoFonte(
                    false,
                    fonte.getEntidadeSilver(),
                    fonte.getEntidadeSilver(),
                    0,
                    0,
                    Collections.emptyList(),
                    Collections.emptyList());
        }

        List<MapeamentoColuna> mapeamentos = fonte.getId() != null
                ? mapeamentoRepository.findByFonteDadoId(fonte.getId())
                : Collections.emptyList();

        Set<String> atributosMapeados = mapeamentos.stream()
                .filter(m -> m.getAtributoDestino() != null && !m.getAtributoDestino().isBlank())
                .filter(m -> !"JSON".equalsIgnoreCase(m.getDestinoTipo()))
                .map(m -> m.getAtributoDestino().trim().toLowerCase())
                .collect(Collectors.toSet());

        List<String> colunasObrigatorias = esquema.colunas().stream()
                .filter(CatalogoSilverService.ColunaSilverDto::obrigatorio)
                .map(CatalogoSilverService.ColunaSilverDto::nome)
                .toList();

        List<String> faltantes = colunasObrigatorias.stream()
                .filter(nome -> !atributosMapeados.contains(nome.toLowerCase()))
                .toList();

        List<String> mapeadas = esquema.colunas().stream()
                .map(CatalogoSilverService.ColunaSilverDto::nome)
                .filter(nome -> atributosMapeados.contains(nome.toLowerCase()))
                .toList();

        boolean completo = faltantes.isEmpty();

        return new StatusMapeamentoFonte(
                completo,
                esquema.id(),
                esquema.nome(),
                colunasObrigatorias.size(),
                mapeadas.size(),
                faltantes,
                mapeadas);
    }

    /**
     * Valida se a fonte está apta para ingestão. Lança {@link IllegalArgumentException}
     * com mensagem amigável caso a fonte não tenha tabela Silver ou esteja com colunas obrigatórias pendentes.
     */
    public void validarParaIngestao(FonteDado fonte) {
        if (fonte == null) {
            throw new IllegalArgumentException("Fonte de dados não informada.");
        }

        StatusMapeamentoFonte status = avaliarStatus(fonte);

        if (status.entidadeSilver() == null || status.entidadeSilver().isBlank()) {
            throw new IllegalArgumentException(
                    "A fonte \"" + fonte.getNome() + "\" não pode ser executada: nenhuma tabela Silver foi configurada.");
        }

        if (!status.completo()) {
            throw new IllegalArgumentException(
                    "A fonte \"" + fonte.getNome() + "\" não pode ser executada: o mapeamento para a tabela Silver '"
                            + status.nomeSilver() + "' está incompleto. Faltam " + status.colunasFaltantes().size()
                            + " coluna(s) obrigatória(s) (NOT NULL): " + String.join(", ", status.colunasFaltantes()) + ".");
        }
    }
}
