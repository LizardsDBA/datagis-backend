package br.gov.sp.cps.datagis.conjuntosdados;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import br.gov.sp.cps.datagis.bronze.RegistroBruto;
import br.gov.sp.cps.datagis.bronze.RegistroBrutoRepository;
import br.gov.sp.cps.datagis.controle.FonteDado;
import br.gov.sp.cps.datagis.controle.FonteDadoRepository;
import br.gov.sp.cps.datagis.controle.LoteIngestao;
import br.gov.sp.cps.datagis.controle.LoteIngestaoRepository;
import br.gov.sp.cps.datagis.validacao.TransicaoBronzeSilverService;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class ConjuntosDadosServiceTest {

    @Mock
    private LoteIngestaoRepository loteIngestaoRepository;
    @Mock
    private FonteDadoRepository fonteDadoRepository;
    @Mock
    private RegistroBrutoRepository registroBrutoRepository;
    @Mock
    private JdbcTemplate jdbcTemplate;
    @Mock
    private TransicaoBronzeSilverService transicaoService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private ConjuntosDadosService service() {
        return new ConjuntosDadosService(
                loteIngestaoRepository, fonteDadoRepository, registroBrutoRepository,
                jdbcTemplate, objectMapper, transicaoService);
    }

    @Test
    void deveBuscarUltimoLotePromovido() {
        FonteDado fonte = FonteDado.builder().id(7L).nome("IBGE - Malha Municipal (PR)").build();
        LoteIngestao lote = LoteIngestao.builder()
                .id(7L).fonteDado(fonte).quantidadeRegistros(3).status("PROMOVIDO_SILVER")
                .dataExecucao(LocalDateTime.of(2024, 6, 1, 9, 0)).build();
        when(fonteDadoRepository.findById(7L)).thenReturn(Optional.of(fonte));
        when(loteIngestaoRepository.findFirstByFonteDadoIdAndStatusOrderByIdDesc(7L, "PROMOVIDO_SILVER"))
                .thenReturn(Optional.of(lote));
        when(transicaoService.resolverEntidadeSilver(fonte)).thenReturn("municipio");
        when(jdbcTemplate.queryForObject(contains("all_tables"), eq(Integer.class), eq("MUNICIPIO")))
                .thenReturn(1);

        LoteResumoDto resumo = service().buscarUltimoLotePromovido(7L);

        assertThat(resumo.loteId()).isEqualTo(7L);
        assertThat(resumo.entidadeSilver()).isEqualTo("municipio");
        assertThat(resumo.quantidadeRegistros()).isEqualTo(3);
    }

    @Test
    void deveFalharSeFonteNaoExistir() {
        when(fonteDadoRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().buscarUltimoLotePromovido(99L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void deveFalharSeFonteNuncaTeveLotePromovido() {
        FonteDado fonte = FonteDado.builder().id(1L).nome("CAR - Imóveis (PR)").build();
        when(fonteDadoRepository.findById(1L)).thenReturn(Optional.of(fonte));
        when(loteIngestaoRepository.findFirstByFonteDadoIdAndStatusOrderByIdDesc(1L, "PROMOVIDO_SILVER"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().buscarUltimoLotePromovido(1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ainda não teve");
    }

    @Test
    void deveListarBronzeSemTratarOPayload() {
        RegistroBruto registro = RegistroBruto.builder()
                .id(1L).loteIngestaoId(7L).fonteDadoId(7L)
                .payload("{\"CD_MUN\":\"4106902\",\"NM_MUN\":\"Curitiba\"}")
                .geometriaWkt("POLYGON ((0 0, 1 0, 1 1, 0 0))")
                .build();
        when(registroBrutoRepository.findByLoteIngestaoId(eq(7L), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new PageImpl<>(List.of(registro)));

        PaginaDto<RegistroCamadaDto> pagina = service().listarBronze(7L, 0, 25);

        assertThat(pagina.total()).isEqualTo(1);
        RegistroCamadaDto item = pagina.itens().get(0);
        assertThat(item.registroBrutoId()).isEqualTo(1L);
        assertThat(item.temGeometria()).isTrue();
        assertThat(item.dados()).containsEntry("CD_MUN", "4106902").containsEntry("NM_MUN", "Curitiba");
    }

    @Test
    void deveFalharAoBuscarRegistroBronzeInexistente() {
        when(registroBrutoRepository.findById(123L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().buscarRegistroBronze(123L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void deveListarSilverConvertendoGeometriaEExcluindoOColunaBruta() {
        FonteDado fonte = FonteDado.builder().id(7L).nome("IBGE - Malha Municipal (PR)").build();
        LoteIngestao lote = LoteIngestao.builder().id(7L).fonteDado(fonte).build();
        when(loteIngestaoRepository.findByIdComFonte(7L)).thenReturn(Optional.of(lote));
        when(transicaoService.resolverEntidadeSilver(fonte)).thenReturn("municipio");
        when(jdbcTemplate.queryForObject(contains("all_tables"), eq(Integer.class), eq("MUNICIPIO")))
                .thenReturn(1);

        List<Map<String, Object>> colunasMetadados = List.of(
                Map.of("COLUMN_NAME", "ID", "DATA_TYPE", "NUMBER"),
                Map.of("COLUMN_NAME", "NOME", "DATA_TYPE", "VARCHAR2"),
                Map.of("COLUMN_NAME", "GEOMETRIA", "DATA_TYPE", "SDO_GEOMETRY"),
                Map.of("COLUMN_NAME", "ATRIBUTOS_ORIGEM", "DATA_TYPE", "JSON"),
                Map.of("COLUMN_NAME", "REGISTRO_BRUTO_ID", "DATA_TYPE", "NUMBER"));
        when(jdbcTemplate.queryForList(contains("all_tab_columns"), eq("MUNICIPIO")))
                .thenReturn(colunasMetadados);
        when(jdbcTemplate.queryForList(contains("all_cons_columns"), eq("MUNICIPIO")))
                .thenReturn(List.of());

        Map<String, Object> linha = new LinkedHashMap<>();
        linha.put("ID", 1);
        linha.put("NOME", "Curitiba");
        linha.put("GEOMETRIA_WKT", "POLYGON ((0 0, 1 0, 1 1, 0 0))");
        linha.put("ATRIBUTOS_ORIGEM", null);
        linha.put("REGISTRO_BRUTO_ID", 1L);
        when(jdbcTemplate.queryForList(contains("FROM SILVER.municipio"), eq(7L), eq(0), eq(25)))
                .thenReturn(List.of(linha));
        when(jdbcTemplate.queryForObject(contains("COUNT(*)"), eq(Long.class), eq(7L))).thenReturn(3L);

        PaginaDto<RegistroCamadaDto> pagina = service().listarSilver(7L, 0, 25);

        assertThat(pagina.total()).isEqualTo(3);
        RegistroCamadaDto item = pagina.itens().get(0);
        assertThat(item.registroBrutoId()).isEqualTo(1L);
        assertThat(item.temGeometria()).isTrue();
        assertThat(item.geometriaWkt()).contains("POLYGON");
        // a chave de geometria some de "dados" - já está exposta no campo dedicado do DTO
        assertThat(item.dados()).doesNotContainKey("GEOMETRIA_WKT");
        assertThat(item.dados()).containsEntry("NOME", "Curitiba");
    }

    @Test
    void deveDevolverNuloQuandoRegistroNuncaChegouNaSilver() {
        RegistroBruto bronze = RegistroBruto.builder().id(38L).fonteDadoId(1L).payload("{}").build();
        FonteDado fonte = FonteDado.builder().id(1L).nome("CAR - Imóveis (PR)").build();
        when(registroBrutoRepository.findById(38L)).thenReturn(Optional.of(bronze));
        when(fonteDadoRepository.findById(1L)).thenReturn(Optional.of(fonte));
        when(transicaoService.resolverEntidadeSilver(fonte)).thenReturn("imovel_rural");
        when(jdbcTemplate.queryForObject(contains("all_tables"), eq(Integer.class), eq("IMOVEL_RURAL")))
                .thenReturn(1);
        when(jdbcTemplate.queryForList(contains("all_tab_columns"), eq("IMOVEL_RURAL")))
                .thenReturn(List.of(Map.of("COLUMN_NAME", "ID", "DATA_TYPE", "NUMBER")));
        when(jdbcTemplate.queryForList(contains("all_cons_columns"), eq("IMOVEL_RURAL")))
                .thenReturn(List.of());
        when(jdbcTemplate.queryForList(contains("FROM SILVER.imovel_rural"), eq(38L)))
                .thenReturn(List.of());

        RegistroCamadaDto resultado = service().buscarRegistroSilver(38L);

        assertThat(resultado).isNull();
    }

    @Test
    void deveResolverColunasDeFkComJoinEmVezDoIdCru() {
        FonteDado fonte = FonteDado.builder().id(1L).nome("CAR - Imóveis (PR)").build();
        LoteIngestao lote = LoteIngestao.builder().id(1L).fonteDado(fonte).build();
        when(loteIngestaoRepository.findByIdComFonte(1L)).thenReturn(Optional.of(lote));
        when(transicaoService.resolverEntidadeSilver(fonte)).thenReturn("imovel_rural");
        when(jdbcTemplate.queryForObject(contains("all_tables"), eq(Integer.class), eq("IMOVEL_RURAL")))
                .thenReturn(1);

        when(jdbcTemplate.queryForList(contains("all_tab_columns"), eq("IMOVEL_RURAL")))
                .thenReturn(List.of(
                        Map.of("COLUMN_NAME", "ID", "DATA_TYPE", "NUMBER"),
                        Map.of("COLUMN_NAME", "COD_IMOVEL", "DATA_TYPE", "VARCHAR2"),
                        Map.of("COLUMN_NAME", "SITUACAO_IMOVEL_ID", "DATA_TYPE", "NUMBER"),
                        Map.of("COLUMN_NAME", "MUNICIPIO_ID", "DATA_TYPE", "NUMBER"),
                        Map.of("COLUMN_NAME", "REGISTRO_BRUTO_ID", "DATA_TYPE", "NUMBER")));
        when(jdbcTemplate.queryForList(contains("all_cons_columns"), eq("IMOVEL_RURAL")))
                .thenReturn(List.of(
                        Map.of("FK_COLUNA", "SITUACAO_IMOVEL_ID", "TABELA_REF", "SITUACAO_IMOVEL"),
                        Map.of("FK_COLUNA", "MUNICIPIO_ID", "TABELA_REF", "MUNICIPIO")));
        // situacao_imovel so tem codigo/descricao; municipio so tem nome (sem descricao)
        when(jdbcTemplate.queryForList(contains("all_tab_columns"), eq(String.class), eq("SITUACAO_IMOVEL")))
                .thenReturn(List.of("ID", "CODIGO", "DESCRICAO"));
        when(jdbcTemplate.queryForList(contains("all_tab_columns"), eq(String.class), eq("MUNICIPIO")))
                .thenReturn(List.of("ID", "NOME"));

        Map<String, Object> linha = new LinkedHashMap<>();
        linha.put("ID", 4);
        linha.put("COD_IMOVEL", "PR-4119905-DEMO0002");
        linha.put("SITUACAO_IMOVEL_ID", "Ativo");
        linha.put("MUNICIPIO_ID", "Ponta Grossa");
        linha.put("REGISTRO_BRUTO_ID", 4L);
        when(jdbcTemplate.queryForList(contains("FROM SILVER.imovel_rural"), eq(1L), eq(0), eq(25)))
                .thenReturn(List.of(linha));
        when(jdbcTemplate.queryForObject(contains("COUNT(*)"), eq(Long.class), eq(1L))).thenReturn(1L);
        PaginaDto<RegistroCamadaDto> pagina = service().listarSilver(1L, 0, 25);

        RegistroCamadaDto item = pagina.itens().get(0);
        assertThat(item.dados()).containsEntry("SITUACAO_IMOVEL_ID", "Ativo");
        assertThat(item.dados()).containsEntry("MUNICIPIO_ID", "Ponta Grossa");
        assertThat(item.dados()).containsEntry("COD_IMOVEL", "PR-4119905-DEMO0002");
    }
}
