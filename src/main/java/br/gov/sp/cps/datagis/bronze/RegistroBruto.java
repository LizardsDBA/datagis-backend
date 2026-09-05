package br.gov.sp.cps.datagis.bronze;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Guarda o registro bruto exatamente como veio da fonte, sem tratar nada.
 *
 * A tabela fica no schema BRONZE, diferente do usuario de conexao (CONTROLE), por
 * isso o {@code schema} explicito no @Table. As referencias pra fonte_dado e
 * lote_ingestao (que ficam no CONTROLE) sao so um Long, nao @ManyToOne — nao precisa
 * carregar o objeto inteiro so pra gravar um registro bruto.
 */
@Entity
@Table(name = "registro_bruto", schema = "BRONZE")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RegistroBruto {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "lote_ingestao_id", nullable = false)
    private Long loteIngestaoId;

    @Column(name = "fonte_dado_id", nullable = false)
    private Long fonteDadoId;

    /** Posicao da linha/feature no arquivo ou resposta original. */
    @Column(name = "numero_linha")
    private Integer numeroLinha;

    /** JSON cru, com os nomes de campo originais da fonte. Sem validar nada. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String payload;

    /** Geometria em WKT, sem validar. Tratar a geometria e coisa da proxima camada. */
    @Lob
    @Column(name = "geometria_wkt")
    private String geometriaWkt;

    @Column(name = "hash_registro", length = 64)
    private String hashRegistro;

    @Column(name = "data_ingestao")
    private LocalDateTime dataIngestao;
}
