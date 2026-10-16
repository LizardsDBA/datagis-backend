package br.gov.sp.cps.datagis.conjuntosdados;

import br.gov.sp.cps.datagis.bronze.RegistroBruto;
import br.gov.sp.cps.datagis.bronze.RegistroBrutoRepository;
import br.gov.sp.cps.datagis.conjuntosdados.ComparacaoCamadasDto.DiferencaCampo;
import br.gov.sp.cps.datagis.controle.MapeamentoColuna;
import br.gov.sp.cps.datagis.controle.MapeamentoColunaRepository;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Diff de um mesmo registro entre camadas vizinhas, na tela de Conjunto de Dados:
 * Bronze→Silver (alinhado pelo de-para de {@code controle.mapeamento_coluna}) e
 * Silver→Gold (só imóvel rural, único fenômeno que a Gold publica registro a registro).
 * Usa {@link ConjuntosDadosService} pra ler cada camada, então herda o mesmo tratamento
 * de FKs e geometria que a tela já exibe.
 */
@Service
public class ComparacaoCamadasService {

    public static final String BRONZE = "BRONZE";
    public static final String SILVER = "SILVER";
    public static final String GOLD = "GOLD";

    private static final String COLUNA_COD_IMOVEL = "COD_IMOVEL";
    private static final List<String> INDICADORES_GOLD = List.of(
            "VALOR_ICV", "VALOR_IRL", "VALOR_IAPP", "VALOR_ISAP", "VALOR_IAE", "VALOR_IDESMAT", "VALOR_IFC");

