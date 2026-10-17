package br.gov.sp.cps.datagis.gold;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.type.NumericBooleanConverter;

/**
 * Entidade JPA representativa dos tipos de indicadores da camada Gold.
 * Mapeada para a tabela {@code gold.tipo_indicador}.
 */
@Entity
@Table(name = "tipo_indicador", schema = "GOLD")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TipoIndicador {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20, unique = true)
    private String codigo;

    @Column(nullable = false, length = 200)
    private String descricao;

    @Convert(converter = NumericBooleanConverter.class)
    @Column(name = "tem_fonte", nullable = false)
    @Builder.Default
    private Boolean temFonte = true;

    @Convert(converter = NumericBooleanConverter.class)
    @Column(nullable = false)
    @Builder.Default
    private Boolean ativo = true;

    // Campos abaixo: V076, para a tela de consulta de indicadores (LIZARDS-34). Sao
    // regra
    // de negocio, nao desenho - por isso moram no banco e nao num if do frontend.

    /** Grupo de cabecalho na tela: CONSERVACAO, TERRITORIAL ou AMBIENTAL. */
    @Column(length = 20)
    private String dimensao;

    /**
     * MAIOR_MELHOR ou MENOR_MELHOR. Define o lado da comparacao das faixas: em ICV,
     * IRL,
     * IAPP e ISAP quanto maior melhor; em IAE, IDESMAT e IFC quanto menor melhor.
     */
    @Column(length = 15)
    private String sentido;

    /** Fronteira da faixa verde. */
    @Column(name = "limite_bom")
    private Integer limiteBom;

    /** Fronteira da faixa amarela; fora dela e vermelho. */
    @Column(name = "limite_atencao")
    private Integer limiteAtencao;

    /** Ordem das colunas na tela, da esquerda para a direita. */
    @Column(name = "ordem_exibicao")
    private Integer ordemExibicao;

    // Campos abaixo: V077. Nem todo indicador e percentual - o IFC e densidade
    // (focos
    // por mil hectares, pkg_calculo_ambiental/V045). Sem isso a tela desenharia a
    // barra
    // dele como se o valor fosse porcentagem.

    /** PERCENTUAL ou DENSIDADE. Define a escala da barra na tela. */
    @Column(length = 20)
    @Builder.Default
    private String unidade = "PERCENTUAL";

    /** Texto exibido ao lado do valor: "%" ou "focos/1.000 ha". */
    @Column(name = "unidade_simbolo", length = 30)
    @Builder.Default
    private String unidadeSimbolo = "%";

    @Column(name = "data_add")
    private LocalDateTime dataAdd;

    @PrePersist
    protected void prePersist() {
        if (dataAdd == null) {
            dataAdd = LocalDateTime.now();
        }
    }
}