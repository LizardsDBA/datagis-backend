package br.gov.sp.cps.datagis.validacao;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Serviço responsável pelo gerenciamento e inclusão de novos valores de domínio
 * em tabelas de referência da camada Silver, com suporte a relacionamentos (ex.: áreas protegidas)
 * e invalidação imediata de cache nos motores de validação e ingestão.
 */
@Service
public class DominioService {

    private static final Logger log = LoggerFactory.getLogger(DominioService.class);

    /**
     * Whitelist estrita de tabelas de domínio permitidas para inserção dinâmica de termos.
     * OBS: 'unidade_federativa' está excluída por possuir valores oficiais estáticos.
     * Tabelas canônicas/fatos (imovel_rural, municipio, auto_infracao, etc.) são proibidas.
     */
    public static final Set<String> TABELAS_PERMITIDAS = Set.of(
            "situacao_imovel",
            "condicao_imovel",
            "tipo_imovel",
            "tipo_area_protegida",
            "categoria_area_protegida",
            "orgao",
            "bioma",
            "satelite",
            "sensor",
            "tipo_area_embargo",
            "tipo_auto_infracao",
            "tipo_infracao",
            "tipo_pessoa",
            "status_auto_infracao",
            "classe_prodes"
    );

    private final JdbcTemplate jdbcTemplate;
    private final ValidadorDominio validadorDominio;
    private final PersistidorSilverService persistidorSilverService;
    private final String silverSchema;
    private final String quarentenaSchema;

    @Autowired
    public DominioService(
            JdbcTemplate jdbcTemplate,
            ValidadorDominio validadorDominio,
            PersistidorSilverService persistidorSilverService,
            @Value("${spring.flyway.placeholders.silverSchema:SILVER}") String silverSchema,
            @Value("${spring.flyway.placeholders.quarentenaSchema:QUARENTENA}") String quarentenaSchema) {
        this.jdbcTemplate = jdbcTemplate;
        this.validadorDominio = validadorDominio;
        this.persistidorSilverService = persistidorSilverService;
        this.silverSchema = (silverSchema != null && !silverSchema.isBlank()) ? silverSchema.trim() : "SILVER";
        this.quarentenaSchema = (quarentenaSchema != null && !quarentenaSchema.isBlank()) ? quarentenaSchema.trim() : "QUARENTENA";
    }

    /**
     * Adiciona um novo valor de domínio ou reativa um existente na tabela informada.
     */
    @Transactional
    public Map<String, Object> adicionarValorDominio(NovoDominioRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Requisição de adição de domínio não pode ser nula.");
        }

        String tabelaRaw = request.tabela();
        if (tabelaRaw == null || tabelaRaw.isBlank()) {
            throw new IllegalArgumentException("Nome da tabela de referência é obrigatório.");
        }

        String tab = ValidadorDominio.normalizarAliasTabela(tabelaRaw);
        if ("unidade_federativa".equals(tab)) {
            throw new IllegalArgumentException("A tabela 'unidade_federativa' possui valores oficiais estáticos e não permite inserção dinâmica.");
        }

        if (!TABELAS_PERMITIDAS.contains(tab)) {
            throw new IllegalArgumentException("A tabela informada ('" + tab + "') não é uma tabela de referência de domínio autorizada.");
        }

        String descricao = request.descricao() != null ? request.descricao().trim() : "";
        if (descricao.isEmpty()) {
            throw new IllegalArgumentException("A descrição do novo valor de domínio não pode ser vazia.");
        }

        String codigo = (request.codigo() != null && !request.codigo().isBlank())
                ? normalizarCodigo(request.codigo())
                : gerarCodigoPadrao(descricao);

        Map<String, Object> resposta = new LinkedHashMap<>();
        resposta.put("tabela", tab);
        resposta.put("codigo", codigo);
        resposta.put("descricao", descricao);

        if ("categoria_area_protegida".equals(tab)) {
            tratarCategoriaAreaProtegida(request, codigo, descricao, resposta);
        } else if ("tipo_area_protegida".equals(tab)) {
            tratarTipoAreaProtegida(request, codigo, descricao, resposta);
        } else {
            tratarTabelaPadrao(tab, codigo, descricao, resposta);
        }