    private final ConjuntosDadosService conjuntosDadosService;
    private final RegistroBrutoRepository registroBrutoRepository;
    private final MapeamentoColunaRepository mapeamentoColunaRepository;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ComparacaoCamadasService(
            ConjuntosDadosService conjuntosDadosService,
            RegistroBrutoRepository registroBrutoRepository,
            MapeamentoColunaRepository mapeamentoColunaRepository,
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper) {
        this.conjuntosDadosService = conjuntosDadosService;
        this.registroBrutoRepository = registroBrutoRepository;
        this.mapeamentoColunaRepository = mapeamentoColunaRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public ComparacaoCamadasDto comparar(Long registroBrutoId, String origem, String destino) {
        String de = origem == null ? "" : origem.toUpperCase(Locale.ROOT);
        String para = destino == null ? "" : destino.toUpperCase(Locale.ROOT);
        if (BRONZE.equals(de) && SILVER.equals(para)) return compararBronzeSilver(registroBrutoId);
        if (SILVER.equals(de) && GOLD.equals(para)) return compararSilverGold(registroBrutoId);
        throw new IllegalArgumentException(
                "Comparação " + origem + " → " + destino + " não suportada: use BRONZE→SILVER ou SILVER→GOLD");
    }

    private ComparacaoCamadasDto compararBronzeSilver(Long registroBrutoId) {
        RegistroBruto registroBruto = registroBrutoRepository.findById(registroBrutoId)
                .orElseThrow(() -> new IllegalArgumentException("Registro bruto " + registroBrutoId + " não encontrado"));
        RegistroCamadaDto bronze = conjuntosDadosService.buscarRegistroBronze(registroBrutoId);
        RegistroCamadaDto silver = conjuntosDadosService.buscarRegistroSilver(registroBrutoId);
        if (silver == null) {
            return ComparacaoCamadasDto.semDestino(registroBrutoId, BRONZE, SILVER,
                    "Este registro não chegou a virar uma linha na Silver (pode ter ido para a Quarentena "
                            + "ou perdido uma deduplicação dentro do próprio lote).");
        }

        List<DiferencaCampo> campos = new ArrayList<>();
        Set<String> colunasMapeadas = new HashSet<>();
        for (MapeamentoColuna mapeamento : mapeamentoColunaRepository.findByFonteDadoId(registroBruto.getFonteDadoId())) {
            colunasMapeadas.add(mapeamento.getColunaOrigem());
            boolean geometria = "GEOMETRIA".equals(mapeamento.getDestinoTipo());
            Object valorOrigem = geometria ? bronze.geometriaWkt() : bronze.dados().get(mapeamento.getColunaOrigem());
            Object valorDestino = valorNaSilver(silver, mapeamento, geometria);
            boolean iguais = geometria
                    ? ComparadorValores.geometriasIguais(textoOuNulo(valorOrigem), textoOuNulo(valorDestino))
                    : ComparadorValores.iguais(valorOrigem, valorDestino);
            campos.add(new DiferencaCampo(
                    rotuloCampo(mapeamento), resumirGeometria(valorOrigem, geometria), resumirGeometria(valorDestino, geometria),
                    iguais ? ComparacaoCamadasDto.IGUAL : ComparacaoCamadasDto.DIFERENTE));
        }

        // Campo do payload que nenhum mapeamento leva pra Silver: aparece só do lado da origem.
        bronze.dados().forEach((coluna, valor) -> {
            if (!colunasMapeadas.contains(coluna)) {
                campos.add(new DiferencaCampo(coluna, valor, null, ComparacaoCamadasDto.SO_ORIGEM));
            }
        });
        return ComparacaoCamadasDto.de(registroBrutoId, BRONZE, SILVER, campos);
    }

    private ComparacaoCamadasDto compararSilverGold(Long registroBrutoId) {
        RegistroCamadaDto silver = conjuntosDadosService.buscarRegistroSilver(registroBrutoId);
        if (silver == null) {
            return ComparacaoCamadasDto.semDestino(registroBrutoId, SILVER, GOLD,
                    "Este registro não chegou na Silver, então não há o que comparar com a Gold.");
        }
        Object codImovel = silver.dados().get(COLUNA_COD_IMOVEL);
        if (codImovel == null) {
            return ComparacaoCamadasDto.semDestino(registroBrutoId, SILVER, GOLD,
                    "A Gold publica registro a registro só para imóvel rural; esta entidade entra na Gold "
                            + "apenas dentro dos cálculos e camadas de mapa.");
        }

        List<Map<String, Object>> publicados = jdbcTemplate.queryForList(
                "SELECT area_ha, municipio_cod_ibge, SDO_UTIL.TO_WKTGEOMETRY(geometria) AS geometria_wkt, "
                        + String.join(", ", INDICADORES_GOLD.stream().map(String::toLowerCase).toList())
                        + " FROM GOLD.imovel_publicado WHERE cod_imovel = ? "
                        + "ORDER BY data_publicacao DESC, id DESC FETCH FIRST 1 ROWS ONLY",
                codImovel.toString());
        if (publicados.isEmpty()) {
            return ComparacaoCamadasDto.semDestino(registroBrutoId, SILVER, GOLD,
                    "O imóvel " + codImovel + " ainda não foi publicado na Gold.");
        }
        Map<String, Object> gold = publicados.get(0);

        List<DiferencaCampo> campos = new ArrayList<>();
        campos.add(campoSimples("area_ha", silver.dados().get("AREA_HA"), gold.get("AREA_HA")));
        campos.add(campoSimples("municipio_cod_ibge", codigoIbgeDoMunicipio(codImovel.toString()), gold.get("MUNICIPIO_COD_IBGE")));
        String wktSilver = silver.geometriaWkt();
        String wktGold = textoOuNulo(gold.get("GEOMETRIA_WKT"));
        campos.add(new DiferencaCampo(
                "geometria", resumirGeometria(wktSilver, true), resumirGeometria(wktGold, true),
                ComparadorValores.geometriasIguais(wktSilver, wktGold) ? ComparacaoCamadasDto.IGUAL : ComparacaoCamadasDto.DIFERENTE));
        // Indicadores só existem na Gold: são o que a camada acrescenta ao dado da Silver.
        for (String indicador : INDICADORES_GOLD) {
            campos.add(new DiferencaCampo(indicador.toLowerCase(Locale.ROOT), null, gold.get(indicador), ComparacaoCamadasDto.SO_DESTINO));
        }
        return ComparacaoCamadasDto.de(registroBrutoId, SILVER, GOLD, campos);
    }

    private DiferencaCampo campoSimples(String campo, Object valorOrigem, Object valorDestino) {
        return new DiferencaCampo(campo, valorOrigem, valorDestino,
                ComparadorValores.iguais(valorOrigem, valorDestino) ? ComparacaoCamadasDto.IGUAL : ComparacaoCamadasDto.DIFERENTE);
    }

    private Object codigoIbgeDoMunicipio(String codImovel) {
        List<Map<String, Object>> linhas = jdbcTemplate.queryForList(
                "SELECT m.cod_ibge FROM SILVER.imovel_rural i JOIN SILVER.municipio m ON m.id = i.municipio_id "
                        + "WHERE i.cod_imovel = ?", codImovel);
        return linhas.isEmpty() ? null : linhas.get(0).get("COD_IBGE");
    }

    /**
     * Coluna da Silver que recebe o mapeamento: o próprio atributo, ou {@code <atributo>_ID}
     * (FK que a tela já devolve resolvida pro texto), ou uma chave dentro de {@code atributos_origem}.
     */
    private Object valorNaSilver(RegistroCamadaDto silver, MapeamentoColuna mapeamento, boolean geometria) {
        if (geometria) return silver.geometriaWkt();
        String chave = mapeamento.getAtributoDestino().toUpperCase(Locale.ROOT);
        Map<String, Object> dados = silver.dados();
        if (dados.containsKey(chave)) return dados.get(chave);
        if (dados.containsKey(chave + "_ID")) return dados.get(chave + "_ID");
        Object atributos = dados.get("ATRIBUTOS_ORIGEM");
        if (atributos != null) {
            try {
                Map<String, Object> json = objectMapper.readValue(atributos.toString(), new TypeReference<Map<String, Object>>() {});
                return json.get(mapeamento.getAtributoDestino());
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    private static String rotuloCampo(MapeamentoColuna mapeamento) {
        return mapeamento.getColunaOrigem().equalsIgnoreCase(mapeamento.getAtributoDestino())
                ? mapeamento.getColunaOrigem()
                : mapeamento.getColunaOrigem() + " → " + mapeamento.getAtributoDestino();
    }

    private static String textoOuNulo(Object valor) {
        return valor == null ? null : valor.toString();
    }

    /** WKT de polígono tem dezenas de milhares de caracteres: a tela mostra só o início, a comparação já foi feita. */
    private static Object resumirGeometria(Object valor, boolean geometria) {
        if (!geometria || valor == null) return valor;
        String texto = valor.toString();
        return texto.length() > 120 ? texto.substring(0, 120) + "… (" + texto.length() + " caracteres)" : texto;
    }
}
