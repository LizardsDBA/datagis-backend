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
 * Cada linha e uma execucao de ingestao. Pra fonte via API (como o CAR),
 * "origemReferencia" guarda a URL usada, nao um nome de arquivo.
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

    /** URL (API) ou nome do arquivo (upload) que gerou o lote. */
    @Column(name = "origem_referencia", length = 1000)
    private String origemReferencia;

    /** Hash do conteudo recebido, usado pra saber se mudou desde o ultimo lote. */
    @Column(name = "hash_conteudo", length = 64)
    private String hashConteudo;

    @Column(name = "quantidade_registros")
    private Integer quantidadeRegistros;

    /** CONCLUIDO | SEM_MUDANCA | EM_PROCESSAMENTO | FALHA. */
    @Builder.Default
    private String status = "CONCLUIDO";

    @Column(name = "data_execucao")
    private LocalDateTime dataExecucao;
}
