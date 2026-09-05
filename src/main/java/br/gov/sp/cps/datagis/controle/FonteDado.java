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

/**
 * Catálogo de fontes de dados (CLAUDE.md §2.7/§2.8).
 *
 * Nesta fatia, populada só por seed do Flyway (V003__seed_fonte_dado_car_pr.sql) — o
 * cadastro via tela/endpoint fica para uma fatia futura.
 */
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

    /** UPLOAD_MANUAL | API_REST | DOWNLOAD_URL (ver §2.8). */
    @Column(name = "tipo_aquisicao", nullable = false, length = 20)
    private String tipoAquisicao;

    @Column(name = "url_origem", length = 1000)
    private String urlOrigem;

    @Column(name = "cron_expressao", length = 100)
    private String cronExpressao;

    /** CSV | GEOJSON | SHAPEFILE | JSON. */
    @Column(name = "formato_esperado", length = 30)
    private String formatoEsperado;

    // DDL declara NUMBER(1) (convenção Oracle tradicional, CLAUDE.md §2.8), não o
    // BOOLEAN nativo do Oracle 23ai — o conversor evita que o Hibernate espere o tipo
    // nativo por padrão.
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
