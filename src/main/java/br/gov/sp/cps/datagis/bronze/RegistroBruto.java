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
 * Registro bruto, schema-on-read (CLAUDE.md §2.2). Guarda o payload original inteiro
 * como JSON, sem tipagem — nenhuma interpretação acontece nesta camada.
 *
 * A tabela vive no schema BRONZE, diferente do datasource de conexão (CONTROLE) — por
 * isso o {@code schema} explícito no @Table. As referências a fonte_dado/lote_ingestao
 * (schema CONTROLE) ficam como Long simples, não @ManyToOne: Bronze é armazenamento
 * burro, não precisa navegar o grafo de objetos, e evita ao Hibernate ter que resolver
 * join cross-schema para uma tabela que já é só "gravar e esquecer".
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

    /** Posição da feature/linha no arquivo/resposta original (rastreabilidade). */
    @Column(name = "numero_linha")
    private Integer numeroLinha;

    /** JSON cru, chaves = nomes de colunas/propriedades originais. Sem validação. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String payload;

    /** WKT bruto como texto, sem validar — reprojeção/tipagem só na Silver. */
    @Lob
    @Column(name = "geometria_wkt")
    private String geometriaWkt;

    @Column(name = "hash_registro", length = 64)
    private String hashRegistro;

    @Column(name = "data_ingestao")
    private LocalDateTime dataIngestao;
}
