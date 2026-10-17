package br.gov.sp.cps.datagis.conjuntosdados;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.when;

import br.gov.sp.cps.datagis.bronze.RegistroBruto;
import br.gov.sp.cps.datagis.bronze.RegistroBrutoRepository;
import br.gov.sp.cps.datagis.conjuntosdados.ComparacaoCamadasDto.DiferencaCampo;
import br.gov.sp.cps.datagis.controle.MapeamentoColuna;
import br.gov.sp.cps.datagis.controle.MapeamentoColunaRepository;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class ComparacaoCamadasServiceTest {

    private static final long REGISTRO = 10L;
    private static final long FONTE = 3L;

    @Mock
    private ConjuntosDadosService conjuntosDadosService;
    @Mock
    private RegistroBrutoRepository registroBrutoRepository;
    @Mock
    private MapeamentoColunaRepository mapeamentoColunaRepository;
    @Mock
    private JdbcTemplate jdbcTemplate;

    private ComparacaoCamadasService service() {
        return new ComparacaoCamadasService(
                conjuntosDadosService, registroBrutoRepository, mapeamentoColunaRepository,
                jdbcTemplate, new ObjectMapper());
    }

    private static MapeamentoColuna mapeamento(String origem, String destino, String tipo) {
        return MapeamentoColuna.builder()
                .fonteDadoId(FONTE).colunaOrigem(origem).atributoDestino(destino).destinoTipo(tipo).build();
    }

    private static DiferencaCampo campo(ComparacaoCamadasDto dto, String nome) {
        return dto.campos().stream().filter(c -> c.campo().equals(nome)).findFirst().orElseThrow();
    }

    private void bronzeComSilver(Map<String, Object> payload, String wktBronze,
                                 Map<String, Object> silver, String wktSilver) {
        when(registroBrutoRepository.findById(REGISTRO))
                .thenReturn(Optional.of(RegistroBruto.builder().id(REGISTRO).fonteDadoId(FONTE).build()));
        when(conjuntosDadosService.buscarRegistroBronze(REGISTRO))
                .thenReturn(new RegistroCamadaDto(REGISTRO, wktBronze != null, wktBronze, payload));
        when(conjuntosDadosService.buscarRegistroSilver(REGISTRO))
                .thenReturn(new RegistroCamadaDto(REGISTRO, wktSilver != null, wktSilver, silver));
    }

    @Test
    void bronzeParaSilverAlinhaPeloMapeamentoEMarcaCadaSituacao() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("cod_imovel", "PR-1");
        payload.put("status_imovel", "AT");
        payload.put("area", "12.50");
        payload.put("tipo_extra", "so-no-bronze");
        Map<String, Object> silver = new LinkedHashMap<>();
        silver.put("COD_IMOVEL", "PR-1");
        silver.put("SITUACAO_IMOVEL_ID", "Ativo");
        silver.put("AREA_HA", new BigDecimal("12.5"));
        bronzeComSilver(payload, "POINT (1 1)", silver, "POINT(1.0 1.0)");
        when(mapeamentoColunaRepository.findByFonteDadoId(FONTE)).thenReturn(List.of(
                mapeamento("cod_imovel", "cod_imovel", "COLUNA"),
                mapeamento("status_imovel", "situacao_imovel", "COLUNA"),
                mapeamento("area", "area_ha", "COLUNA"),
                mapeamento("geometry", "geometria", "GEOMETRIA")));

        ComparacaoCamadasDto dto = service().comparar(REGISTRO, "bronze", "silver");

        assertThat(dto.destinoEncontrado()).isTrue();
        assertThat(campo(dto, "cod_imovel").situacao()).isEqualTo(ComparacaoCamadasDto.IGUAL);
        assertThat(campo(dto, "area → area_ha").situacao()).isEqualTo(ComparacaoCamadasDto.IGUAL);
        assertThat(campo(dto, "status_imovel → situacao_imovel").situacao()).isEqualTo(ComparacaoCamadasDto.DIFERENTE);
        assertThat(campo(dto, "geometry → geometria").situacao()).isEqualTo(ComparacaoCamadasDto.IGUAL);
        assertThat(campo(dto, "tipo_extra").situacao()).isEqualTo(ComparacaoCamadasDto.SO_ORIGEM);
        assertThat(dto.resumo().iguais()).isEqualTo(3);
        assertThat(dto.resumo().diferentes()).isEqualTo(1);
        assertThat(dto.resumo().soOrigem()).isEqualTo(1);
    }

    @Test
    void bronzeParaSilverLeAtributoQueMoraNoJsonDeOrigem() {
        bronzeComSilver(Map.of("nome_uc", "Parque X"), null,
                Map.of("ATRIBUTOS_ORIGEM", "{\"nome\":\"Parque X\"}"), null);
        when(mapeamentoColunaRepository.findByFonteDadoId(FONTE))
                .thenReturn(List.of(mapeamento("nome_uc", "nome", "JSON")));

        ComparacaoCamadasDto dto = service().comparar(REGISTRO, "BRONZE", "SILVER");

        assertThat(campo(dto, "nome_uc → nome").situacao()).isEqualTo(ComparacaoCamadasDto.IGUAL);
    }

    @Test
    void bronzeParaSilverSemLinhaNaSilverExplicaOMotivo() {
        when(registroBrutoRepository.findById(REGISTRO))
                .thenReturn(Optional.of(RegistroBruto.builder().id(REGISTRO).fonteDadoId(FONTE).build()));
        when(conjuntosDadosService.buscarRegistroBronze(REGISTRO))
                .thenReturn(new RegistroCamadaDto(REGISTRO, false, null, Map.of()));
        when(conjuntosDadosService.buscarRegistroSilver(REGISTRO)).thenReturn(null);

        ComparacaoCamadasDto dto = service().comparar(REGISTRO, "BRONZE", "SILVER");

        assertThat(dto.destinoEncontrado()).isFalse();
        assertThat(dto.mensagem()).contains("não chegou a virar uma linha na Silver");
        assertThat(dto.campos()).isEmpty();
    }

    @Test
    void silverParaGoldComparaAreaMunicipioEGeometriaEListaIndicadoresSoDaGold() {
        Map<String, Object> silver = new LinkedHashMap<>();
        silver.put("COD_IMOVEL", "PR-1");
        silver.put("AREA_HA", new BigDecimal("12.5"));
        when(conjuntosDadosService.buscarRegistroSilver(REGISTRO))
                .thenReturn(new RegistroCamadaDto(REGISTRO, true, "POINT (1 1)", silver));
        Map<String, Object> gold = new LinkedHashMap<>();
        gold.put("AREA_HA", new BigDecimal("12.7"));
        gold.put("MUNICIPIO_COD_IBGE", new BigDecimal("4106902"));
        gold.put("GEOMETRIA_WKT", "POINT(1 1)");
        gold.put("VALOR_ISAP", new BigDecimal("3.4"));
        when(jdbcTemplate.queryForList(contains("GOLD.imovel_publicado"), anyString())).thenReturn(List.of(gold));
        when(jdbcTemplate.queryForList(contains("SILVER.municipio"), anyString()))
                .thenReturn(List.of(Map.of("COD_IBGE", new BigDecimal("4106902"))));

        ComparacaoCamadasDto dto = service().comparar(REGISTRO, "SILVER", "GOLD");

        assertThat(campo(dto, "area_ha").situacao()).isEqualTo(ComparacaoCamadasDto.DIFERENTE);
        assertThat(campo(dto, "municipio_cod_ibge").situacao()).isEqualTo(ComparacaoCamadasDto.IGUAL);
        assertThat(campo(dto, "geometria").situacao()).isEqualTo(ComparacaoCamadasDto.IGUAL);
        assertThat(campo(dto, "valor_isap").situacao()).isEqualTo(ComparacaoCamadasDto.SO_DESTINO);
        assertThat(campo(dto, "valor_isap").valorDestino()).isEqualTo(new BigDecimal("3.4"));
        assertThat(dto.resumo().soDestino()).isEqualTo(7);
    }

    @Test
    void silverParaGoldDeImovelNaoPublicadoExplicaOMotivo() {
        when(conjuntosDadosService.buscarRegistroSilver(REGISTRO))
                .thenReturn(new RegistroCamadaDto(REGISTRO, false, null, Map.of("COD_IMOVEL", "PR-9")));
        when(jdbcTemplate.queryForList(contains("GOLD.imovel_publicado"), anyString())).thenReturn(List.of());

        ComparacaoCamadasDto dto = service().comparar(REGISTRO, "SILVER", "GOLD");

        assertThat(dto.destinoEncontrado()).isFalse();
        assertThat(dto.mensagem()).contains("PR-9").contains("ainda não foi publicado");
    }

    @Test
    void silverParaGoldDeEntidadeSemImovelExplicaQueAGoldPublicaPorImovel() {
        when(conjuntosDadosService.buscarRegistroSilver(REGISTRO))
                .thenReturn(new RegistroCamadaDto(REGISTRO, false, null, Map.of("CODIGO_ORIGEM", "x")));

        ComparacaoCamadasDto dto = service().comparar(REGISTRO, "SILVER", "GOLD");

        assertThat(dto.destinoEncontrado()).isFalse();
        assertThat(dto.mensagem()).contains("imóvel rural");
    }

    @Test
    void combinacaoDeCamadasNaoSuportadaViraErro() {
        assertThatThrownBy(() -> service().comparar(REGISTRO, "BRONZE", "GOLD"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BRONZE→SILVER ou SILVER→GOLD");
    }
}
