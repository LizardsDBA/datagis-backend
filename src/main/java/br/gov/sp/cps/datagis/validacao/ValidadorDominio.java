package br.gov.sp.cps.datagis.validacao;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 2.3. Validador de Domínio e Tabelas de Referência.
 *
 * <p>Responsável por:
 * <ul>
 *   <li>Consultar em cache thread-safe se o valor recebido no payload existe na
 *       tabela de referência configurada em {@code controle.mapeamento_coluna.tabela_referencia}
 *       (ex.: {@code silver.situacao_imovel}, {@code silver.bioma}, {@code silver.orgao});</li>
 *   <li>Consultar apenas registros ativos ({@code WHERE ativo = 1});</li>
 *   <li>Permitir correspondência de domínio tanto por código quanto por descrição (case-insensitive);</li>
 *   <li>Normalizar e retornar o código canônico para persistência íntegra na Silver;</li>
 *   <li>Retornar o erro {@code VALOR_FORA_DO_DOMINIO} caso o valor não exista na referência;</li>
 *   <li>Disponibilizar métodos de invalidação de cache ({@link #invalidarCache(String)})
 *       para quando o analista cadastrar ou aprovar novos valores, permitindo que a engine
 *       revalide os registros pendentes sem reiniciar a aplicação.</li>
 * </ul>
 *
 * <p>Valores {@code null} ou vazios são repassados como {@code sucesso(null)}
 * (a obrigatoriedade é responsabilidade do {@link SanitizadorEntrada}).
 */
@Component
public class ValidadorDominio implements ValidadorCampo {

    public static final String ERRO_DOMINIO = "VALOR_FORA_DO_DOMINIO";

    private static final Pattern NOME_TABELA_VALIDO = Pattern.compile("^[a-zA-Z0-9_]+$");

    private final JdbcTemplate jdbcTemplate;
    private final String silverSchema;

    /**
     * Cache thread-safe por tabela:
     * Chave externa: nome da tabela em minúsculas (ex: "situacao_imovel")
     * Mapa interno: termo em maiúsculas (código ou descrição) -> código canônico cadastrado no banco.
     */
    private final ConcurrentHashMap<String, Map<String, String>> cache = new ConcurrentHashMap<>();

    public ValidadorDominio(
            JdbcTemplate jdbcTemplate,
            @Value("${spring.flyway.placeholders.silverSchema:SILVER}") String silverSchema) {
        this.jdbcTemplate = jdbcTemplate;
        this.silverSchema = (silverSchema != null && !silverSchema.isBlank()) ? silverSchema.trim() : "SILVER";
    }

    public ValidadorDominio(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, "SILVER");
    }

    @Override
    public boolean suporta(ContextoValidacaoCampo contexto) {
        return contexto != null
                && contexto.tabelaReferencia() != null
                && !contexto.tabelaReferencia().isBlank();
    }

    @Override
    public ResultadoValidacao validar(Object valor, ContextoValidacaoCampo contexto) {
        if (valor == null) {
            // Valor nulo é responsabilidade do SanitizadorEntrada (se obrigatorio)
            return ResultadoValidacao.sucesso(null);
        }

        String valorStr = valor.toString().trim();
        if (valorStr.isEmpty()) {
            return ResultadoValidacao.sucesso(null);
        }

        String nomeTabelaConfigurada = contexto != null ? contexto.tabelaReferencia() : null;
        if (nomeTabelaConfigurada == null || nomeTabelaConfigurada.isBlank()) {
            return ResultadoValidacao.sucesso(valor);
        }

        String nomeTabela = extrairNomeTabela(nomeTabelaConfigurada);
        Map<String, String> dominio = cache.computeIfAbsent(
                nomeTabela.toLowerCase(Locale.ROOT),
                t -> carregarDominio(nomeTabela)
        );

        String chaveBusca = valorStr.toUpperCase(Locale.ROOT);
        String codigoCanonico = dominio.get(chaveBusca);

        if (codigoCanonico == null) {
            String nomeCampo = (contexto != null && contexto.colunaOrigem() != null)
                    ? contexto.colunaOrigem()
                    : "campo";
            return ResultadoValidacao.erro(
                    ERRO_DOMINIO,
                    nomeCampo,
                    valorStr,
                    "Valor \"" + valorStr + "\" não encontrado na tabela de referência \"" + nomeTabela + "\""
            );
        }

        return ResultadoValidacao.sucesso(codigoCanonico);
    }

    /**
     * Sanitiza e extrai o nome simples da tabela de referência, removendo
     * eventuais prefixos de schema (ex.: "silver.situacao_imovel" -> "situacao_imovel")
     * e validando contra caracteres suspeitos (mitigação de SQL Injection).
     */
    public String extrairNomeTabela(String nomeTabelaConfigurada) {
        if (nomeTabelaConfigurada == null || nomeTabelaConfigurada.isBlank()) {
            throw new IllegalArgumentException("Nome da tabela de referência não pode ser nulo ou vazio");
        }
        String limpo = nomeTabelaConfigurada.trim();
        if (limpo.contains(".")) {
            limpo = limpo.substring(limpo.lastIndexOf('.') + 1).trim();
        }
        if (!NOME_TABELA_VALIDO.matcher(limpo).matches()) {
            throw new IllegalArgumentException("Nome de tabela de referência inválido ou suspeito: " + nomeTabelaConfigurada);
        }
        return limpo;
    }

    /**
     * Carrega do banco de dados todos os registros ativos da tabela de referência informada.
     */
    private Map<String, String> carregarDominio(String nomeTabela) {
        Map<String, String> mapa = new HashMap<>();
        try {
            String sql = "SELECT codigo, descricao FROM " + silverSchema + "." + nomeTabela + " WHERE ativo = 1";
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);
            for (Map<String, Object> row : rows) {
                Object codObj = obterValor(row, "codigo");
                if (codObj != null) {
                    String codStr = codObj.toString().trim();
                    mapa.put(codStr.toUpperCase(Locale.ROOT), codStr);

                    Object descObj = obterValor(row, "descricao");
                    if (descObj != null) {
                        String descStr = descObj.toString().trim();
                        if (!descStr.isBlank()) {
                            mapa.putIfAbsent(descStr.toUpperCase(Locale.ROOT), codStr);
                        }
                    }
                }
            }
        } catch (DataAccessException ex) {
            // Fallback caso a tabela não possua coluna 'descricao'
            try {
                String sqlCodigo = "SELECT codigo FROM " + silverSchema + "." + nomeTabela + " WHERE ativo = 1";
                List<String> codigos = jdbcTemplate.queryForList(sqlCodigo, String.class);
                for (String cod : codigos) {
                    if (cod != null && !cod.isBlank()) {
                        String codStr = cod.trim();
                        mapa.put(codStr.toUpperCase(Locale.ROOT), codStr);
                    }
                }
            } catch (DataAccessException fallbackEx) {
                throw new IllegalStateException(
                        "Erro ao carregar domínio da tabela de referência \"" + silverSchema + "." + nomeTabela + "\": " + fallbackEx.getMessage(),
                        fallbackEx
                );
            }
        }
        return Collections.unmodifiableMap(mapa);
    }

    private Object obterValor(Map<String, Object> row, String nomeColuna) {
        Object val = row.get(nomeColuna);
        if (val == null) {
            val = row.get(nomeColuna.toUpperCase(Locale.ROOT));
        }
        return val;
    }

    /**
     * Invalida o cache da tabela de referência informada.
     * Permite que a engine revalide dados após uma aprovação ou inserção feita pelo analista.
     *
     * @param tabelaReferencia nome da tabela (com ou sem prefixo de schema)
     */
    public void invalidarCache(String tabelaReferencia) {
        if (tabelaReferencia != null && !tabelaReferencia.isBlank()) {
            String nomeTabela = extrairNomeTabela(tabelaReferencia).toLowerCase(Locale.ROOT);
            cache.remove(nomeTabela);
        }
    }

    /**
     * Invalida todo o cache de tabelas de referência.
     */
    public void invalidarTodoCache() {
        cache.clear();
    }

    /**
     * Indica se uma tabela de referência já foi carregada no cache em memória.
     */
    public boolean isEmCache(String tabelaReferencia) {
        if (tabelaReferencia == null || tabelaReferencia.isBlank()) {
            return false;
        }
        try {
            String nomeTabela = extrairNomeTabela(tabelaReferencia).toLowerCase(Locale.ROOT);
            return cache.containsKey(nomeTabela);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Retorna o total de tabelas de referência mantidas em cache.
     */
    public int getQuantidadeTabelasEmCache() {
        return cache.size();
    }
}
