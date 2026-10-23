package br.gov.sp.cps.datagis.gold;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consultas da tela de indicadores (LIZARDS-34). Sao leituras que cruzam
 * schemas
 * (GOLD.imovel_publicado com SILVER.municipio) e fazem agregacao, entao usam
 * JdbcTemplate
 * com SQL nativo, no mesmo padrao do DominioService e do QuarentenaService.
 *
 * <p>
 * A agregacao por municipio acontece AQUI, no banco, e nao no navegador: sao
 * 4.611
 * imoveis so no recorte de homologacao, e o calculo precisa existir num lugar
 * so para a
 * tela de tabela e a de mapa nunca mostrarem numeros diferentes para o mesmo
 * indicador.
 */
@Service
public class ConsultaIndicadoresService {

    /**
     * Imovel que cruza alguma camada de restricao do mapa de cobertura (LIZARDS-35):
     * area protegida (ISAP), embargo (IAE), desmatamento (IDESMAT) ou foco de calor
     * (IFC). Qualquer sobreposicao conta, por menor que seja - decisao da task. IRL,
     * IAPP e ICV ficam de fora: cruzar Reserva Legal, APP ou vegetacao nao e alerta.
     */
    static final String COM_INTERSECAO = "(NVL(i.valor_isap, 0) > 0 OR NVL(i.valor_iae, 0) > 0 "
            + "OR NVL(i.valor_idesmat, 0) > 0 OR NVL(i.valor_ifc, 0) > 0)";

    private final JdbcTemplate jdbcTemplate;
    private final String goldSchema;
    private final String silverSchema;

    public ConsultaIndicadoresService(
            JdbcTemplate jdbcTemplate,
            @Value("${spring.flyway.placeholders.goldSchema:GOLD}") String goldSchema,
            @Value("${spring.flyway.placeholders.silverSchema:SILVER}") String silverSchema) {
        this.jdbcTemplate = jdbcTemplate;
        this.goldSchema = goldSchema;
        this.silverSchema = silverSchema;
    }

    /** Uma linha por imovel publicado, com o nome do municipio resolvido. */
    @Transactional(readOnly = true)
    public List<ConsultaImovelResponse> listarPorImovel() {
        String sql = "SELECT i.cod_imovel, i.municipio_cod_ibge, m.nome AS municipio_nome, i.area_ha, "
                + "i.valor_icv, i.valor_irl, i.valor_iapp, i.valor_isap, "
                + "i.valor_iae, i.valor_idesmat, i.valor_ifc, i.data_publicacao "
                + "FROM " + versaoAtualDeCadaImovel() + " "
                // LEFT JOIN de proposito: imovel com municipio ainda nao semeado na Silver
                // continua aparecendo na tela, so sem o nome.
                + "LEFT JOIN " + silverSchema + ".municipio m ON m.cod_ibge = i.municipio_cod_ibge "
                + "WHERE i.ordem_versao = 1 "
                + "ORDER BY m.nome NULLS LAST, i.cod_imovel";

        return jdbcTemplate.query(sql, (rs, linha) -> new ConsultaImovelResponse(
                rs.getString("cod_imovel"),
                lerLong(rs, "municipio_cod_ibge"),
                rs.getString("municipio_nome"),
                lerDouble(rs, "area_ha"),
                lerDouble(rs, "valor_icv"),
                lerDouble(rs, "valor_irl"),
                lerDouble(rs, "valor_iapp"),
                lerDouble(rs, "valor_isap"),
                lerDouble(rs, "valor_iae"),
                lerDouble(rs, "valor_idesmat"),
                lerDouble(rs, "valor_ifc"),
                rs.getTimestamp("data_publicacao") == null
                        ? null
                        : rs.getTimestamp("data_publicacao").toLocalDateTime()));
    }

