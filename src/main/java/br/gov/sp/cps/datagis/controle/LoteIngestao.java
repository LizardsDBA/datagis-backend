package br.gov.sp.cps.datagis.controle;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Histórico de execuções de ingestão (CLAUDE.md §2.7). Para fontes via API (como o CAR
 * nesta fatia), "origemReferencia" é a URL/consulta que gerou o lote, não um arquivo.
 */
@Entity
@Table(name = "lote_ingestao")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LoteIngestao {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "fonte_dado_id", nullable = false)
    private FonteDado fonteDado;

    /** URL/consulta (API) ou nome do arquivo (upload) que originou o lote. */
    @Column(name = "origem_referencia", length = 1000)
    private String origemReferencia;

    /** Hash do corpo recebido — dedupe / detectar se a fonte mudou (§2.8, RN018). */
    @Column(name = "hash_conteudo", length = 64)
    private String hashConteudo;

    @Column(name = "quantidade_registros")
    private Integer quantidadeRegistros;

    /** CONCLUIDO | FALHA | EM_PROCESSAMENTO — nesta fatia só CONCLUIDO/FALHA (síncrono). */
    @Builder.Default
    private String status = "CONCLUIDO";

    @Column(name = "data_execucao")
    private LocalDateTime dataExecucao;
}
