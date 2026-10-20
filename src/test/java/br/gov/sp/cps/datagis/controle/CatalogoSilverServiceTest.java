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

    @Test
    void deveConterNovasColunasEmAutoInfracaoEFocoCalor() {
        CatalogoSilverService.EsquemaSilverDto auto = service.buscarPorId("auto_infracao");
        assertThat(auto).isNotNull();
        assertThat(auto.colunas()).extracting(CatalogoSilverService.ColunaSilverDto::nome)
                .contains("houve_embargo", "houve_apreensao");

        CatalogoSilverService.EsquemaSilverDto foco = service.buscarPorId("foco_calor");
        assertThat(foco).isNotNull();
        assertThat(foco.colunas()).extracting(CatalogoSilverService.ColunaSilverDto::nome)
                .contains("dias_sem_chuva", "precipitacao_mm", "frp");
    }

    @Test
    void deveExcluirCamposAutogeradosEAjustarObrigatoriedades() {
        CatalogoSilverService.EsquemaSilverDto area = service.buscarPorId("area_protegida");
        assertThat(area).isNotNull();
        assertThat(area.colunas()).extracting(CatalogoSilverService.ColunaSilverDto::nome)
                .doesNotContain("orgao", "tipo_area_protegida")
                .contains("categoria_area_protegida");
        CatalogoSilverService.ColunaSilverDto colCat = area.colunas().stream()
                .filter(c -> c.nome().equals("categoria_area_protegida")).findFirst().orElseThrow();
        assertThat(colCat.obrigatorio()).isTrue();

        CatalogoSilverService.EsquemaSilverDto auto = service.buscarPorId("auto_infracao");
        assertThat(auto.colunas()).extracting(CatalogoSilverService.ColunaSilverDto::nome)
                .doesNotContain("orgao");

        CatalogoSilverService.EsquemaSilverDto embargo = service.buscarPorId("embargo_ambiental");
        assertThat(embargo.colunas()).extracting(CatalogoSilverService.ColunaSilverDto::nome)
                .doesNotContain("orgao");

        CatalogoSilverService.EsquemaSilverDto municipio = service.buscarPorId("municipio");
        assertThat(municipio.colunas()).extracting(CatalogoSilverService.ColunaSilverDto::nome)
                .doesNotContain("unidade_federativa");

        CatalogoSilverService.EsquemaSilverDto imovel = service.buscarPorId("imovel_rural");
        CatalogoSilverService.ColunaSilverDto colSit = imovel.colunas().stream()
                .filter(c -> c.nome().equals("situacao_imovel")).findFirst().orElseThrow();
        assertThat(colSit.obrigatorio()).isFalse();
    }
}
