package br.gov.sp.cps.datagis.controle;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class CatalogoSilverServiceTest {

    private final CatalogoSilverService service = new CatalogoSilverService();

    @Test
    void deveListarTodasAsEntidadesSilver() {
        List<CatalogoSilverService.EsquemaSilverDto> esquemas = service.listarEsquemas();

        assertThat(esquemas).isNotEmpty();
        assertThat(esquemas).extracting(CatalogoSilverService.EsquemaSilverDto::id)
                .contains("imovel_rural", "area_protegida", "embargo_ambiental", "auto_infracao",
                          "desmatamento", "foco_calor", "municipio", "reserva_legal",
                          "app_declarada", "vegetacao_nativa_declarada", "bioma");
    }

    @Test
    void deveBuscarPorIdEspecifico() {
        CatalogoSilverService.EsquemaSilverDto imovel = service.buscarPorId("imovel_rural");

        assertThat(imovel).isNotNull();
        assertThat(imovel.nome()).isEqualTo("Imóvel Rural (CAR)");
        assertThat(imovel.colunas()).extracting(CatalogoSilverService.ColunaSilverDto::nome)
                .contains("cod_imovel", "situacao_imovel", "condicao_imovel", "tipo_imovel", "area_ha", "geometria");
        assertThat(imovel.dominios()).extracting(CatalogoSilverService.DominioSilverDto::tabela)
                .contains("situacao_imovel", "condicao_imovel", "tipo_imovel");
    }

    @Test
    void deveRetornarNullParaIdInexistente() {
        assertThat(service.buscarPorId("inexistente")).isNull();
        assertThat(service.buscarPorId(null)).isNull();
    }
}
