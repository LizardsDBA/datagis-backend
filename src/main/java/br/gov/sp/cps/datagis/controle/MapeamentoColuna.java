package br.gov.sp.cps.datagis.controle;

import br.gov.sp.cps.datagis.validacao.ContextoValidacaoCampo;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.NumericBooleanConverter;
import org.hibernate.type.SqlTypes;

/**
 * Entidade que define o de-para de campos do payload bruto (Bronze)
 * para a entidade canônica da Silver, juntamente com as regras de validação aplicáveis.
 */
@Entity
@Table(name = "mapeamento_coluna")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MapeamentoColuna {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "fonte_dado_id", nullable = false)
    private Long fonteDadoId;

    @Column(name = "coluna_origem", nullable = false, length = 200)
    private String colunaOrigem;

    @Column(name = "atributo_destino", nullable = false, length = 100)
    private String atributoDestino;

    @Column(name = "tabela_destino", length = 60)
    private String tabelaDestino;

    /** COLUNA | JSON | GEOMETRIA */
    @Column(name = "destino_tipo", nullable = false, length = 20)
    private String destinoTipo;

    @Column(name = "tipo_dado", length = 30)
    private String tipoDado;

    @Convert(converter = NumericBooleanConverter.class)
    @Builder.Default
    private Boolean obrigatorio = false;

    @Column(name = "valor_minimo")
    private BigDecimal valorMinimo;

    @Column(name = "tabela_referencia", length = 60)
    private String tabelaReferencia;

    @Column(name = "referencia_entidade_silver", length = 60)
    private String referenciaEntidadeSilver;

    @Convert(converter = NumericBooleanConverter.class)
    @Builder.Default
    @Column(name = "validar_cpf_cnpj")
    private Boolean validarCpfCnpj = false;

    @Convert(converter = NumericBooleanConverter.class)
    @Builder.Default
    @Column(name = "validar_geometria")
    private Boolean validarGeometria = false;

    @Convert(converter = NumericBooleanConverter.class)
    @Builder.Default
    @Column(name = "validar_recorte_pr")
    private Boolean validarRecortePr = false;

    @Convert(converter = NumericBooleanConverter.class)
    @Builder.Default
    @Column(name = "impedir_regressao")
    private Boolean impedirRegressao = true;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "regras_extras")
    private String regrasExtras;

    @Column(name = "data_add")
    private LocalDateTime dataAdd;

    /**
     * Converte as configurações de mapeamento em um contexto executável
     * pelo motor de validações.
     */
    public ContextoValidacaoCampo toContextoValidacao(Object valorAnterior) {
        return ContextoValidacaoCampo.builder()
                .colunaOrigem(colunaOrigem)
                .atributoDestino(atributoDestino)
                .destinoTipo(destinoTipo)
                .tipoDado(tipoDado)
                .obrigatorio(Boolean.TRUE.equals(obrigatorio))
                .valorMinimo(valorMinimo != null ? valorMinimo.doubleValue() : null)
                .tabelaReferencia(tabelaReferencia)
                .referenciaEntidadeSilver(referenciaEntidadeSilver)
                .validarCpfCnpj(Boolean.TRUE.equals(validarCpfCnpj))
                .validarGeometria(Boolean.TRUE.equals(validarGeometria))
                .validarRecortePr(Boolean.TRUE.equals(validarRecortePr))
                .impedirRegressao(impedirRegressao == null || Boolean.TRUE.equals(impedirRegressao))
                .regrasExtras(regrasExtras)
                .valorAnterior(valorAnterior)
                .build();
    }

    public ContextoValidacaoCampo toContextoValidacao() {
        return toContextoValidacao(null);
    }

    public static class MapeamentoColunaBuilder {
        public MapeamentoColunaBuilder valorMinimo(Double valor) {
            this.valorMinimo = valor != null ? BigDecimal.valueOf(valor) : null;
            return this;
        }

        public MapeamentoColunaBuilder valorMinimo(BigDecimal valor) {
            this.valorMinimo = valor;
            return this;
        }
    }
}
