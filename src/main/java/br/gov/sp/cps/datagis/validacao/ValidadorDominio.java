package br.gov.sp.cps.datagis.validacao;

import java.text.Normalizer;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
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
 *       (ex.: {@code silver.situacao_imovel}, {@code silver.condicao_imovel}, {@code silver.bioma}, {@code silver.orgao});</li>
 *   <li>Consultar apenas registros ativos ({@code WHERE ativo = 1});</li>
 *   <li>Permitir correspondência de domínio tanto por código quanto por descrição de forma resiliente
 *       (case-insensitive, acento-insensível e tolerante a pontuação e separadores como espaço ou underline);</li>
 *   <li>Resolver variações semânticas e sub-estados oficiais conhecidos (ex.: variações da Lei 12.651/2012 do SICAR/CAR);</li>
 *   <li>Normalizar e retornar o código canônico para persistência íntegra na Silver;</li>
 *   <li>Gerar o erro {@code VALOR_FORA_DO_DOMINIO} quando não houver correspondência;</li>
 *   <li>Disponibilizar método para invalidação/recarregamento do cache (quando o analista aprovar novo valor).</li>
 * </ul>
 *
 * <p>Valores {@code null} são ignorados por esta etapa (responsabilidade do {@link SanitizadorEntrada}).
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
     * Mapa interno: termos normalizados (código, descrição, sinônimos) -> código canônico cadastrado no banco.
     */
    private final ConcurrentHashMap<String, Map<String, String>> cache = new ConcurrentHashMap<>();

    @Autowired
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

        String codigoCanonico = resolverCodigoCanonico(valorStr, nomeTabela, dominio);

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
     * Normaliza aliases e variações de nomes de tabelas de referência e fatos (lookups).
     */
    public static String normalizarAliasTabela(String nomeTabela) {
        if (nomeTabela == null || nomeTabela.isBlank()) {
            return null;
        }
        String tab = extrairNomeTabelaEstatico(nomeTabela).toLowerCase(Locale.ROOT);
        return switch (tab) {
            case "classes_prodes", "classe_prodes", "prodes" -> "classe_prodes";
            case "tipo_area_embrago", "tipo_area_embargo" -> "tipo_area_embargo";
            case "bioma_predominante", "bioma" -> "bioma";
            case "uf", "unidade_federativa" -> "unidade_federativa";
            default -> tab;
        };
    }

    /**
     * Resolve o código canônico a partir do valor recebido, aplicando buscas diretas,
     * normalizações fonéticas (sem acentos), separadores alternativos e resolução semântica.
     */
    public String resolverCodigoCanonico(String valorStr, String nomeTabela, Map<String, String> dominio) {
        if (valorStr == null || valorStr.isBlank()) {
            return null;
        }

        // 1. Busca direta (case-insensitive)
        String upper = valorStr.toUpperCase(Locale.ROOT);
        String cod = dominio.get(upper);
        if (cod != null) return cod;

        // Se for no formato UF-IBGE (ex.: PR-4106902), tenta buscar apenas pelo código numérico
        if (valorStr.matches("^[A-Za-z]{2}-\\d+$")) {
            String semUf = valorStr.substring(3);
            cod = dominio.get(semUf);
            if (cod != null) return cod;
        }

        // 2. Busca sem acentos
        String norm = normalizarTexto(valorStr);
        cod = dominio.get(norm);
        if (cod != null) return cod;

        // 3. Substituição de separadores (_ <-> espaço <-> hífen)
        if (norm.contains("_")) {
            cod = dominio.get(norm.replace('_', ' '));
            if (cod != null) return cod;
        }
        if (norm.contains(" ")) {
            cod = dominio.get(norm.replace(' ', '_'));
            if (cod != null) return cod;
        }
        if (norm.contains("-")) {
            cod = dominio.get(norm.replace('-', ' '));
            if (cod != null) return cod;
        }

        // 4. Apenas alfanuméricos (elimina vírgulas, parênteses, pontos, graus, etc.)
        String alnum = normalizarApenasAlfanumerico(valorStr);
        cod = dominio.get(alnum);
        if (cod != null) return cod;

        // 5. Resolução Semântica de Sinônimos e Sub-estados de órgãos oficiais (apenas se existir no domínio)
        String sinonimo = resolverSinonimosConhecidos(norm, nomeTabela);
        if (sinonimo != null && !dominio.isEmpty()) {
            if (dominio.containsKey(sinonimo) || dominio.containsValue(sinonimo)) {
                return sinonimo;
            }
        }

        return null;
    }

    /**
     * Resolução de sinônimos e sub-estados conhecidos de órgãos públicos oficiais (ex: SICAR, MMA, IBAMA).
     */
    public String resolverSinonimosConhecidos(String norm, String nomeTabela) {
        return resolverSinonimosConhecidosEstatico(norm, nomeTabela);
    }

    public static String resolverSinonimosConhecidosEstatico(String valor, String nomeTabela) {
        if (valor == null || valor.isBlank() || nomeTabela == null) {
            return null;
        }
        String norm = normalizarTexto(valor);
        String tab = normalizarAliasTabela(nomeTabela);

        if ("condicao_imovel".equals(tab)) {
            // Variações do SICAR (Lei 12.651/2012):
            if (norm.startsWith("AGUARDANDO ANALISE") || norm.startsWith("AGUARDANDO REVISAO") || norm.startsWith("AGUARDANDO RETIFICACAO")) {
                return "AGUARDANDO_ANALISE";
            }
            if (norm.startsWith("EM ANALISE") || norm.startsWith("ANALISE INICIADA")) {
                return "EM_ANALISE";
            }
            if (norm.contains("CONFORMIDADE") || norm.contains("SEM PENDENCIA")) {
                return "ANALISADO_SEM_PENDENCIAS";
            }
            if (norm.contains("REGULARIZACAO") || norm.contains("COM PENDENCIA") || norm.contains("ATENDIMENTO A NOTIFICACAO") || norm.contains("NOTIFICACAO")) {
                return "ANALISADO_COM_PENDENCIAS";
            }
            if (norm.contains("CANCELADO") && norm.contains("ADMINISTRATIV")) {
                return "CANCELADO_ADM";
            }
            if (norm.contains("CANCELADO") && norm.contains("JUDICIAL")) {
                return "CANCELADO_JUDICIAL";
            }
        } else if ("situacao_imovel".equals(tab)) {
            if (norm.startsWith("ATIV") || norm.equals("AT")) {
                return "AT";
            }
            if (norm.startsWith("PENDENT") || norm.equals("PE")) {
                return "PE";
            }
            if (norm.startsWith("SUSPENS") || norm.equals("SU")) {
                return "SU";
            }
            if (norm.startsWith("CANCELAD") || norm.equals("CA")) {
                return "CA";
            }
        } else if ("tipo_imovel".equals(tab)) {
            if (norm.contains("ASSENTAMENTO") || norm.contains("REFORMA AGRARIA") || norm.equals("AST")) {
                return "AST";
            }
            if (norm.contains("TRADICIONAIS") || norm.contains("QUILOMB") || norm.contains("INDIGEN") || norm.equals("PCT")) {
                return "PCT";
            }
            if (norm.contains("RURAL") || norm.equals("IRU")) {
                return "IRU";
            }
        } else if ("tipo_pessoa".equals(tab)) {
            if (norm.contains("FISIC") || norm.equals("PF")) {
                return "PF";
            }
            if (norm.contains("JURIDIC") || norm.equals("PJ")) {
                return "PJ";
            }
        } else if ("orgao".equals(tab)) {
            if (norm.contains("IBAMA")) return "IBAMA";
            if (norm.contains("ICMBIO") || norm.contains("CHICO MENDES")) return "ICMBIO";
            if (norm.contains("FUNAI")) return "FUNAI";
            if (norm.contains("INCRA")) return "INCRA";
            if (norm.contains("IBGE")) return "IBGE";
            if (norm.contains("INPE")) return "INPE";
            if (norm.contains("IAT") || norm.contains("AGUA E TERRA") || norm.equals("IAP")) return "IAT_PR";
            if (norm.contains("SICAR") || norm.contains("FLORESTAL BRASILEIRO")) return "SFB_SICAR";
        } else if ("bioma".equals(tab)) {
            if (norm.contains("ATLANTICA")) return "MATA_ATLANTICA";
            if (norm.contains("CERRADO")) return "CERRADO";
            if (norm.contains("CAATINGA")) return "CAATINGA";
            if (norm.contains("AMAZON")) return "AMAZONIA";
            if (norm.contains("PAMPA")) return "PAMPA";
            if (norm.contains("PANTANAL")) return "PANTANAL";
        } else if ("satelite".equals(tab)) {
            if (norm.contains("AQUA")) return "AQUA_M-T";
            if (norm.contains("TERRA")) return "TERRA_M-M";
            if (norm.contains("GOES")) return "GOES-16";
            if (norm.contains("NOAA")) return "NOAA-20";
            if (norm.contains("NPP") || norm.contains("SUOMI")) return "NPP-375";
            if (norm.contains("LANDSAT 7") || norm.contains("LANDSAT7")) return "LANDSAT7";
            if (norm.contains("LANDSAT 8") || norm.contains("LANDSAT8")) return "LANDSAT8";
        } else if ("sensor".equals(tab)) {
            if (norm.contains("ETM")) return "ETM";
            if (norm.contains("TM") && !norm.contains("ETM")) return "TM";
            if (norm.contains("OLI")) return "OLI";
            if (norm.contains("MODIS")) return "MODIS";
            if (norm.contains("VIIRS")) return "VIIRS";
        } else if ("classe_prodes".equals(tab)) {
            if (norm.contains("DESMAT") || norm.contains("CORTE RASO")) return "DESMATAMENTO";
            if (norm.contains("NAO FLORESTA") || norm.contains("NAO_FLORESTA")) return "NAO_FLORESTA";
            if (norm.contains("FLORESTA") && !norm.contains("NAO")) return "FLORESTA";
            if (norm.contains("NUVEM")) return "NUVEM";
            if (norm.contains("HIDRO") || norm.contains("AGUA")) return "HIDROGRAFIA";
        } else if ("tipo_area_embargo".equals(tab)) {
            if (norm.contains("DESMAT")) return "DESMATAMENTO";
            if (norm.contains("QUEIM") || norm.contains("INCEND")) return "QUEIMADA";
            if (norm.contains("EXPLOR")) return "EXPLORACAO_IRREGULAR";
            if (norm.contains("OUTRO")) return "OUTROS";
        } else if ("tipo_auto_infracao".equals(tab)) {
            if (norm.contains("MULTA DIARIA")) return "MULTA_DIARIA";
            if (norm.contains("MULTA")) return "MULTA_SIMPLES";
            if (norm.contains("ADVERT")) return "ADVERTENCIA";
            if (norm.contains("APREENS")) return "APREENSAO";
            if (norm.contains("EMBARGO") || norm.contains("INTERDIC")) return "EMBARGO";
            if (norm.contains("DEMOLIC")) return "DEMOLICAO";
            if (norm.contains("SUSPENS")) return "SUSPENSAO";
        } else if ("tipo_infracao".equals(tab)) {
            if (norm.contains("FLORA")) return "CONTRA_FLORA";
            if (norm.contains("FAUNA")) return "CONTRA_FAUNA";
            if (norm.contains("POLUIC")) return "POLUICAO";
            if (norm.contains("ADMIN")) return "CONTRA_ADMIN_AMBIENTAL";
            if (norm.contains("UNIDADE") || norm.contains("CONSERVACAO") || norm.contains("UC")) return "EM_UNIDADES_CONSERVACAO";
            if (norm.contains("URBANO") || norm.contains("PATRIMONIO")) return "CONTRA_ORDENAMENTO_URBANO";
        } else if ("status_auto_infracao".equals(tab)) {
            if (norm.contains("LAVR")) return "LAVRADO";
            if (norm.contains("HOMOLOG")) return "HOMOLOGADO";
            if (norm.contains("JULG")) return "JULGADO";
            if (norm.contains("CANCEL")) return "CANCELADO";
        } else if ("tipo_area_protegida".equals(tab)) {
            if (norm.contains("INTEGRAL")) return "UC_PROTECAO_INTEGRAL";
            if (norm.contains("SUSTENTAVEL") || norm.contains("USO")) return "UC_USO_SUSTENTAVEL";
            if (norm.contains("INDIGEN") || norm.equals("TI")) return "TERRA_INDIGENA";
            if (norm.contains("QUILOMB")) return "TERRITORIO_QUILOMBOLA";
            if (norm.contains("ASSENTAMENTO") || norm.contains("INCRA")) return "ASSENTAMENTO_INCRA";
        } else if ("categoria_area_protegida".equals(tab)) {
            if (norm.contains("PARNA") || norm.contains("PARQUE NACIONAL")) return "PARNA";
            if (norm.contains("REBIO") || norm.contains("RESERVA BIOLOGICA")) return "REBIO";
            if (norm.contains("ESEC") || norm.contains("ESTACAO ECOLOGICA")) return "ESEC";
            if (norm.contains("REVIS") || norm.contains("REFUGIO")) return "REVIS";
            if (norm.contains("MONA") || norm.contains("MONUMENTO")) return "MONA";
            if (norm.contains("FLONA") || norm.contains("FLORESTA NACIONAL")) return "FLONA";
            if (norm.contains("RESEX") || norm.contains("RESERVA EXTRATIVISTA")) return "RESEX";
            if (norm.contains("RDS") || norm.contains("DESENVOLVIMENTO SUSTENTAVEL")) return "RDS";
            if (norm.contains("ARIE") || norm.contains("RELEVANTE INTERESSE")) return "ARIE";
            if (norm.contains("APA") || norm.contains("PROTECAO AMBIENTAL")) return "APA";
            if (norm.contains("RPPN") || norm.contains("PARTICULAR")) return "RPPN";
            if (norm.contains("TRADICIONAL")) return "TI_TRADICIONALMENTE_OCUPADA";
            if (norm.contains("RESERVA INDIGENA")) return "TI_RESERVA_INDIGENA";
            if (norm.contains("DOMINIAL")) return "TI_DOMINIAL";
            if (norm.contains("INTERDITADA")) return "TI_INTERDITADA";
        } else if ("unidade_federativa".equals(tab)) {
            return switch (norm) {
                case "ACRE", "AC", "12" -> "AC";
                case "ALAGOAS", "AL", "27" -> "AL";
                case "AMAPA", "AP", "16" -> "AP";
                case "AMAZONAS", "AM", "13" -> "AM";
                case "BAHIA", "BA", "29" -> "BA";
                case "CEARA", "CE", "23" -> "CE";
                case "DISTRITO FEDERAL", "DF", "BRASILIA", "53" -> "DF";
                case "ESPIRITO SANTO", "ES", "32" -> "ES";
                case "GOIAS", "GO", "52" -> "GO";
                case "MARANHAO", "MA", "21" -> "MA";
                case "MATO GROSSO", "MT", "51" -> "MT";
                case "MATO GROSSO DO SUL", "MS", "50" -> "MS";
                case "MINAS GERAIS", "MG", "31" -> "MG";
                case "PARA", "PA", "15" -> "PA";
                case "PARAIBA", "PB", "25" -> "PB";
                case "PARANA", "PR", "41" -> "PR";
                case "PERNAMBUCO", "PE", "26" -> "PE";
                case "PIAUI", "PI", "22" -> "PI";
                case "RIO DE JANEIRO", "RJ", "33" -> "RJ";
                case "RIO GRANDE DO NORTE", "RN", "24" -> "RN";
                case "RIO GRANDE DO SUL", "RS", "43" -> "RS";
                case "RONDONIA", "RO", "11" -> "RO";
                case "RORAIMA", "RR", "14" -> "RR";
                case "SANTA CATARINA", "SC", "42" -> "SC";
                case "SAO PAULO", "SP", "35" -> "SP";
                case "SERGIPE", "SE", "28" -> "SE";
                case "TOCANTINS", "TO", "17" -> "TO";
                default -> null;
            };
        }

        return null;
    }

    /**
     * Sanitiza e extrai o nome simples da tabela de referência, removendo
     * eventuais prefixos de schema (ex.: "silver.situacao_imovel" -> "situacao_imovel")
     * e validando contra caracteres suspeitos (mitigação de SQL Injection).
     */
    public String extrairNomeTabela(String nomeTabelaConfigurada) {
        return normalizarAliasTabela(extrairNomeTabelaEstatico(nomeTabelaConfigurada));
    }

    public static String extrairNomeTabelaEstatico(String nomeTabelaConfigurada) {
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
     * Carrega do banco de dados todos os registros ativos da tabela de referência informada,
     * indexando variações ortográficas e normalizações para busca rápida em O(1).
     * Suporta tabelas de domínio ativas (WHERE ativo = 1) e esquemas específicos como silver.unidade_federativa.
     * OBS: Município e Auto de Infração pertencem à Integridade Cruzada (ValidadorIntegridadeCruzada).
     */
    private Map<String, String> carregarDominio(String nomeTabela) {
        Map<String, String> mapa = new HashMap<>();
        String tab = normalizarAliasTabela(nomeTabela);

        if ("unidade_federativa".equals(tab)) {
            try {
                String sql = "SELECT codigo, descricao, CAST(cod_ibge AS VARCHAR2(10)) AS cod_ibge FROM " + silverSchema + ".unidade_federativa WHERE ativo = 1";
                List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);
                for (Map<String, Object> row : rows) {
                    Object codObj = obterValor(row, "codigo");
                    Object descObj = obterValor(row, "descricao");
                    Object ibgeObj = obterValor(row, "cod_ibge");
                    if (codObj != null) {
                        String codStr = codObj.toString().trim();
                        registrarNoMapa(mapa, codStr, codStr);
                        if (descObj != null) {
                            String descStr = descObj.toString().trim();
                            if (!descStr.isBlank()) {
                                registrarNoMapa(mapa, descStr, codStr);
                            }
                        }
                        if (ibgeObj != null) {
                            String ibgeStr = ibgeObj.toString().trim();
                            if (!ibgeStr.isBlank()) {
                                registrarNoMapa(mapa, ibgeStr, codStr);
                            }
                        }
                    }
                }
            } catch (DataAccessException ex) {
                throw new IllegalStateException("Erro ao carregar domínio da tabela silver.unidade_federativa: " + ex.getMessage(), ex);
            }
            return Collections.unmodifiableMap(mapa);
        }

        // Tabelas dimensionais/referência padrão com codigo, descricao e ativo = 1
        try {
            String sql = "SELECT codigo, descricao FROM " + silverSchema + "." + tab + " WHERE ativo = 1";
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);
            for (Map<String, Object> row : rows) {
                Object codObj = obterValor(row, "codigo");
                if (codObj != null) {
                    String codStr = codObj.toString().trim();
                    registrarNoMapa(mapa, codStr, codStr);

                    Object descObj = obterValor(row, "descricao");
                    if (descObj != null) {
                        String descStr = descObj.toString().trim();
                        if (!descStr.isBlank()) {
                            registrarNoMapa(mapa, descStr, codStr);
                        }
                    }
                }
            }
        } catch (DataAccessException ex) {
            // Fallback caso a tabela não possua coluna 'descricao'
            try {
                String sqlCodigo = "SELECT codigo FROM " + silverSchema + "." + tab + " WHERE ativo = 1";
                List<String> codigos = jdbcTemplate.queryForList(sqlCodigo, String.class);
                for (String cod : codigos) {
                    if (cod != null && !cod.isBlank()) {
                        String codStr = cod.trim();
                        registrarNoMapa(mapa, codStr, codStr);
                    }
                }
            } catch (DataAccessException fallbackEx) {
                throw new IllegalStateException(
                        "Erro ao carregar domínio da tabela de referência \"" + silverSchema + "." + tab + "\": " + fallbackEx.getMessage(),
                        fallbackEx
                );
            }
        }
        return Collections.unmodifiableMap(mapa);
    }

    /**
     * Registra no mapa a chave e suas formas normalizadas para correspondência flexível.
     */
    private void registrarNoMapa(Map<String, String> mapa, String chave, String codigoCanonico) {
        if (chave == null || chave.isBlank()) return;

        String upper = chave.trim().toUpperCase(Locale.ROOT);
        mapa.putIfAbsent(upper, codigoCanonico);

        String norm = normalizarTexto(chave);
        mapa.putIfAbsent(norm, codigoCanonico);

        if (norm.contains("_")) {
            mapa.putIfAbsent(norm.replace('_', ' '), codigoCanonico);
        }
        if (norm.contains("-")) {
            mapa.putIfAbsent(norm.replace('-', ' '), codigoCanonico);
        }
        if (norm.contains(" ")) {
            mapa.putIfAbsent(norm.replace(' ', '_'), codigoCanonico);
        }

        String alnum = normalizarApenasAlfanumerico(chave);
        if (!alnum.isBlank()) {
            mapa.putIfAbsent(alnum, codigoCanonico);
        }
    }

    /**
     * Remove todos os acentos e diacríticos, substitui múltiplos espaços por um só e converte para maiúsculas.
     */
    public static String normalizarTexto(String texto) {
        if (texto == null) {
            return "";
        }
        String nfd = Normalizer.normalize(texto.trim(), Normalizer.Form.NFD);
        String semAcento = nfd.replaceAll("\\p{M}", "");
        return semAcento.replaceAll("\\s+", " ").toUpperCase(Locale.ROOT).trim();
    }

    /**
     * Remove todos os caracteres não alfanuméricos de uma string normalizada.
     */
    public static String normalizarApenasAlfanumerico(String texto) {
        String normalizado = normalizarTexto(texto);
        return normalizado.replaceAll("[^A-Z0-9]", "");
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
