package br.gov.sp.cps.datagis.gold;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Orquestra a transição Silver→Gold chamando as procedures PL/SQL (primeiro uso de
 * {@code CallableStatement} no projeto — não existe mock para PL/SQL,
 * {@code docs/gold-procedures-calculo-publicacao.md} §3.4). O "para cada imóvel,
 * calcule e publique" fica aqui em Java, não dentro do PL/SQL — evita dependência
 * circular entre {@code pkg_gold_publicacao} e os packages {@code pkg_calculo_*}
 * (eles chamam {@code registrar_resultado} de volta), mesmo papel de orquestrador que
 * o Java já tem no resto do projeto (Quartz/{@code AgendadorInicializacaoRunner}).
 *
 * <p>Não aborta a execução inteira por causa de 1 imóvel com problema — mesmo
 * princípio da ingestão (falha é logada e a próxima segue).
 */
@Service
public class GoldPublicacaoService {

    private static final Logger log = LoggerFactory.getLogger(GoldPublicacaoService.class);

    /** Camadas semeadas em gold.mapa_camada (V018) — publicadas na mesma ordem fixa. */
    private static final List<String> CODIGOS_MAPA_CAMADA = List.of(
            "MUNICIPIO", "AREA_PROTEGIDA", "DESMATAMENTO", "EMBARGO", "IMOVEL_RURAL", "FOCO_CALOR");

    private final JdbcTemplate jdbcTemplate;

    public GoldPublicacaoService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Calcula os 4 indicadores reais (ISAP, IAE, IDesmat, IFC — ICV/IRL/IAPP são stub,
     * não fazem nada hoje) e publica o detalhe congelado para 1 imóvel.
     */
    public void recalcularImovel(String codImovel) {
        chamarCalculo("pkg_calculo_territorial", "calcular_isap", codImovel);
        chamarCalculo("pkg_calculo_territorial", "calcular_iae", codImovel);
        chamarCalculo("pkg_calculo_ambiental", "calcular_idesmat", codImovel);
        chamarCalculo("pkg_calculo_ambiental", "calcular_ifc", codImovel);
        chamarPublicarImovel(codImovel);
    }

    /**
     * Recalcula todo {@code silver.imovel_rural} e publica todas as camadas de mapa
     * ativas. Pode levar minutos num volume real — pensado pra disparo manual ou
     * agendado, não pra resposta HTTP instantânea.
     */
    public ResultadoPublicacaoTudo publicarTudo() {
        List<String> codigosImovel = jdbcTemplate.queryForList(
                "SELECT cod_imovel FROM silver.imovel_rural", String.class);

        int sucesso = 0;
        List<String> falhas = new ArrayList<>();
        for (String codImovel : codigosImovel) {
            try {
                recalcularImovel(codImovel);
                sucesso++;
            } catch (Exception e) {
                log.warn("publicarTudo: falhou pro imóvel {}: {}", codImovel, e.getMessage());
                falhas.add(codImovel);
            }
        }

        List<String> camadasPublicadas = new ArrayList<>();
        List<String> camadasComFalha = new ArrayList<>();
        for (String codigoCamada : CODIGOS_MAPA_CAMADA) {
            try {
                chamarPublicarCamadaMapa(codigoCamada);
                camadasPublicadas.add(codigoCamada);
            } catch (Exception e) {
                log.warn("publicarTudo: falhou a camada {}: {}", codigoCamada, e.getMessage());
                camadasComFalha.add(codigoCamada);
            }
        }

        return new ResultadoPublicacaoTudo(
                codigosImovel.size(), sucesso, falhas, camadasPublicadas, camadasComFalha);
    }

    private Long chamarCalculo(String pacote, String procedure, String codImovel) {
        return jdbcTemplate.execute((Connection con) -> {
            try (CallableStatement cs = con.prepareCall(
                    "{call gold." + pacote + "." + procedure + "(?, ?, ?)}")) {
                cs.setString(1, codImovel);
                cs.setNull(2, Types.NUMERIC); // p_regra_calculo_id = NULL -> usa a vigente
                cs.registerOutParameter(3, Types.NUMERIC);
                cs.execute();
                long resultado = cs.getLong(3);
                return cs.wasNull() ? null : resultado;
            }
        });
    }

    private void chamarPublicarImovel(String codImovel) {
        jdbcTemplate.execute((Connection con) -> {
            try (CallableStatement cs = con.prepareCall(
                    "{call gold.pkg_gold_publicacao.publicar_imovel(?)}")) {
                cs.setString(1, codImovel);
                cs.execute();
                return null;
            }
        });
    }

    private void chamarPublicarCamadaMapa(String codigoCamada) {
        jdbcTemplate.execute((Connection con) -> {
            try (CallableStatement cs = con.prepareCall(
                    "{call gold.pkg_gold_publicacao.publicar_camada_mapa(?)}")) {
                cs.setString(1, codigoCamada);
                cs.execute();
                return null;
            }
        });
    }

    public record ResultadoPublicacaoTudo(
            int totalImoveis,
            int imoveisPublicados,
            List<String> imoveisComFalha,
            List<String> camadasPublicadas,
            List<String> camadasComFalha
    ) {}
}