    /**
     * Uma linha por municipio, com os 7 indicadores em media ponderada pela area.
     */
    @Transactional(readOnly = true)
    public List<ConsultaMunicipioResponse> listarPorMunicipio() {
        String sql = "SELECT i.municipio_cod_ibge AS cod_ibge, MAX(m.nome) AS nome, "
                + "COUNT(*) AS qtd_imoveis, "
                + "SUM(CASE WHEN " + COM_INTERSECAO + " THEN 1 ELSE 0 END) AS qtd_imoveis_com_intersecao, "
                + "ROUND(SUM(NVL(i.area_ha, 0)), 2) AS area_total_ha, "
                + mediaPonderada("valor_icv") + ", "
                + mediaPonderada("valor_irl") + ", "
                + mediaPonderada("valor_iapp") + ", "
                + mediaPonderada("valor_isap") + ", "
                + mediaPonderada("valor_iae") + ", "
                + mediaPonderada("valor_idesmat") + ", "
                + mediaPonderada("valor_ifc") + " "
                + "FROM " + versaoAtualDeCadaImovel() + " "
                + "LEFT JOIN " + silverSchema + ".municipio m ON m.cod_ibge = i.municipio_cod_ibge "
                + "WHERE i.ordem_versao = 1 "
                + "GROUP BY i.municipio_cod_ibge "
                + "ORDER BY MAX(m.nome) NULLS LAST";

        return jdbcTemplate.query(sql, (rs, linha) -> new ConsultaMunicipioResponse(
                lerLong(rs, "cod_ibge"),
                rs.getString("nome"),
                rs.getLong("qtd_imoveis"),
                rs.getLong("qtd_imoveis_com_intersecao"),
                lerDouble(rs, "area_total_ha"),
                lerDouble(rs, "valor_icv"),
                lerDouble(rs, "valor_irl"),
                lerDouble(rs, "valor_iapp"),
                lerDouble(rs, "valor_isap"),
                lerDouble(rs, "valor_iae"),
                lerDouble(rs, "valor_idesmat"),
                lerDouble(rs, "valor_ifc")));
    }

    /**
     * {@code gold.imovel_publicado} numerado por versao: a mais recente de cada imovel
     * recebe {@code ordem_versao = 1}.
     *
     * <p>
     * A tabela guarda uma linha por versao publicada (a chave e cod_imovel +
     * indicador_versao_id): republicar um imovel com dado novo cria uma linha e mantem
     * a antiga, que e o historico de auditoria. A tela mostra o estado atual, entao
     * quem consulta filtra {@code ordem_versao = 1}; sem isso o imovel aparece uma vez
     * por versao e a media do municipio soma a area dele em dobro. Mesmo criterio da
     * comparacao Silver -> Gold (ComparacaoCamadasService): data de publicacao, e o id
     * para desempatar.
     */
    private String versaoAtualDeCadaImovel() {
        return "(SELECT p.*, ROW_NUMBER() OVER (PARTITION BY p.cod_imovel "
                + "ORDER BY p.data_publicacao DESC, p.id DESC) AS ordem_versao "
                + "FROM " + goldSchema + ".imovel_publicado p) i";
    }

    /**
     * Media ponderada pela area de um indicador.
     *
     * <p>
     * O denominador soma a area SO dos imoveis que tem valor naquele indicador. Se
     * somasse a area de todos, um imovel sem o indicador calculado puxaria a media
     * para
     * baixo como se tivesse tirado zero — que e diferente de nao ter nota.
     *
     * <p>
     * NULLIF(..., 0) evita divisao por zero quando nenhum imovel do municipio tem
     * valor:
     * o resultado vira nulo, e a tela mostra celula vazia em vez de um zero
     * mentiroso.
     */
    private static String mediaPonderada(String coluna) {
        return "ROUND(SUM(i." + coluna + " * NVL(i.area_ha, 0)) / "
                + "NULLIF(SUM(CASE WHEN i." + coluna + " IS NOT NULL THEN NVL(i.area_ha, 0) END), 0), 2) "
                + "AS " + coluna;
    }

    /**
     * getDouble devolve 0 pra NULL; aqui NULL continua NULL (celula vazia, nao
     * zero).
     */
    private static Double lerDouble(ResultSet rs, String coluna) throws SQLException {
        double valor = rs.getDouble(coluna);
        return rs.wasNull() ? null : valor;
    }

    private static Long lerLong(ResultSet rs, String coluna) throws SQLException {
        long valor = rs.getLong(coluna);
        return rs.wasNull() ? null : valor;
    }
}