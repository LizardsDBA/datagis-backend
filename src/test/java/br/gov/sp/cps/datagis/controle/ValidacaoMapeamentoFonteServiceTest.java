package br.gov.sp.cps.datagis.controle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ValidacaoMapeamentoFonteServiceTest {

    @Mock
    private CatalogoSilverService catalogoSilverService;

    @Mock
    private MapeamentoColunaRepository mapeamentoRepository;

    private ValidacaoMapeamentoFonteService service;

    @BeforeEach
    void setUp() {
        service = new ValidacaoMapeamentoFonteService(catalogoSilverService, mapeamentoRepository);
    }

    @Test
    void deveRetornarIncompletoQuandoFonteNaoTemTabelaSilver() {
        FonteDado fonte = FonteDado.builder().id(1L).nome("Fonte Sem Silver").entidadeSilver(null).build();

        ValidacaoMapeamentoFonteService.StatusMapeamentoFonte status = service.avaliarStatus(fonte);

        assertThat(status.completo()).isFalse();
        assertThat(status.entidadeSilver()).isNull();
        assertThat(status.totalColunasEsperadas()).isZero();

        assertThatThrownBy(() -> service.validarParaIngestao(fonte))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nenhuma tabela Silver foi configurada");
    }

    @Test
    void deveRetornarIncompletoQuandoFaltamColunasDaTabelaSilver() {
        FonteDado fonte = FonteDado.builder().id(2L).nome("CAR SP").entidadeSilver("imovel_rural").build();

        CatalogoSilverService.EsquemaSilverDto esquema = new CatalogoSilverService.EsquemaSilverDto(
                "imovel_rural",
                "Imóvel Rural (CAR)",
                "Descrição",
                List.of(
                        CatalogoSilverService.ColunaSilverDto.coluna("cod_imovel", "Código", "texto", true),
                        CatalogoSilverService.ColunaSilverDto.coluna("area_ha", "Área", "numero", false),
                        CatalogoSilverService.ColunaSilverDto.geometria("geometria", "Geometria")),
                Collections.emptyList());

        when(catalogoSilverService.buscarPorId("imovel_rural")).thenReturn(esquema);

        // Apenas 'cod_imovel' mapeado
        MapeamentoColuna map1 = MapeamentoColuna.builder()
                .id(10L)
                .fonteDadoId(2L)
                .colunaOrigem("num_sicar")
                .atributoDestino("cod_imovel")
                .destinoTipo("COLUNA")
                .build();

        when(mapeamentoRepository.findByFonteDadoId(2L)).thenReturn(List.of(map1));

        ValidacaoMapeamentoFonteService.StatusMapeamentoFonte status = service.avaliarStatus(fonte);

        assertThat(status.completo()).isFalse();
        assertThat(status.totalColunasEsperadas()).isEqualTo(3);
        assertThat(status.totalColunasMapeadas()).isEqualTo(1);
        assertThat(status.colunasFaltantes()).containsExactly("area_ha", "geometria");

        assertThatThrownBy(() -> service.validarParaIngestao(fonte))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("o mapeamento para a tabela Silver 'Imóvel Rural (CAR)' está incompleto")
                .hasMessageContaining("Faltam 2 coluna(s): area_ha, geometria");
    }

    @Test
    void deveAprovarQuandoTodasAsColunasForemMapeadas() {
        FonteDado fonte = FonteDado.builder().id(3L).nome("Biomas").entidadeSilver("bioma").build();

        CatalogoSilverService.EsquemaSilverDto esquema = new CatalogoSilverService.EsquemaSilverDto(
                "bioma",
                "Bioma (IBGE)",
                "Descrição",
                List.of(
                        CatalogoSilverService.ColunaSilverDto.coluna("codigo_bioma", "Código", "texto", true),
                        CatalogoSilverService.ColunaSilverDto.coluna("nome", "Nome", "texto", true),
                        CatalogoSilverService.ColunaSilverDto.geometria("geometria", "Geometria")),
                Collections.emptyList());

        when(catalogoSilverService.buscarPorId("bioma")).thenReturn(esquema);

        MapeamentoColuna map1 = MapeamentoColuna.builder().id(1L).fonteDadoId(3L).colunaOrigem("CD_BIOMA").atributoDestino("codigo_bioma").destinoTipo("COLUNA").build();
        MapeamentoColuna map2 = MapeamentoColuna.builder().id(2L).fonteDadoId(3L).colunaOrigem("NM_BIOMA").atributoDestino("nome").destinoTipo("COLUNA").build();
        MapeamentoColuna map3 = MapeamentoColuna.builder().id(3L).fonteDadoId(3L).colunaOrigem("THE_GEOM").atributoDestino("geometria").destinoTipo("GEOMETRIA").build();

        when(mapeamentoRepository.findByFonteDadoId(3L)).thenReturn(List.of(map1, map2, map3));

        ValidacaoMapeamentoFonteService.StatusMapeamentoFonte status = service.avaliarStatus(fonte);

        assertThat(status.completo()).isTrue();
        assertThat(status.totalColunasEsperadas()).isEqualTo(3);
        assertThat(status.totalColunasMapeadas()).isEqualTo(3);
        assertThat(status.colunasFaltantes()).isEmpty();

        // Não deve lançar exceção
        service.validarParaIngestao(fonte);
    }
}
