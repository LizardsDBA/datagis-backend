package br.gov.sp.cps.datagis.gold;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import org.hibernate.type.NumericBooleanConverter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TipoIndicadorTest {

    @Test
    @DisplayName("Deve inicializar com valores padrão corretos via Builder")
    void deveInicializarComValoresPadrao() {
        TipoIndicador tipo = TipoIndicador.builder()
                .codigo("ISAP")
                .descricao("Índice de Sobreposição com Áreas Protegidas")
                .build();

        assertThat(tipo.getCodigo()).isEqualTo("ISAP");
        assertThat(tipo.getDescricao()).isEqualTo("Índice de Sobreposição com Áreas Protegidas");
        assertThat(tipo.getTemFonte()).isTrue();
        assertThat(tipo.getAtivo()).isTrue();
        assertThat(tipo.getDataAdd()).isNull();

        // V077: a maioria dos indicadores é percentual, então o padrão evita que um
        // tipo cadastrado pela tela de administração nasça sem unidade.
        assertThat(tipo.getUnidade()).isEqualTo("PERCENTUAL");
        assertThat(tipo.getUnidadeSimbolo()).isEqualTo("%");

        // V076: classificação não tem padrão. Indicador sem faixa definida fica neutro
        // na tela em vez de herdar limites de outro indicador.
        assertThat(tipo.getDimensao()).isNull();
        assertThat(tipo.getSentido()).isNull();
        assertThat(tipo.getLimiteBom()).isNull();
        assertThat(tipo.getLimiteAtencao()).isNull();
        assertThat(tipo.getOrdemExibicao()).isNull();
    }

    @Test
    @DisplayName("Deve permitir customização de todos os campos")
    void devePermitirCustomizacaoDeTodosOsCampos() {
        LocalDateTime data = LocalDateTime.of(2026, 9, 17, 10, 0);
        TipoIndicador tipo = TipoIndicador.builder()
                .id(10L)
                .codigo("ICV")
                .descricao("Índice de Cobertura Vegetal Nativa")
                .temFonte(false)
                .ativo(false)
                .dataAdd(data)
                .build();

        assertThat(tipo.getId()).isEqualTo(10L);
        assertThat(tipo.getCodigo()).isEqualTo("ICV");
        assertThat(tipo.getDescricao()).isEqualTo("Índice de Cobertura Vegetal Nativa");
        assertThat(tipo.getTemFonte()).isFalse();
        assertThat(tipo.getAtivo()).isFalse();
        assertThat(tipo.getDataAdd()).isEqualTo(data);
    }

    @Test
    @DisplayName("Deve carregar os campos de catálogo usados pela tela de indicadores")
    void deveCarregarCamposDeCatalogo() {
        // IFC é o caso que motivou a V077: único indicador de densidade do catálogo,
        // calculado como focos / área do imóvel * 1.000 pela pkg_calculo_ambiental.
        TipoIndicador tipo = TipoIndicador.builder()
                .codigo("IFC")
                .descricao("Índice de Focos de Calor")
                .dimensao("AMBIENTAL")
                .sentido("MENOR_MELHOR")
                .limiteBom(30)
                .limiteAtencao(60)
                .ordemExibicao(7)
                .unidade("DENSIDADE")
                .unidadeSimbolo("focos/1.000 ha")
                .build();

        assertThat(tipo.getDimensao()).isEqualTo("AMBIENTAL");
        assertThat(tipo.getSentido()).isEqualTo("MENOR_MELHOR");
        assertThat(tipo.getLimiteBom()).isEqualTo(30);
        assertThat(tipo.getLimiteAtencao()).isEqualTo(60);
        assertThat(tipo.getOrdemExibicao()).isEqualTo(7);
        assertThat(tipo.getUnidade()).isEqualTo("DENSIDADE");
        assertThat(tipo.getUnidadeSimbolo()).isEqualTo("focos/1.000 ha");
    }

    @Test
    @DisplayName("PrePersist deve preencher dataAdd quando nula")
    void prePersistDevePreencherDataAddQuandoNula() {
        TipoIndicador tipo = new TipoIndicador();
        tipo.setCodigo("IAE");
        tipo.setDescricao("Índice de Área Embargada");

        assertThat(tipo.getDataAdd()).isNull();
        tipo.prePersist();
        assertThat(tipo.getDataAdd()).isNotNull();
    }

    @Test
    @DisplayName("PrePersist deve preservar dataAdd existente")
    void prePersistDevePreservarDataAddExistente() {
        LocalDateTime dataOriginal = LocalDateTime.of(2026, 1, 1, 12, 0);
        TipoIndicador tipo = new TipoIndicador();
        tipo.setDataAdd(dataOriginal);

        tipo.prePersist();
        assertThat(tipo.getDataAdd()).isEqualTo(dataOriginal);
    }

    @Test
    @DisplayName("Verifica anotações de tabela e mapeamento de schema GOLD")
    void deveEstarMapeadoParaSchemaGold() throws NoSuchFieldException {
        Table tableAnnotation = TipoIndicador.class.getAnnotation(Table.class);
        assertThat(tableAnnotation).isNotNull();
        assertThat(tableAnnotation.name()).isEqualTo("tipo_indicador");
        assertThat(tableAnnotation.schema()).isEqualTo("GOLD");

        Convert convertTemFonte = TipoIndicador.class.getDeclaredField("temFonte").getAnnotation(Convert.class);
        assertThat(convertTemFonte).isNotNull();
        assertThat(convertTemFonte.converter()).isEqualTo(NumericBooleanConverter.class);

        Convert convertAtivo = TipoIndicador.class.getDeclaredField("ativo").getAnnotation(Convert.class);
        assertThat(convertAtivo).isNotNull();
        assertThat(convertAtivo.converter()).isEqualTo(NumericBooleanConverter.class);

        // Campos com nome diferente do atributo: divergência aqui só apareceria em
        // tempo de execução, contra o banco.
        assertThat(nomeDaColuna("limiteBom")).isEqualTo("limite_bom");
        assertThat(nomeDaColuna("limiteAtencao")).isEqualTo("limite_atencao");
        assertThat(nomeDaColuna("ordemExibicao")).isEqualTo("ordem_exibicao");
        assertThat(nomeDaColuna("unidadeSimbolo")).isEqualTo("unidade_simbolo");
    }

    private String nomeDaColuna(String atributo) throws NoSuchFieldException {
        return TipoIndicador.class.getDeclaredField(atributo).getAnnotation(Column.class).name();
    }
}