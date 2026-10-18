package br.gov.sp.cps.datagis.conjuntosdados;

import br.gov.sp.cps.datagis.bronze.RegistroBruto;
import br.gov.sp.cps.datagis.bronze.RegistroBrutoRepository;
import br.gov.sp.cps.datagis.controle.FonteDado;
import br.gov.sp.cps.datagis.controle.FonteDadoRepository;
import br.gov.sp.cps.datagis.controle.LoteIngestao;
import br.gov.sp.cps.datagis.controle.LoteIngestaoRepository;
import br.gov.sp.cps.datagis.gold.GoldPublicacaoService;
import br.gov.sp.cps.datagis.validacao.TransicaoBronzeSilverService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Suporte à tela de Conjunto de Dados: dado o último lote de uma fonte que chegou na
 * Silver, permite comparar o mesmo registro (via {@code registro_bruto_id}) nas 3
 * camadas — Bronze (JPA), Silver (sem entidade JPA, só JDBC — ver
 * {@link br.gov.sp.cps.datagis.validacao.PersistidorSilverService}) e Quarentena (já
 * coberta pelo {@code GET /api/quarentena} existente, não duplicada aqui).
 */
@Service
public class ConjuntosDadosService {

    private final LoteIngestaoRepository loteIngestaoRepository;
    private final FonteDadoRepository fonteDadoRepository;
    private final RegistroBrutoRepository registroBrutoRepository;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final TransicaoBronzeSilverService transicaoService;

    /** Colunas/FKs de cada tabela Silver não mudam em runtime — cache simples por tabela. */
    private final Map<String, InfoTabelaSilver> cacheInfoTabela = new ConcurrentHashMap<>();

    private record InfoTabelaSilver(List<String> expressoesColuna, List<String> joins) {}

    public ConjuntosDadosService(
            LoteIngestaoRepository loteIngestaoRepository,
            FonteDadoRepository fonteDadoRepository,
            RegistroBrutoRepository registroBrutoRepository,
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            TransicaoBronzeSilverService transicaoService) {
        this.loteIngestaoRepository = loteIngestaoRepository;
        this.fonteDadoRepository = fonteDadoRepository;
        this.registroBrutoRepository = registroBrutoRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.transicaoService = transicaoService;
    }

    public LoteResumoDto buscarUltimoLotePromovido(Long fonteDadoId) {
        FonteDado fonte = fonteDadoRepository.findById(fonteDadoId)
                .orElseThrow(() -> new IllegalArgumentException("Fonte de dado " + fonteDadoId + " não encontrada"));
        LoteIngestao lote = loteIngestaoRepository
                .findFirstByFonteDadoIdAndStatusOrderByIdDesc(fonteDadoId, TransicaoBronzeSilverService.STATUS_PROMOVIDO_SILVER)
                .or(() -> loteIngestaoRepository.findFirstByFonteDadoIdAndStatusOrderByIdDesc(fonteDadoId, GoldPublicacaoService.STATUS_PROMOVIDO_GOLD))
                .orElseThrow(() -> new IllegalArgumentException(
                        "Fonte \"" + fonte.getNome() + "\" ainda não teve nenhum lote promovido para a Silver"));
        String entidadeSilver = resolverTabelaSilver(fonte);
        return new LoteResumoDto(
                lote.getId(), fonte.getId(), fonte.getNome(), entidadeSilver,
                lote.getQuantidadeRegistros(), lote.getStatus(), lote.getDataExecucao());
    }

    public PaginaDto<RegistroCamadaDto> listarBronze(Long loteId, int pagina, int tamanho) {
        var page = registroBrutoRepository.findByLoteIngestaoId(
                loteId, PageRequest.of(Math.max(0, pagina), Math.max(1, tamanho), Sort.by("id").ascending()));
        List<RegistroCamadaDto> itens = page.getContent().stream().map(this::paraDtoBronze).toList();
        return new PaginaDto<>(itens, page.getTotalElements(), pagina, tamanho);
    }

    public RegistroCamadaDto buscarRegistroBronze(Long registroBrutoId) {
        RegistroBruto registro = registroBrutoRepository.findById(registroBrutoId)
                .orElseThrow(() -> new IllegalArgumentException("Registro bruto " + registroBrutoId + " não encontrado"));
        return paraDtoBronze(registro);
    }

    public PaginaDto<RegistroCamadaDto> listarSilver(Long loteId, int pagina, int tamanho) {
        LoteIngestao lote = loteIngestaoRepository.findByIdComFonte(loteId)
                .orElseThrow(() -> new IllegalArgumentException("Lote " + loteId + " não encontrado"));
        String tabela = resolverTabelaSilver(lote.getFonteDado());

        int offset = Math.max(0, pagina) * Math.max(1, tamanho);
        String sql = "SELECT " + colunasSelecionaveis(tabela) + " FROM SILVER." + tabela + " t"
                + joinsAdicionais(tabela) + " WHERE t.lote_ingestao_id = ? ORDER BY t.id ASC OFFSET ? ROWS FETCH NEXT ? ROWS ONLY";
        List<Map<String, Object>> linhas = jdbcTemplate.queryForList(sql, loteId, offset, Math.max(1, tamanho));

        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM SILVER." + tabela + " WHERE lote_ingestao_id = ?", Long.class, loteId);

        List<RegistroCamadaDto> itens = linhas.stream().map(this::paraDtoSilver).toList();
        return new PaginaDto<>(itens, total != null ? total : 0L, pagina, tamanho);
    }

