package br.gov.sp.cps.datagis.validacao;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 2.4. Validador de Integridade Cruzada (Referência a Entidades Silver).
 *
 * <p>Responsável por:
 * <ul>
 *   <li>Verificar se o valor recebido no payload referencia uma chave existente
 *       em uma entidade canônica da camada Silver parametrizada em
 *       {@code controle.mapeamento_coluna.referencia_entidade_silver}
 *       (ex.: {@code silver.municipio}, {@code imovel_rural}, {@code area_protegida});</li>
 *   <li>Resolver a coluna de busca canônica por convenção (ex.: {@code municipio -> cod_ibge},
 *       {@code imovel_rural -> cod_imovel}) ou por especificação explícita ({@code entidade.coluna});</li>
 *   <li>Manter cache thread-safe em memória para evitar overhead de consultas repetidas,
 *       com pré-carregamento de entidades dimensionais/estáticas (como {@code municipio});</li>
 *   <li>Retornar o erro {@code REFERENCIA_NAO_ENCONTRADA} caso o registro referenciado não exista;</li>
 *   <li>Disponibilizar métodos de invalidação de cache ({@link #invalidarCache(String)})
 *       para quando novas entidades forem inseridas ou aprovadas.</li>
 * </ul>
 *
 * <p>Valores {@code null} ou em branco são repassados como {@code sucesso(null)}
 * (a obrigatoriedade é responsabilidade do {@link SanitizadorEntrada}).
 */
@Component
public class ValidadorIntegridadeCruzada implements ValidadorCampo {

    public static final String ERRO_REFERENCIA = "REFERENCIA_NAO_ENCONTRADA";

    private static final Pattern IDENTIFICADOR_SQL_VALIDO = Pattern.compile("^[a-zA-Z0-9_]+$");

    /**
     * Mapeamento padrão de tabela da Silver para a sua respectiva coluna de chave natural.
     */
    private static final Map<String, String> COLUNAS_PADRAO = Map.of(
            "municipio", "cod_ibge",
            "imovel_rural", "cod_imovel",
            "area_protegida", "codigo_origem",
            "auto_infracao", "numero_auto",
            "embargo_ambiental", "numero_termo"
    );

    /**
     * Tabelas pequenas/dimensionais cujas chaves devem ser integralmente pré-carregadas em memória.
     */
    private static final Set<String> TABELAS_PRE_CARREGAVEIS = Set.of("municipio");

    private final JdbcTemplate jdbcTemplate;
    private final String silverSchema;

    /**
     * Cache thread-safe de chaves existentes por entidade:
     * Chave externa: nome da entidade em minúsculas (ex.: "municipio")
     * Valor: conjunto de chaves em maiúsculas existentes na Silver.
     */
    private final ConcurrentHashMap<String, Set<String>> cache = new ConcurrentHashMap<>();

    @Autowired
    public ValidadorIntegridadeCruzada(
            JdbcTemplate jdbcTemplate,
            @Value("${spring.flyway.placeholders.silverSchema:SILVER}") String silverSchema) {
        this.jdbcTemplate = jdbcTemplate;
        this.silverSchema = (silverSchema != null && !silverSchema.isBlank()) ? silverSchema.trim() : "SILVER";
    }

    public ValidadorIntegridadeCruzada(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, "SILVER");
    }

    @Override
    public boolean suporta(ContextoValidacaoCampo contexto) {
        return contexto != null
                && contexto.referenciaEntidadeSilver() != null
                && !contexto.referenciaEntidadeSilver().isBlank();
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

        String refConfigurada = contexto != null ? contexto.referenciaEntidadeSilver() : null;
        if (refConfigurada == null || refConfigurada.isBlank()) {
            return ResultadoValidacao.sucesso(valor);
        }

        DefinicaoReferenciaSilver def = extrairDefinicao(refConfigurada);
        boolean existe = verificarExistencia(def, valorStr);

        if (!existe) {
            String nomeCampo = (contexto != null && contexto.colunaOrigem() != null)
                    ? contexto.colunaOrigem()
                    : "campo";
            return ResultadoValidacao.erro(
                    ERRO_REFERENCIA,
                    nomeCampo,
                    valorStr,
                    "Referência \"" + valorStr + "\" não encontrada na entidade Silver \"" + def.tabela() + "\""
            );
        }

        return ResultadoValidacao.sucesso(valorStr);
    }

    /**
     * Verifica se a chave existe na entidade da Silver, consultando o cache ou o banco de dados.
     */
    private boolean verificarExistencia(DefinicaoReferenciaSilver def, String valor) {
        String chaveValor = valor.toUpperCase(Locale.ROOT);
        String chaveCache = def.tabela().toLowerCase(Locale.ROOT);

        // Se for tabela pré-carregável (como municipio), carrega todas as chaves sob demanda
        if (TABELAS_PRE_CARREGAVEIS.contains(chaveCache)) {
            Set<String> chavesPreCarregadas = cache.computeIfAbsent(
                    chaveCache,
                    k -> carregarTodasChaves(def)
            );
            if (chavesPreCarregadas.contains(chaveValor)) {
                return true;
            }
            if (chaveValor.matches("^[A-Za-z]{2}-\\d+$")) {
                String semUf = chaveValor.substring(3);
                if (chavesPreCarregadas.contains(semUf)) {
                    return true;
                }
            }
            String norm = normalizarTexto(chaveValor);
            if (chavesPreCarregadas.contains(norm)) {
                return true;
            }
            return false;
        }

        // Para outras tabelas, verifica se já foi verificado e mantido em cache
        Set<String> chavesConhecidas = cache.get(chaveCache);
        if (chavesConhecidas != null && chavesConhecidas.contains(chaveValor)) {
            return true;
        }

        // Consulta pontual no banco de dados
        boolean existeNoBanco = consultarExistenciaPontual(def, valor);
        if (existeNoBanco) {
            cache.computeIfAbsent(chaveCache, k -> ConcurrentHashMap.newKeySet()).add(chaveValor);
            return true;
        }

        return false;
    }

    /**
     * Executa carga em lote de todas as chaves de entidades dimensionais (ex.: silver.municipio).
     * Para município, indexa código IBGE numérico, prefixado (ex.: PR-4106902) e nome oficial.
     */
    private Set<String> carregarTodasChaves(DefinicaoReferenciaSilver def) {
        try {
            String sql = "SELECT " + def.coluna() + " FROM " + silverSchema + "." + def.tabela();
            List<String> resultados = jdbcTemplate.queryForList(sql, String.class);
            Set<String> chaves = new HashSet<>();
            if (resultados != null) {
                for (String res : resultados) {
                    if (res != null && !res.isBlank()) {
                        String s = res.trim().toUpperCase(Locale.ROOT);
                        chaves.add(s);
                        if ("municipio".equalsIgnoreCase(def.tabela())) {
                            chaves.add("PR-" + s);
                        }
                    }
                }
            }

            if ("municipio".equalsIgnoreCase(def.tabela()) && !"nome".equalsIgnoreCase(def.coluna())) {
                try {
                    String sqlNome = "SELECT nome FROM " + silverSchema + ".municipio";
                    List<String> nomes = jdbcTemplate.queryForList(sqlNome, String.class);
                    if (nomes != null) {
                        for (String nm : nomes) {
                            if (nm != null && !nm.isBlank()) {
                                String nmUpper = nm.trim().toUpperCase(Locale.ROOT);
                                chaves.add(nmUpper);
                                chaves.add(normalizarTexto(nmUpper));
                            }
                        }
                    }
                } catch (Exception ignored) {
                    // Em mocks ou ambientes sem stub para 'nome', ignora
                }
            }

            Set<String> chavesSet = ConcurrentHashMap.newKeySet(chaves.size() + 16);
            chavesSet.addAll(chaves);
            return chavesSet;
        } catch (DataAccessException ex) {
            throw new IllegalStateException(
                    "Erro ao carregar referências da tabela \"" + silverSchema + "." + def.tabela() + "\": " + ex.getMessage(),
                    ex
            );
        }
    }

    public static String normalizarTexto(String s) {
        if (s == null) return "";
        return java.text.Normalizer.normalize(s.trim().toUpperCase(Locale.ROOT), java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
    }

    /**
     * Executa consulta pontual no banco para verificar existência de chave em tabelas volumosas.
     */
    private boolean consultarExistenciaPontual(DefinicaoReferenciaSilver def, String valor) {
        try {
            String sql = "SELECT COUNT(1) FROM " + silverSchema + "." + def.tabela() + " WHERE " + def.coluna() + " = ?";
            Integer count = jdbcTemplate.queryForObject(sql, Integer.class, valor);
            return count != null && count > 0;
        } catch (DataAccessException ex) {
            throw new IllegalStateException(
                    "Erro ao verificar integridade cruzada na tabela \"" + silverSchema + "." + def.tabela() + "\": " + ex.getMessage(),
                    ex
            );
        }
    }

    /**
     * Interpreta e sanitiza a string de configuração de referência Silver.
     * Suporta formatos:
     * <ul>
     *   <li>{@code "municipio"} -> tabela: {@code municipio}, coluna: {@code cod_ibge}</li>
     *   <li>{@code "silver.municipio"} -> tabela: {@code municipio}, coluna: {@code cod_ibge}</li>
     *   <li>{@code "municipio.cod_ibge"} -> tabela: {@code municipio}, coluna: {@code cod_ibge}</li>
     *   <li>{@code "silver.imovel_rural.cod_imovel"} -> tabela: {@code imovel_rural}, coluna: {@code cod_imovel}</li>
     * </ul>
     */
    public DefinicaoReferenciaSilver extrairDefinicao(String referenciaConfigurada) {
        if (referenciaConfigurada == null || referenciaConfigurada.isBlank()) {
            throw new IllegalArgumentException("Referência à entidade Silver não pode ser nula ou vazia");
        }

        String limpo = referenciaConfigurada.trim();
        // Remove prefixo de schema "silver." se presente
        if (limpo.toLowerCase(Locale.ROOT).startsWith("silver.")) {
            limpo = limpo.substring(7).trim();
        }

        String nomeTabela;
        String nomeColuna;

        if (limpo.contains(".")) {
            String[] partes = limpo.split("\\.", 2);
            nomeTabela = partes[0].trim();
            nomeColuna = partes[1].trim();
        } else {
            nomeTabela = limpo;
            nomeColuna = COLUNAS_PADRAO.get(nomeTabela.toLowerCase(Locale.ROOT));
            if (nomeColuna == null) {
                // Fallback padrão se não mapeada nas convenções
                nomeColuna = "id";
            }
        }

        if (!IDENTIFICADOR_SQL_VALIDO.matcher(nomeTabela).matches()
                || !IDENTIFICADOR_SQL_VALIDO.matcher(nomeColuna).matches()) {
            throw new IllegalArgumentException("Nome de tabela ou coluna de referência inválido ou suspeito: " + referenciaConfigurada);
        }

        return new DefinicaoReferenciaSilver(nomeTabela, nomeColuna);
    }

    /**
     * Invalida o cache da entidade referenciada.
     * Deve ser invocado quando novos registros forem inseridos na entidade Silver.
     */
    public void invalidarCache(String referenciaEntidadeSilver) {
        if (referenciaEntidadeSilver != null && !referenciaEntidadeSilver.isBlank()) {
            DefinicaoReferenciaSilver def = extrairDefinicao(referenciaEntidadeSilver);
            cache.remove(def.tabela().toLowerCase(Locale.ROOT));
        }
    }

    /**
     * Invalida todo o cache de integridade cruzada.
     */
    public void invalidarTodoCache() {
        cache.clear();
    }

    /**
     * Verifica se os dados de uma entidade Silver já estão presentes no cache.
     */
    public boolean isEmCache(String referenciaEntidadeSilver) {
        if (referenciaEntidadeSilver == null || referenciaEntidadeSilver.isBlank()) {
            return false;
        }
        try {
            DefinicaoReferenciaSilver def = extrairDefinicao(referenciaEntidadeSilver);
            return cache.containsKey(def.tabela().toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public int getQuantidadeEntidadesEmCache() {
        return cache.size();
    }

    public record DefinicaoReferenciaSilver(String tabela, String coluna) {}
}