        // Invalida os caches em memória de validação e persistência
        invalidarCaches(tab);

        return resposta;
    }

    /**
     * Lista todos os tipos de áreas protegidas ativos cadastrados na camada Silver.
     */
    public List<Map<String, Object>> listarTiposAreaProtegida() {
        String sql = "SELECT id, codigo, descricao FROM " + silverSchema + ".tipo_area_protegida WHERE ativo = 1 ORDER BY descricao ASC";
        try {
            return jdbcTemplate.queryForList(sql);
        } catch (Exception e) {
            log.warn("Erro ao listar tipos de área protegida: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    // -------------------------------------------------------------------------
    // Lógicas Especializadas para Áreas Protegidas
    // -------------------------------------------------------------------------

    private void tratarCategoriaAreaProtegida(
            NovoDominioRequest request,
            String codigoCategoria,
            String descricaoCategoria,
            Map<String, Object> resposta) {

        Long tipoId = null;

        // 1. Se o operador selecionou um tipo existente pelo ID:
        if (request.tipoAreaProtegidaId() != null) {
            try {
                String sql = "SELECT id FROM " + silverSchema + ".tipo_area_protegida WHERE id = ?";
                List<Long> ids = jdbcTemplate.query(sql, (rs, rowNum) -> rs.getLong("id"), request.tipoAreaProtegidaId());
                if (!ids.isEmpty()) {
                    tipoId = ids.get(0);
                }
            } catch (Exception e) {
                log.debug("Aviso ao buscar tipo_area_protegida_id={}: {}", request.tipoAreaProtegidaId(), e.getMessage());
            }
        }

        // 2. Se o operador optou por cadastrar um NOVO tipo de área protegida:
        if (tipoId == null && Boolean.TRUE.equals(request.criarNovoTipo())) {
            String descNovoTipo = request.novoTipoAreaProtegidaDescricao() != null && !request.novoTipoAreaProtegidaDescricao().isBlank()
                    ? request.novoTipoAreaProtegidaDescricao().trim()
                    : (request.tipoAreaProtegida() != null ? request.tipoAreaProtegida().trim() : "Novo Tipo");
            String codNovoTipo = request.novoTipoAreaProtegidaCodigo() != null && !request.novoTipoAreaProtegidaCodigo().isBlank()
                    ? normalizarCodigo(request.novoTipoAreaProtegidaCodigo())
                    : gerarCodigoPadrao(descNovoTipo);
            tipoId = criarOuAtualizarTipoAreaProtegida(codNovoTipo, descNovoTipo, resposta);
        }

        // 3. Fallback: Se não especificou ID nem flag de novo tipo, busca pelo texto ou cria
        if (tipoId == null) {
            String tipoInformado = extrairTipoAreaProtegida(request);
            if (tipoInformado == null || tipoInformado.isBlank()) {
                tipoInformado = "UC_USO_SUSTENTAVEL"; // Fallback padrão caso não haja tipo informado
            }
            tipoId = obterOuCriarTipoAreaProtegida(tipoInformado, resposta);
        }

        // 4. Inserir ou atualizar a categoria vinculada
        String sqlBusca = "SELECT id, ativo, tipo_area_protegida_id FROM " + silverSchema + ".categoria_area_protegida WHERE UPPER(codigo) = ?";
        List<Map<String, Object>> existentes = jdbcTemplate.queryForList(sqlBusca, codigoCategoria.toUpperCase(Locale.ROOT));

        Long categoriaId;
        if (!existentes.isEmpty()) {
            Map<String, Object> row = existentes.get(0);
            categoriaId = ((Number) row.get("ID")).longValue();
            String sqlUpdate = "UPDATE " + silverSchema + ".categoria_area_protegida " +
                               "SET ativo = 1, tipo_area_protegida_id = ?, descricao = COALESCE(?, descricao) WHERE id = ?";
            jdbcTemplate.update(sqlUpdate, tipoId, descricaoCategoria, categoriaId);
            resposta.put("status", "ATUALIZADO");
        } else {
            String sqlInsert = "INSERT INTO " + silverSchema + ".categoria_area_protegida " +
                               "(codigo, descricao, tipo_area_protegida_id, ativo, data_add) " +
                               "VALUES (?, ?, ?, 1, SYSTIMESTAMP)";
            jdbcTemplate.update(sqlInsert, codigoCategoria, descricaoCategoria, tipoId);
            categoriaId = jdbcTemplate.queryForObject(
                    "SELECT id FROM " + silverSchema + ".categoria_area_protegida WHERE UPPER(codigo) = ?",
                    Long.class,
                    codigoCategoria.toUpperCase(Locale.ROOT));
            resposta.put("status", "CRIADO");
        }

        resposta.put("id", categoriaId);
        resposta.put("tipo_area_protegida_id", tipoId);
        invalidarCaches("tipo_area_protegida");
    }

    private Long criarOuAtualizarTipoAreaProtegida(String codigoTipo, String descricaoTipo, Map<String, Object> resposta) {
        String sqlBusca = "SELECT id, ativo FROM " + silverSchema + ".tipo_area_protegida WHERE UPPER(codigo) = ?";
        List<Map<String, Object>> existentes = jdbcTemplate.queryForList(sqlBusca, codigoTipo.toUpperCase(Locale.ROOT));

        Long tipoId;
        if (!existentes.isEmpty()) {
            tipoId = ((Number) existentes.get(0).get("ID")).longValue();
            jdbcTemplate.update("UPDATE " + silverSchema + ".tipo_area_protegida SET ativo = 1, descricao = COALESCE(?, descricao) WHERE id = ?",
                    descricaoTipo, tipoId);
            resposta.put("tipo_status", "ATUALIZADO");
        } else {
            jdbcTemplate.update("INSERT INTO " + silverSchema + ".tipo_area_protegida (codigo, descricao, ativo, data_add) VALUES (?, ?, 1, SYSTIMESTAMP)",
                    codigoTipo, descricaoTipo);
            tipoId = jdbcTemplate.queryForObject("SELECT id FROM " + silverSchema + ".tipo_area_protegida WHERE UPPER(codigo) = ?",
                    Long.class, codigoTipo.toUpperCase(Locale.ROOT));
            resposta.put("tipo_status", "CRIADO");
        }
        resposta.put("tipo_area_protegida_id", tipoId);
        return tipoId;
    }

    private void tratarTipoAreaProtegida(
            NovoDominioRequest request,
            String codigoTipo,
            String descricaoTipo,
            Map<String, Object> resposta) {

        Long tipoId;
        String sqlBusca = "SELECT id, ativo FROM " + silverSchema + ".tipo_area_protegida WHERE UPPER(codigo) = ?";
        List<Map<String, Object>> existentes = jdbcTemplate.queryForList(sqlBusca, codigoTipo.toUpperCase(Locale.ROOT));

        if (!existentes.isEmpty()) {
            tipoId = ((Number) existentes.get(0).get("ID")).longValue();
            jdbcTemplate.update("UPDATE " + silverSchema + ".tipo_area_protegida SET ativo = 1, descricao = COALESCE(?, descricao) WHERE id = ?",
                    descricaoTipo, tipoId);
            resposta.put("status", "ATUALIZADO");
        } else {
            jdbcTemplate.update("INSERT INTO " + silverSchema + ".tipo_area_protegida (codigo, descricao, ativo, data_add) VALUES (?, ?, 1, SYSTIMESTAMP)",
                    codigoTipo, descricaoTipo);
            tipoId = jdbcTemplate.queryForObject("SELECT id FROM " + silverSchema + ".tipo_area_protegida WHERE UPPER(codigo) = ?",
                    Long.class, codigoTipo.toUpperCase(Locale.ROOT));
            resposta.put("status", "CRIADO");
        }
        resposta.put("id", tipoId);

        // Se houver uma categoria_area_protegida associada no mesmo registro:
        String catInformada = extrairCategoriaAreaProtegida(request);
        if (catInformada != null && !catInformada.isBlank()) {
            String codCat = gerarCodigoPadrao(catInformada);
            String sqlBuscaCat = "SELECT id FROM " + silverSchema + ".categoria_area_protegida WHERE UPPER(codigo) = ?";
            List<Long> idsCat = jdbcTemplate.query(sqlBuscaCat, (rs, rowNum) -> rs.getLong("id"), codCat.toUpperCase(Locale.ROOT));

            if (!idsCat.isEmpty()) {
                jdbcTemplate.update("UPDATE " + silverSchema + ".categoria_area_protegida SET tipo_area_protegida_id = ?, ativo = 1 WHERE id = ?",
                        tipoId, idsCat.get(0));
                resposta.put("categoria_vinculada_id", idsCat.get(0));
            } else {
                jdbcTemplate.update("INSERT INTO " + silverSchema + ".categoria_area_protegida (codigo, descricao, tipo_area_protegida_id, ativo, data_add) VALUES (?, ?, ?, 1, SYSTIMESTAMP)",
                        codCat, catInformada, tipoId);
                Long novaCatId = jdbcTemplate.queryForObject("SELECT id FROM " + silverSchema + ".categoria_area_protegida WHERE UPPER(codigo) = ?",
                        Long.class, codCat.toUpperCase(Locale.ROOT));
                resposta.put("categoria_vinculada_id", novaCatId);
            }
            invalidarCaches("categoria_area_protegida");
        }
    }

    private Long obterOuCriarTipoAreaProtegida(String tipoInformado, Map<String, Object> resposta) {
        String codTipo = normalizarCodigo(tipoInformado);
        String sqlBusca = "SELECT id FROM " + silverSchema + ".tipo_area_protegida WHERE UPPER(codigo) = ? OR UPPER(descricao) = ?";
        List<Long> ids = jdbcTemplate.query(sqlBusca, (rs, rowNum) -> rs.getLong("id"),
                codTipo.toUpperCase(Locale.ROOT), tipoInformado.toUpperCase(Locale.ROOT));

        if (!ids.isEmpty()) {
            return ids.get(0);
        }

        // Tenta resolver por sinônimos estáticos conhecidos
        String sinonimo = ValidadorDominio.resolverSinonimosConhecidosEstatico(tipoInformado, "tipo_area_protegida");
        if (sinonimo != null) {
            List<Long> idsSin = jdbcTemplate.query(sqlBusca, (rs, rowNum) -> rs.getLong("id"),
                    sinonimo.toUpperCase(Locale.ROOT), sinonimo.toUpperCase(Locale.ROOT));
            if (!idsSin.isEmpty()) {
                return idsSin.get(0);
            }
            codTipo = sinonimo;
        }

        // Não existe: cria automaticamente o tipo_area_protegida
        jdbcTemplate.update("INSERT INTO " + silverSchema + ".tipo_area_protegida (codigo, descricao, ativo, data_add) VALUES (?, ?, 1, SYSTIMESTAMP)",
                codTipo, tipoInformado);
        Long novoId = jdbcTemplate.queryForObject("SELECT id FROM " + silverSchema + ".tipo_area_protegida WHERE UPPER(codigo) = ?",
                Long.class, codTipo.toUpperCase(Locale.ROOT));
        resposta.put("tipo_auto_criado_id", novoId);
        resposta.put("tipo_auto_criado_codigo", codTipo);
        return novoId;
    }

    private String extrairTipoAreaProtegida(NovoDominioRequest request) {
        if (request.tipoAreaProtegida() != null && !request.tipoAreaProtegida().isBlank()) {
            return request.tipoAreaProtegida().trim();
        }
        if (request.quarentenaItemId() != null && "area_protegida".equalsIgnoreCase(request.entidade())) {
            try {
                String sql = "SELECT tipo_area_protegida FROM " + quarentenaSchema + ".q_area_protegida WHERE id = ?";
                List<String> list = jdbcTemplate.query(sql, (rs, rowNum) -> rs.getString("tipo_area_protegida"), request.quarentenaItemId());
                if (!list.isEmpty() && list.get(0) != null && !list.get(0).isBlank()) {
                    return list.get(0).trim();
                }
            } catch (Exception e) {
                log.debug("Aviso ao buscar tipo_area_protegida da quarentena: {}", e.getMessage());
            }
        }
        return null;
    }

    private String extrairCategoriaAreaProtegida(NovoDominioRequest request) {
        if (request.categoriaAreaProtegida() != null && !request.categoriaAreaProtegida().isBlank()) {
            return request.categoriaAreaProtegida().trim();
        }
        if (request.quarentenaItemId() != null && "area_protegida".equalsIgnoreCase(request.entidade())) {
            try {
                String sql = "SELECT categoria_area_protegida FROM " + quarentenaSchema + ".q_area_protegida WHERE id = ?";
                List<String> list = jdbcTemplate.query(sql, (rs, rowNum) -> rs.getString("categoria_area_protegida"), request.quarentenaItemId());
                if (!list.isEmpty() && list.get(0) != null && !list.get(0).isBlank()) {
                    return list.get(0).trim();
                }
            } catch (Exception e) {
                log.debug("Aviso ao buscar categoria_area_protegida da quarentena: {}", e.getMessage());
            }
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Lógica para Tabelas Padrão
    // -------------------------------------------------------------------------

    private void tratarTabelaPadrao(
            String tab,
            String codigo,
            String descricao,
            Map<String, Object> resposta) {

        String sqlBusca = "SELECT id, ativo FROM " + silverSchema + "." + tab + " WHERE UPPER(codigo) = ?";
        List<Map<String, Object>> existentes = jdbcTemplate.queryForList(sqlBusca, codigo.toUpperCase(Locale.ROOT));

        Long id;
        if (!existentes.isEmpty()) {
            Map<String, Object> row = existentes.get(0);
            id = ((Number) row.get("ID")).longValue();
            jdbcTemplate.update("UPDATE " + silverSchema + "." + tab + " SET ativo = 1, descricao = COALESCE(?, descricao) WHERE id = ?",
                    descricao, id);
            resposta.put("status", "ATUALIZADO");
        } else {
            jdbcTemplate.update("INSERT INTO " + silverSchema + "." + tab + " (codigo, descricao, ativo, data_add) VALUES (?, ?, 1, SYSTIMESTAMP)",
                    codigo, descricao);
            id = jdbcTemplate.queryForObject("SELECT id FROM " + silverSchema + "." + tab + " WHERE UPPER(codigo) = ?",
                    Long.class, codigo.toUpperCase(Locale.ROOT));
            resposta.put("status", "CRIADO");
        }
        resposta.put("id", id);
    }

    /**
     * Invalida os caches de memória nos componentes correspondentes.
     */
    public void invalidarCaches(String tabela) {
        if (tabela == null || tabela.isBlank()) return;
        try {
            if (validadorDominio != null) {
                validadorDominio.invalidarCache(tabela);
            }
            if (persistidorSilverService != null) {
                persistidorSilverService.invalidarCache(tabela);
            }
            log.info("Caches de domínio invalidados para tabela: {}", tabela);
        } catch (Exception e) {
            log.warn("Erro ao invalidar cache para tabela {}: {}", tabela, e.getMessage());
        }
    }

    /**
     * Gera um código padronizado (slug em maiúsculas sem acentos) a partir de um texto.
     */
    public static String gerarCodigoPadrao(String texto) {
        if (texto == null || texto.isBlank()) {
            return "NOVO_ITEM";
        }
        String norm = ValidadorDominio.normalizarTexto(texto);
        String slug = norm.replaceAll("[^A-Z0-9]+", "_").replaceAll("^_+|_+$", "");
        if (slug.isEmpty()) {
            slug = "ITEM";
        }
        return slug.length() > 40 ? slug.substring(0, 40) : slug;
    }

    private static String normalizarCodigo(String codigo) {
        if (codigo == null || codigo.isBlank()) return "NOVO_ITEM";
        String norm = ValidadorDominio.normalizarTexto(codigo);
        String slug = norm.replaceAll("[^A-Z0-9_]+", "_").replaceAll("^_+|_+$", "");
        return slug.length() > 40 ? slug.substring(0, 40) : slug;
    }
}