    /**
     * Registro correspondente na Silver, ou {@code null} se esse {@code registroBrutoId}
     * nunca virou linha lá — cenário legítimo (ex.: perdeu uma deduplicação dentro do
     * próprio lote), não é erro.
     */
    public RegistroCamadaDto buscarRegistroSilver(Long registroBrutoId) {
        RegistroBruto bronze = registroBrutoRepository.findById(registroBrutoId)
                .orElseThrow(() -> new IllegalArgumentException("Registro bruto " + registroBrutoId + " não encontrado"));
        FonteDado fonte = fonteDadoRepository.findById(bronze.getFonteDadoId())
                .orElseThrow(() -> new IllegalArgumentException("Fonte de dado " + bronze.getFonteDadoId() + " não encontrada"));
        String tabela = resolverTabelaSilver(fonte);

        String sql = "SELECT " + colunasSelecionaveis(tabela) + " FROM SILVER." + tabela + " t"
                + joinsAdicionais(tabela) + " WHERE t.registro_bruto_id = ?";
        List<Map<String, Object>> linhas = jdbcTemplate.queryForList(sql, registroBrutoId);
        return linhas.isEmpty() ? null : paraDtoSilver(linhas.get(0));
    }

    private RegistroCamadaDto paraDtoBronze(RegistroBruto registro) {
        Map<String, Object> dados = parsearPayload(registro.getPayload());
        boolean temGeometria = registro.getGeometriaWkt() != null;
        return new RegistroCamadaDto(registro.getId(), temGeometria, registro.getGeometriaWkt(), dados);
    }

    private RegistroCamadaDto paraDtoSilver(Map<String, Object> linha) {
        Map<String, Object> dados = new LinkedHashMap<>(linha);
        Object geometriaWkt = dados.remove("GEOMETRIA_WKT");
        Object registroBrutoIdBruto = dados.get("REGISTRO_BRUTO_ID");
        Long registroBrutoId = registroBrutoIdBruto instanceof Number numero ? numero.longValue() : null;
        boolean temGeometria = geometriaWkt != null;
        return new RegistroCamadaDto(registroBrutoId, temGeometria, temGeometria ? geometriaWkt.toString() : null, dados);
    }

    private Map<String, Object> parsearPayload(String payloadJson) {
        try {
            return objectMapper.readValue(payloadJson, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("Falha ao interpretar payload do registro bruto", e);
        }
    }

    /**
     * Reconhece a entidade automaticamente consultando o catálogo do Oracle (mesma
     * ideia de {@link #montarInfoTabela}), em vez de manter uma lista fixa de nomes
     * conhecidos — uma tabela Silver nova passa a funcionar aqui sem precisar lembrar
     * de atualizar este arquivo (achado ao vivo: a lista estática ficou pra trás
     * quando app_declarada/reserva_legal/vegetacao_nativa_declarada foram criadas).
     * O regex é defesa extra contra SQL injection, já que {@code tabela} vira literal
     * concatenado no SQL de {@link #colunasSelecionaveis}/{@link #joinsAdicionais}
     * (nome de tabela não pode ser bind variable) — mesmo padrão de
     * {@code ValidadorIntegridadeCruzada}.
     */
    private String resolverTabelaSilver(FonteDado fonte) {
        String entidade = transicaoService.resolverEntidadeSilver(fonte);
        if (entidade == null || !entidade.matches("[a-z_]+") || !tabelaSilverExiste(entidade)) {
            throw new IllegalStateException("Entidade Silver desconhecida pra fonte \"" + fonte.getNome() + "\": " + entidade);
        }
        return entidade;
    }

    private boolean tabelaSilverExiste(String tabela) {
        Integer total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM all_tables WHERE owner = 'SILVER' AND table_name = ?",
                Integer.class, tabela.toUpperCase(Locale.ROOT));
        return total != null && total > 0;
    }

    /**
     * Monta a lista de colunas pra um {@code SELECT} genérico contra uma tabela Silver:
     * {@code SDO_GEOMETRY} vira WKT via {@code SDO_UTIL.TO_WKTGEOMETRY} (devolver a
     * STRUCT bruta pro Jackson serializar não funciona), {@code JSON} nativo do Oracle
     * precisa de {@code CAST ... AS VARCHAR2} pelo mesmo motivo (mesmo tratamento que
     * {@code QuarentenaService} já dá pra {@code payload_original}), e toda coluna que
     * for FK de verdade (constraint declarada no banco, não as de linhagem
     * fonte_dado_id/lote_ingestao_id/registro_bruto_id, que são cross-schema sem FK)
     * vira o valor legível da tabela referenciada via LEFT JOIN, não o id numérico cru.
     * Resultado cacheado por tabela, já que o schema não muda em runtime.
     */
    private String colunasSelecionaveis(String tabela) {
        return String.join(", ", infoTabela(tabela).expressoesColuna());
    }

