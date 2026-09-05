package br.gov.sp.cps.datagis.controle;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.type.NumericBooleanConverter;

/** Cadastro das fontes de dados (CAR, IBAMA, etc). */
@Entity
@Table(name = "fonte_dado")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FonteDado {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String nome;

    @Column(length = 200)
    private String orgao;

    /** UPLOAD_MANUAL | API_REST | DOWNLOAD_URL. */
    @Column(name = "tipo_aquisicao", nullable = false, length = 20)
    private String tipoAquisicao;

    @Column(name = "url_origem", length = 1000)
    private String urlOrigem;

    @Column(name = "cron_expressao", length = 100)
    private String cronExpressao;

    /** CSV | GEOJSON | SHAPEFILE | JSON. */
    @Column(name = "formato_esperado", length = 30)
    private String formatoEsperado;

    // A coluna no banco e NUMBER(1), nao BOOLEAN. Esse conversor faz o Hibernate
    // ler/gravar 0 e 1 em vez de esperar um tipo booleano nativo.
    @Convert(converter = NumericBooleanConverter.class)
    @Builder.Default
    private Boolean ativo = true;

    @Column(name = "ultima_execucao_em")
    private LocalDateTime ultimaExecucaoEm;

    @Column(name = "proxima_execucao_em")
    private LocalDateTime proximaExecucaoEm;

    @Column(name = "data_add")
    private LocalDateTime dataAdd;
}
