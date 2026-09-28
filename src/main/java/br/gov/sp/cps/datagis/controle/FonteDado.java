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

    /** IMOVEL_RURAL | AREA_PROTEGIDA | EMBARGO_AMBIENTAL | AUTO_INFRACAO | DESMATAMENTO | FOCO_CALOR | MUNICIPIO */
    @Column(name = "entidade_silver", length = 40)
    private String entidadeSilver;

    /** URL final, já com todo filtro que a fonte precisar resolvido (CQL_FILTER,
     * outSR, bbox etc.) — expandir um circuito ou trocar um parâmetro é editar aqui,
     * não mexer em código. */
    @Column(name = "url_origem", length = 2000)
    private String urlOrigem;

    @Column(name = "cron_expressao", length = 100)
    private String cronExpressao;

    /** CSV | GEOJSON | SHAPEFILE | JSON. */
    @Column(name = "formato_esperado", length = 30)
    private String formatoEsperado;

    /** Nome da coluna de latitude, só relevante pra fontes CSV com coordenada
     * (ex.: focos de calor). Nula = sem geometria ou formato que já embute
     * geometria (GeoJSON). */
    @Column(name = "campo_geometria_lat", length = 100)
    private String campoGeometriaLat;

    @Column(name = "campo_geometria_lon", length = 100)
    private String campoGeometriaLon;

    /** Separador de campo do CSV — "," | ";" | "|" | "\t". Só relevante pra
     * formatoEsperado=CSV; fontes de outro formato ignoram. Nulo em fonte antiga vira
     * "," (vírgula) por padrão — era o único separador suportado antes dessa coluna
     * existir, então esse default preserva o comportamento de quem já estava cadastrado. */
    @Column(name = "separador_csv", length = 5)
    @Builder.Default
    private String separadorCsv = ",";

    /** Endereço (devolvido por ArmazenamentoArquivoBruto.salvar) do último arquivo
     * enviado por upload — só relevante pra tipoAquisicao=UPLOAD_MANUAL. */
    @Column(name = "caminho_arquivo_upload", length = 500)
    private String caminhoArquivoUpload;

    // A coluna no banco e NUMBER(1), nao BOOLEAN. Esse conversor faz o Hibernate
    // ler/gravar 0 e 1 em vez de esperar um tipo booleano nativo.
    @Convert(converter = NumericBooleanConverter.class)
    @Builder.Default
    private Boolean ativo = true;

    @Column(name = "ultima_execucao_em")
    private LocalDateTime ultimaExecucaoEm;

    @Column(name = "ultima_duracao_ms")
    private Long ultimaDuracaoMs;

    /** SUCESSO | ERRO — nulo quando a fonte nunca foi executada ainda. */
    @Column(name = "ultimo_status_execucao", length = 20)
    private String ultimoStatusExecucao;

    /** Só preenchido quando ultimoStatusExecucao = ERRO. */
    @Column(name = "ultimo_erro_mensagem", length = 2000)
    private String ultimoErroMensagem;

    @Column(name = "proxima_execucao_em")
    private LocalDateTime proximaExecucaoEm;

    @Column(name = "data_add")
    private LocalDateTime dataAdd;
}