    private String joinsAdicionais(String tabela) {
        List<String> joins = infoTabela(tabela).joins();
        return joins.isEmpty() ? "" : " " + String.join(" ", joins);
    }

    private InfoTabelaSilver infoTabela(String tabela) {
        return cacheInfoTabela.computeIfAbsent(tabela, this::montarInfoTabela);
    }

    private InfoTabelaSilver montarInfoTabela(String tabela) {
        List<Map<String, Object>> colunas = jdbcTemplate.queryForList(
                "SELECT column_name, data_type FROM all_tab_columns "
                        + "WHERE owner = 'SILVER' AND table_name = ? ORDER BY column_id",
                tabela.toUpperCase(Locale.ROOT));
        if (colunas.isEmpty()) {
            throw new IllegalStateException("Tabela SILVER." + tabela + " não encontrada ou sem colunas visíveis");
        }
        Map<String, String> chavesEstrangeiras = buscarChavesEstrangeiras(tabela);

        List<String> expressoes = new ArrayList<>();
        List<String> joins = new ArrayList<>();
        int aliasSequencial = 0;
        for (Map<String, Object> coluna : colunas) {
            String nome = (String) coluna.get("COLUMN_NAME");
            String tipo = (String) coluna.get("DATA_TYPE");
            String tabelaReferenciada = chavesEstrangeiras.get(nome);
            String colunaExibicao = tabelaReferenciada != null ? colunaDeExibicao(tabelaReferenciada) : null;

            if (colunaExibicao != null) {
                String alias = "r" + (aliasSequencial++);
                joins.add("LEFT JOIN SILVER." + tabelaReferenciada + " " + alias + " ON " + alias + ".id = t." + nome);
                expressoes.add(alias + "." + colunaExibicao + " AS " + nome);
            } else if ("SDO_GEOMETRY".equals(tipo)) {
                expressoes.add("SDO_UTIL.TO_WKTGEOMETRY(t." + nome + ") AS GEOMETRIA_WKT");
            } else if ("JSON".equals(tipo)) {
                expressoes.add("CAST(t." + nome + " AS VARCHAR2(4000)) AS " + nome);
            } else {
                expressoes.add("t." + nome);
            }
        }
        return new InfoTabelaSilver(expressoes, joins);
    }

    /**
     * FKs declaradas de verdade no schema SILVER pra uma tabela (ex.: {@code
     * situacao_imovel_id -> situacao_imovel}) — as colunas de linhagem
     * (fonte_dado_id/lote_ingestao_id/registro_bruto_id) são cross-schema e não têm
     * constraint, então nunca aparecem aqui, sobrevivem como número cru mesmo.
     */
    private Map<String, String> buscarChavesEstrangeiras(String tabela) {
        List<Map<String, Object>> linhas = jdbcTemplate.queryForList(
                "SELECT a.column_name AS fk_coluna, c_pk.table_name AS tabela_ref "
                        + "FROM all_cons_columns a "
                        + "JOIN all_constraints c ON a.constraint_name = c.constraint_name AND a.owner = c.owner "
                        + "JOIN all_constraints c_pk ON c.r_constraint_name = c_pk.constraint_name AND c.r_owner = c_pk.owner "
                        + "JOIN all_cons_columns a_pk ON c_pk.constraint_name = a_pk.constraint_name AND c_pk.owner = a_pk.owner "
                        + "WHERE c.constraint_type = 'R' AND c.owner = 'SILVER' AND c.table_name = ?",
                tabela.toUpperCase(Locale.ROOT));
        Map<String, String> mapa = new LinkedHashMap<>();
        for (Map<String, Object> linha : linhas) {
            mapa.put((String) linha.get("FK_COLUNA"), ((String) linha.get("TABELA_REF")).toLowerCase(Locale.ROOT));
        }
        return mapa;
    }

    /**
     * Qual coluna da tabela referenciada mostrar no lugar do id: prioridade
     * descricao > nome > codigo (ex.: tabelas de domínio como situacao_imovel só têm
     * codigo/descricao; municipio, que também é referenciada, só tem nome). Sem
     * nenhuma dessas, mantém o id cru — não força join que não ajudaria em nada.
     */
    private String colunaDeExibicao(String tabelaReferenciada) {
        List<String> colunas = jdbcTemplate.queryForList(
                "SELECT column_name FROM all_tab_columns WHERE owner = 'SILVER' AND table_name = ?",
                String.class, tabelaReferenciada.toUpperCase(Locale.ROOT));
        if (colunas.contains("DESCRICAO")) return "DESCRICAO";
        if (colunas.contains("NOME")) return "NOME";
        if (colunas.contains("CODIGO")) return "CODIGO";
        return null;
    }
}
