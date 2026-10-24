package br.gov.sp.cps.datagis.gold;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Orquestra a transição Silver→Gold chamando as procedures PL/SQL (primeiro uso de
 * {@code CallableStatement} no projeto — não existe mock para PL/SQL,
 * {@code docs/gold-procedures-calculo-publicacao.md} §3.4). O "para cada imóvel,
 * calcule e publique" fica aqui em Java, não dentro do PL/SQL — evita dependência
 * circular entre {@code pkg_gold_publicacao} e os packages {@code pkg_calculo_*}
 * (eles chamam {@code registrar_resultado} de volta), mesmo papel de orquestrador que
 * o Java já tem no resto do projeto (Quartz/{@code AgendadorInicializacaoRunner}).
 *
 * <p>Não aborta a execução inteira por causa de 1 imóvel com problema — mesmo
 * princípio da ingestão (falha é logada e a próxima segue).
 *
 * <p><b>Publicação de camadas de mapa (bloco 2) simplifica geometria em Java/JTS,
 * não mais via {@code SDO_UTIL.SIMPLIFY} (PL/SQL) — LIZARDS-50.</b> Achado ao vivo
 * publicando DESMATAMENTO contra os 1.428 polígonos reais do PRODES: mesmo isolando
 * cada registro numa sessão Oracle própria (1 conexão nova por linha, com timeout),
 * algumas geometrias individuais (ex.: 634 mil ordenadas) fazem
 * {@code SDO_UTIL.SIMPLIFY} sozinho estourar {@code ORA-04036} (PGA_AGGREGATE_LIMIT
 * de 8GB) - não é demora, é a própria chamada excedendo o teto de memória do banco,
 * então nenhum ajuste de timeout/lote resolvia. {@link SimplificadorGeometriaMapa}
 * roda o mesmo algoritmo (Douglas-Peucker preservando topologia) no heap da JVM do
 * backend, memória separada do PGA do Oracle - decisão de negócio: nenhum registro
 * pode ser descartado por limitação de memória. Sem {@code SIMPLIFY} rodando no
 * banco, o que sobra do lado Oracle por registro é só um SELECT leve (buscar WKT) e
 * um INSERT - não acumula memória de sessão do jeito que operação de geometria
 * acumula, então voltou a usar o pool de conexões normal (Hikari) e páginas maiores,
 * sem precisar de conexão isolada nem timeout por registro.
 */
@Service
public class GoldPublicacaoService {

    private static final Logger log = LoggerFactory.getLogger(GoldPublicacaoService.class);

    public static final String STATUS_PROMOVIDO_GOLD = "PROMOVIDO_GOLD";

    /** Camadas semeadas em gold.mapa_camada (V018) — publicadas na mesma ordem fixa. */
    private static final List<String> CODIGOS_MAPA_CAMADA = List.of(
            "MUNICIPIO", "LIMITE_PR", "AREA_PROTEGIDA", "DESMATAMENTO", "EMBARGO", "IMOVEL_RURAL", "FOCO_CALOR");

    /** Contorno do Paraná (V080): 1 feature só, a união dos 399 municípios. */
    private static final String CAMADA_LIMITE_PR = "LIMITE_PR";

    /** ~50 m. A união sai com ~62 mil vértices; nos 5 m padrão ficariam ~26 mil
     *  (~1 MB pro navegador baixar), nos 50 m ~6,4 mil (medido no Oracle). É só a
     *  moldura do mapa, a diferença não aparece. */
    static final double TOLERANCIA_LIMITE_PR_GRAUS = 0.0005;

    /** Tabela Silver de origem de cada camada, só pra estimar o total no início (UX de andamento). */
    private static final Map<String, String> TABELA_SILVER_POR_CAMADA = Map.of(
            "MUNICIPIO", "silver.municipio",
            "AREA_PROTEGIDA", "silver.area_protegida",
            "DESMATAMENTO", "silver.desmatamento",
            "EMBARGO", "silver.embargo_ambiental",
            "IMOVEL_RURAL", "silver.imovel_rural",
            "FOCO_CALOR", "silver.foco_calor");

    /**
     * SELECT por camada (as 5 que passam por SIMPLIFY) - id (origem_silver_id),
     * cod_natural, rotulo, municipio_cod_ibge, wkt (via SDO_UTIL.TO_WKTGEOMETRY,
     * CLOB), fonte_dado_id, lote_ingestao_id, nessa ordem fixa (ver {@link #mapearLinha}).
     * Paginado com OFFSET/FETCH (2 binds no fim: offset, tamanho da página).
     * FOCO_CALOR não entra aqui - geometria é só Point, não passa por SIMPLIFY, segue
     * como INSERT...SELECT em massa (ver {@link #publicarFocoCalorEmMassa}).
     */
    private static final Map<String, String> SELECT_POR_CAMADA = Map.of(
            "MUNICIPIO", """
                SELECT id, TO_CHAR(cod_ibge) AS cod_natural, nome AS rotulo, cod_ibge AS municipio_cod_ibge,
                       SDO_UTIL.TO_WKTGEOMETRY(geometria) AS wkt, fonte_dado_id, lote_ingestao_id
                  FROM silver.municipio WHERE geometria IS NOT NULL
                 ORDER BY id OFFSET ? ROWS FETCH NEXT ? ROWS ONLY
                """,
            // Agregado sem GROUP BY = 1 linha; o OFFSET/FETCH so existe pra seguir o
            // mesmo laco paginado (a 2a pagina vem vazia e encerra). Sem id de origem:
            // nao e uma linha da Silver, e a uniao de todas.
            CAMADA_LIMITE_PR, """
                SELECT CAST(NULL AS NUMBER) AS id, 'PR' AS cod_natural, 'Paraná' AS rotulo,
                       CAST(NULL AS NUMBER) AS municipio_cod_ibge,
                       SDO_UTIL.TO_WKTGEOMETRY(SDO_AGGR_UNION(SDOAGGRTYPE(geometria, 0.05))) AS wkt,
                       MAX(fonte_dado_id) AS fonte_dado_id, MAX(lote_ingestao_id) AS lote_ingestao_id
                  FROM silver.municipio WHERE geometria IS NOT NULL
                 OFFSET ? ROWS FETCH NEXT ? ROWS ONLY
                """,
            "AREA_PROTEGIDA", """
                SELECT id, codigo_origem AS cod_natural, nome AS rotulo, CAST(NULL AS NUMBER) AS municipio_cod_ibge,
                       SDO_UTIL.TO_WKTGEOMETRY(geometria) AS wkt, fonte_dado_id, lote_ingestao_id
                  FROM silver.area_protegida WHERE geometria IS NOT NULL
                 ORDER BY id OFFSET ? ROWS FETCH NEXT ? ROWS ONLY
                """,
            "DESMATAMENTO", """
                SELECT id, identificador_origem AS cod_natural, ('PRODES ' || TO_CHAR(ano)) AS rotulo,
                       CAST(NULL AS NUMBER) AS municipio_cod_ibge,
                       SDO_UTIL.TO_WKTGEOMETRY(geometria) AS wkt, fonte_dado_id, lote_ingestao_id
                  FROM silver.desmatamento WHERE geometria IS NOT NULL
                 ORDER BY id OFFSET ? ROWS FETCH NEXT ? ROWS ONLY
                """,
            "EMBARGO", """
                SELECT e.id, e.numero_termo AS cod_natural, e.nome_autuado AS rotulo, m.cod_ibge AS municipio_cod_ibge,
                       SDO_UTIL.TO_WKTGEOMETRY(e.geometria) AS wkt, e.fonte_dado_id, e.lote_ingestao_id
                  FROM silver.embargo_ambiental e LEFT JOIN silver.municipio m ON m.id = e.municipio_id
                 WHERE e.geometria IS NOT NULL
                 ORDER BY e.id OFFSET ? ROWS FETCH NEXT ? ROWS ONLY
                """,
            "IMOVEL_RURAL", """
                SELECT ir.id, ir.cod_imovel AS cod_natural, ir.cod_imovel AS rotulo, m.cod_ibge AS municipio_cod_ibge,
                       SDO_UTIL.TO_WKTGEOMETRY(ir.geometria) AS wkt, ir.fonte_dado_id, ir.lote_ingestao_id
                  FROM silver.imovel_rural ir LEFT JOIN silver.municipio m ON m.id = ir.municipio_id
                 WHERE ir.geometria IS NOT NULL
                 ORDER BY ir.id OFFSET ? ROWS FETCH NEXT ? ROWS ONLY
                """);

    private static final String INSERT_FEATURE_SQL = """
            INSERT INTO gold.feature_publicada
                (mapa_camada_id, cod_natural, origem_silver_id, rotulo, municipio_cod_ibge,
                 geometria, versao_publicacao, fonte_dado_id, lote_ingestao_id)
            VALUES (?, ?, ?, ?, ?, SDO_GEOMETRY(?, 4674), ?, ?, ?)
            """;

    /** Páginas de leitura da Silver - só SELECT leve (TO_WKTGEOMETRY) + INSERT, sem
     *  risco de memória (SIMPLIFY não roda mais no Oracle), pode ser grande. */
    private static final int TAMANHO_PAGINA = 200;

    private final JdbcTemplate jdbcTemplate;
    private final SimplificadorGeometriaMapa simplificador;
    private final int paralelismo;

    public GoldPublicacaoService(
            JdbcTemplate jdbcTemplate,
            SimplificadorGeometriaMapa simplificador,
            @Value("${app.gold.paralelismo:1}") int paralelismo) {
        this.jdbcTemplate = jdbcTemplate;
        this.simplificador = simplificador;
        this.paralelismo = paralelismo;
    }

    /**
     * Calcula os 7 indicadores (ISAP, IAE, IRL, IAPP, IDesmat, IFC, ICV — todos reais
     * desde {@code V072}/{@code V073}, LIZARDS-24) e publica o detalhe congelado para
     * 1 imóvel.
     */
    public void recalcularImovel(String codImovel) {
        chamarCalculo("pkg_calculo_territorial", "calcular_isap", codImovel);
        chamarCalculo("pkg_calculo_territorial", "calcular_iae", codImovel);
        chamarCalculo("pkg_calculo_territorial", "calcular_irl", codImovel);
        chamarCalculo("pkg_calculo_territorial", "calcular_iapp", codImovel);
        chamarCalculo("pkg_calculo_ambiental", "calcular_idesmat", codImovel);
        chamarCalculo("pkg_calculo_ambiental", "calcular_ifc", codImovel);
        chamarCalculo("pkg_calculo_conservacao", "calcular_icv", codImovel);
        chamarPublicarImovel(codImovel);
    }

    /** Progresso em {@code gold.publicacao_indicadores} é atualizado a cada N imóveis - frequente o
     *  suficiente pra uma tela de acompanhamento ver andamento real, sem gravar a cada imóvel. */
    private static final int LOTE_PROGRESSO_INDICADORES = 50;

    /**
     * Recalcula todo {@code silver.imovel_rural} e publica todas as camadas de mapa
     * ativas. Pode levar minutos num volume real - pensado pra disparo manual ou
     * agendado, não pra resposta HTTP instantânea. Status/andamento consultável em
     * {@code gold.publicacao_indicadores} (ver {@link #obterStatusPublicacaoIndicadores()})
     * - importante pra uma futura tela de acompanhamento de processos.
     */
    public ResultadoPublicacaoTudo publicarTudo() {
        List<String> codigosImovel = jdbcTemplate.queryForList(
                "SELECT cod_imovel FROM silver.imovel_rural", String.class);

        long publicacaoIndicadoresId = inserirControlePublicacaoIndicadores(codigosImovel.size());

        AtomicInteger sucesso = new AtomicInteger();
        List<String> falhas = Collections.synchronizedList(new ArrayList<>());
        try {
            calcularIndicadoresEmParalelo(codigosImovel, publicacaoIndicadoresId, sucesso, falhas);

            jdbcTemplate.update(
                    """
                    UPDATE gold.publicacao_indicadores
                       SET status = 'CONCLUIDA', concluido_em = SYSTIMESTAMP
                     WHERE id = ?
                    """,
                    publicacaoIndicadoresId);
        } catch (Exception e) {
            // So chega aqui por falha de infraestrutura (ex.: conexao caiu) - falha
            // de 1 imovel especifico ja e tratada dentro de calcularIndicadoresEmParalelo
            // e nao propaga ate aqui.
            log.warn("publicarTudo: recalculo de indicadores falhou de vez", e);
            jdbcTemplate.update(
                    """
                    UPDATE gold.publicacao_indicadores
                       SET status = 'FALHOU', mensagem_erro = ?, concluido_em = SYSTIMESTAMP
                     WHERE id = ?
                    """,
                    truncar(e.getMessage(), 4000), publicacaoIndicadoresId);
            throw new IllegalStateException("Falha no recálculo de indicadores", e);
        }

        List<String> camadasPublicadas = new ArrayList<>();
        List<String> camadasComFalha = new ArrayList<>();
        for (String codigoCamada : CODIGOS_MAPA_CAMADA) {
            try {
                publicarCamadaMapaEmLotes(codigoCamada);
                camadasPublicadas.add(codigoCamada);
            } catch (Exception e) {
                log.warn("publicarTudo: falhou a camada {}: {}", codigoCamada, e.getMessage());
                camadasComFalha.add(codigoCamada);
            }
        }

        try {
            jdbcTemplate.update("UPDATE controle.lote_ingestao SET status = ? WHERE status = 'PROMOVIDO_SILVER'", STATUS_PROMOVIDO_GOLD);
        } catch (Exception e) {
            log.warn("publicarTudo: erro ao atualizar status dos lotes para PROMOVIDO_GOLD: {}", e.getMessage());
        }

        return new ResultadoPublicacaoTudo(
                codigosImovel.size(), sucesso.get(), new ArrayList<>(falhas), camadasPublicadas, camadasComFalha);
    }

    /**
     * Calcula os indicadores de cada imóvel em {@link #paralelismo} threads
     * (docs/publicacao-gold-paralela.md). Cada imóvel é independente nas procedures
     * (§4 do doc - sem UPDATE de linha compartilhada, sem LOCK), então dividir a lista
     * sem repetição é suficiente pra não haver disputa entre as threads. Com
     * {@code paralelismo = 1} o {@link Executors#newFixedThreadPool} processa a fila
     * em ordem de chegada - mesmo comportamento de hoje, só trocando de forma.
     *
     * <p>Progresso: {@code processadosTotal} é um contador atômico único pra todas as
     * threads - como cada chamada de {@code incrementAndGet()} devolve um valor
     * diferente, só uma thread vê cada múltiplo exato de {@link #LOTE_PROGRESSO_INDICADORES},
     * então o UPDATE em {@code gold.publicacao_indicadores} dispara exatamente 1 vez a
     * cada N processados, não importa a ordem de chegada das threads. O resto (menos
     * que N desde o último múltiplo) é gravado depois que todas as tarefas terminam.
     */
    private void calcularIndicadoresEmParalelo(
            List<String> codigosImovel, long publicacaoIndicadoresId, AtomicInteger sucesso, List<String> falhas) {
        AtomicInteger processadosTotal = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(
                paralelismo, Thread.ofPlatform().name("gold-calculo-", 1).factory());
        try {
            List<Future<?>> tarefas = codigosImovel.stream()
                    .<Future<?>>map(codImovel -> executor.submit(() -> {
                        try {
                            recalcularImovel(codImovel);
                            sucesso.incrementAndGet();
                        } catch (Exception e) {
                            log.warn("publicarTudo: falhou pro imóvel {}: {}", codImovel, e.getMessage());
                            falhas.add(codImovel);
                            jdbcTemplate.update(
                                    """
                                    INSERT INTO gold.publicacao_indicadores_erro
                                        (publicacao_indicadores_id, cod_imovel, motivo)
                                    VALUES (?, ?, ?)
                                    """,
                                    publicacaoIndicadoresId, codImovel, truncar(e.getMessage(), 500));
                        }
                        if (processadosTotal.incrementAndGet() % LOTE_PROGRESSO_INDICADORES == 0) {
                            atualizarProgressoIndicadores(
                                    publicacaoIndicadoresId, LOTE_PROGRESSO_INDICADORES, sucesso.get(), falhas.size());
                        }
                    }))
                    .toList();

            for (Future<?> tarefa : tarefas) {
                try {
                    tarefa.get();
                } catch (Exception e) {
                    // Falha de infraestrutura real (falha de 1 imovel ja foi tratada
                    // dentro da tarefa acima e nunca chega aqui) - cancela o resto e
                    // deixa o chamador marcar a publicacao como FALHOU.
                    tarefas.forEach(t -> t.cancel(true));
                    throw new IllegalStateException("Falha processando imóveis em paralelo", e);
                }
            }
        } finally {
            executor.shutdown();
        }

        int restante = processadosTotal.get() % LOTE_PROGRESSO_INDICADORES;
        if (restante > 0) {
            atualizarProgressoIndicadores(publicacaoIndicadoresId, restante, sucesso.get(), falhas.size());
        }
    }

    /**
     * Disparo padrão de publicação da camada Gold, delegando para {@link #publicarTudo()}.
     */
    public ResultadoPublicacaoTudo publicar() {
        return publicarTudo();
    }

    /**
     * Executa a publicação consolidada da camada Gold de forma assíncrona,
     * desacoplada da thread HTTP.
     */
    @org.springframework.scheduling.annotation.Async("datagisTaskExecutor")
    public void publicarTudoAssincrono() {
        try {
            publicarTudo();
        } catch (Exception e) {
            log.error("Erro na publicação assíncrona da camada Gold: {}", e.getMessage(), e);
        }
    }

    /**
     * Publica 1 camada de mapa (ver javadoc da classe). A versão nova só fica
     * "visível" (status CONCLUIDA em {@code gold.publicacao_camada_mapa}) se
     * processar inteira - cada publicação é uma versão absoluta, nunca pode ficar
     * visível pela metade (decisão de negócio: quem for ler essa tabela no futuro
     * filtra por {@code status = 'CONCLUIDA'}, nunca por
     * {@code MAX(versao_publicacao)} direto em {@code feature_publicada}). Se
     * quebrar de vez (ex.: erro de conexão no meio), a versão nova é marcada FALHOU
     * e as linhas órfãs dela são apagadas - a versão anterior (já CONCLUIDA)
     * continua valendo.
     */
    public void publicarCamadaMapaEmLotes(String codigoCamada) {
        Long mapaCamadaId = jdbcTemplate.queryForObject(
                "SELECT id FROM gold.mapa_camada WHERE codigo = ? AND ativo = 1",
                Long.class, codigoCamada);

        long novaVersao = jdbcTemplate.queryForObject(
                // GREATEST com feature_publicada, nao so publicacao_camada_mapa: ha
                // dado antigo publicado ANTES dessa tabela de controle existir
                // (primeira rodada da sessao, ainda pelo pkg_gold_publicacao.
                // publicar_camada_mapa de entao) - confiar so em
                // publicacao_camada_mapa gerava "proxima versao = 1" de novo,
                // colidindo (ORA-00001 em UQ_FEATURE_PUBLICADA) com esse dado
                // antigo que ja estava na versao 1. Achado ao vivo publicando
                // MUNICIPIO/AREA_PROTEGIDA/IMOVEL_RURAL (LIZARDS-50).
                """
                SELECT GREATEST(
                    (SELECT COALESCE(MAX(versao_publicacao), 0) FROM gold.publicacao_camada_mapa WHERE mapa_camada_id = ?),
                    (SELECT COALESCE(MAX(versao_publicacao), 0) FROM gold.feature_publicada WHERE mapa_camada_id = ?)
                ) + 1 FROM dual
                """,
                Long.class, mapaCamadaId, mapaCamadaId);

        Long totalEstimado = contarTotalSilver(codigoCamada);
        long publicacaoId = inserirControlePublicacao(mapaCamadaId, novaVersao, totalEstimado);

        try {
            if ("FOCO_CALOR".equals(codigoCamada)) {
                int publicados = publicarFocoCalorEmMassa(mapaCamadaId, novaVersao);
                atualizarProgresso(publicacaoId, publicados, publicados, 0);
            } else {
                String selectSql = SELECT_POR_CAMADA.get(codigoCamada);
                if (selectSql == null) {
                    throw new IllegalArgumentException("Camada desconhecida: " + codigoCamada);
                }
                long offset = 0;
                while (true) {
                    List<LinhaSilver> linhas = jdbcTemplate.query(selectSql, GoldPublicacaoService::mapearLinha, offset, TAMANHO_PAGINA);
                    if (linhas.isEmpty()) {
                        break;
                    }

                    int publicados = 0;
                    int pulados = 0;
                    for (LinhaSilver linha : linhas) {
                        try {
                            String wktSimplificado = CAMADA_LIMITE_PR.equals(codigoCamada)
                                    ? simplificador.simplificarContornoExterno(linha.wkt(), TOLERANCIA_LIMITE_PR_GRAUS)
                                    : simplificador.simplificar(linha.wkt());
                            inserirFeaturePublicada(mapaCamadaId, linha, wktSimplificado, novaVersao);
                            publicados++;
                        } catch (Exception e) {
                            pulados++;
                            jdbcTemplate.update(
                                    """
                                    INSERT INTO gold.publicacao_camada_mapa_erro
                                        (publicacao_camada_mapa_id, origem_silver_id, identificador, motivo)
                                    VALUES (?, ?, ?, ?)
                                    """,
                                    publicacaoId, linha.id(), linha.codNatural(), truncar(e.getMessage(), 500));
                        }
                    }

                    atualizarProgresso(publicacaoId, linhas.size(), publicados, pulados);
                    offset += TAMANHO_PAGINA;
                }
            }

            jdbcTemplate.update(
                    """
                    UPDATE gold.publicacao_camada_mapa
                       SET status = 'CONCLUIDA', concluido_em = SYSTIMESTAMP
                     WHERE id = ?
                    """,
                    publicacaoId);

            log.info("publicarCamadaMapaEmLotes: camada {} concluída, versão {}", codigoCamada, novaVersao);
        } catch (Exception e) {
            log.warn("publicarCamadaMapaEmLotes: camada {} falhou na versão {}", codigoCamada, novaVersao, e);
            jdbcTemplate.update(
                    """
                    UPDATE gold.publicacao_camada_mapa
                       SET status = 'FALHOU', mensagem_erro = ?, concluido_em = SYSTIMESTAMP
                     WHERE id = ?
                    """,
                    truncar(e.getMessage(), 4000), publicacaoId);
            // Versao nova nunca fica visivel pela metade - apaga os orfaos, a versao
            // anterior (CONCLUIDA) continua sendo a valida pra quem for ler depois.
            jdbcTemplate.update(
                    "DELETE FROM gold.feature_publicada WHERE mapa_camada_id = ? AND versao_publicacao = ?",
                    mapaCamadaId, novaVersao);
            throw new IllegalStateException("Falha publicando camada " + codigoCamada, e);
        }
    }

    /**
     * O WKT simplificado pode passar de 4000 caracteres pra geometria complexa
     * (mesmo depois de simplificada) - bind de String comum (setString) dá
     * ORA-01461 nesse caso (achado ao vivo publicando DESMATAMENTO: 13 de 1.428).
     * Mesmo padrão já usado em PersistidorSilverService pra WKT grande: bind via
     * java.sql.Clob, não String direto.
     */
    private void inserirFeaturePublicada(long mapaCamadaId, LinhaSilver linha, String wktSimplificado, long novaVersao) {
        jdbcTemplate.execute((Connection con) -> {
            java.sql.Clob clob = con.createClob();
            try (var ps = con.prepareStatement(INSERT_FEATURE_SQL)) {
                clob.setString(1, wktSimplificado);
                ps.setLong(1, mapaCamadaId);
                ps.setString(2, linha.codNatural());
                if (linha.id() == null) {
                    ps.setNull(3, Types.NUMERIC);
                } else {
                    ps.setLong(3, linha.id());
                }
                ps.setString(4, linha.rotulo());
                if (linha.municipioCodIbge() == null) {
                    ps.setNull(5, Types.NUMERIC);
                } else {
                    ps.setLong(5, linha.municipioCodIbge());
                }
                ps.setClob(6, clob);
                ps.setLong(7, novaVersao);
                if (linha.fonteDadoId() == null) {
                    ps.setNull(8, Types.NUMERIC);
                } else {
                    ps.setLong(8, linha.fonteDadoId());
                }
                if (linha.loteIngestaoId() == null) {
                    ps.setNull(9, Types.NUMERIC);
                } else {
                    ps.setLong(9, linha.loteIngestaoId());
                }
                ps.executeUpdate();
                return null;
            } finally {
                try {
                    clob.free();
                } catch (Exception ignored) {
                    // nada a fazer
                }
            }
        });
    }

    /** FOCO_CALOR é só Point (sem risco de SIMPLIFY pesado) - INSERT...SELECT em massa, sem passar por Java/JTS. */
    private int publicarFocoCalorEmMassa(Long mapaCamadaId, long novaVersao) {
        return jdbcTemplate.update(
                """
                INSERT INTO gold.feature_publicada
                    (mapa_camada_id, cod_natural, origem_silver_id, rotulo, municipio_cod_ibge,
                     geometria, versao_publicacao, fonte_dado_id, lote_ingestao_id)
                SELECT ?, f.identificador_origem, f.id, 'Foco ' || TO_CHAR(f.data_hora, 'DD/MM/YYYY'), m.cod_ibge,
                       f.geometria, ?, f.fonte_dado_id, f.lote_ingestao_id
                  FROM silver.foco_calor f
                  LEFT JOIN silver.municipio m ON m.id = f.municipio_id
                 WHERE f.geometria IS NOT NULL
                """,
                mapaCamadaId, novaVersao);
    }

    private void atualizarProgresso(long publicacaoId, int processados, int publicados, int pulados) {
        jdbcTemplate.update(
                """
                UPDATE gold.publicacao_camada_mapa
                   SET total_processado = total_processado + ?,
                       total_publicado = total_publicado + ?,
                       total_pulado = total_pulado + ?
                 WHERE id = ?
                """,
                processados, publicados, pulados, publicacaoId);
    }

    private Long contarTotalSilver(String codigoCamada) {
        if (CAMADA_LIMITE_PR.equals(codigoCamada)) {
            return 1L;
        }
        String tabela = TABELA_SILVER_POR_CAMADA.get(codigoCamada);
        if (tabela == null) {
            return null;
        }
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + tabela + " WHERE geometria IS NOT NULL", Long.class);
    }

    private long inserirControlePublicacaoIndicadores(int totalEstimado) {
        jdbcTemplate.update(
                "INSERT INTO gold.publicacao_indicadores (status, total_estimado) VALUES ('EM_ANDAMENTO', ?)",
                totalEstimado);
        return jdbcTemplate.queryForObject(
                "SELECT MAX(id) FROM gold.publicacao_indicadores", Long.class);
    }

    private void atualizarProgressoIndicadores(long publicacaoIndicadoresId, int processados, int sucessoAcumulado, int falhaAcumulada) {
        jdbcTemplate.update(
                """
                UPDATE gold.publicacao_indicadores
                   SET total_processado = total_processado + ?,
                       total_sucesso = ?,
                       total_falha = ?
                 WHERE id = ?
                """,
                processados, sucessoAcumulado, falhaAcumulada, publicacaoIndicadoresId);
    }

    /** "NUNCA_EXECUTADO" quando o recálculo nunca rodou ainda - nunca 404/500, sempre uma resposta consultável. */
    public StatusPublicacaoIndicadores obterStatusPublicacaoIndicadores() {
        List<StatusPublicacaoIndicadores> linhas = jdbcTemplate.query(
                """
                SELECT id, status, total_estimado, total_processado, total_sucesso, total_falha,
                       mensagem_erro, iniciado_em, concluido_em
                  FROM gold.publicacao_indicadores
                 ORDER BY id DESC
                 FETCH FIRST 1 ROW ONLY
                """,
                (rs, rowNum) -> {
                    Number totalEstimado = (Number) rs.getObject("total_estimado");
                    java.sql.Timestamp iniciadoEm = rs.getTimestamp("iniciado_em");
                    java.sql.Timestamp concluidoEm = rs.getTimestamp("concluido_em");
                    return new StatusPublicacaoIndicadores(
                            rs.getLong("id"),
                            rs.getString("status"),
                            totalEstimado == null ? null : totalEstimado.longValue(),
                            rs.getLong("total_processado"),
                            rs.getLong("total_sucesso"),
                            rs.getLong("total_falha"),
                            rs.getString("mensagem_erro"),
                            iniciadoEm == null ? null : iniciadoEm.toLocalDateTime(),
                            concluidoEm == null ? null : concluidoEm.toLocalDateTime());
                });
        return linhas.isEmpty()
                ? new StatusPublicacaoIndicadores(0, "NUNCA_EXECUTADO", null, 0, 0, 0, null, null, null)
                : linhas.get(0);
    }

    /**
     * Status consolidado de TUDO que é publicação Gold (indicadores + as 6 camadas
     * de mapa) numa consulta só - pensado pra uma futura tela de acompanhamento de
     * processos não precisar fazer 7 chamadas separadas (CLAUDE.md §9.38).
     */
    public StatusGoldConsolidado obterStatusConsolidado() {
        StatusPublicacaoIndicadores indicadores = obterStatusPublicacaoIndicadores();
        List<StatusPublicacaoCamada> camadas = CODIGOS_MAPA_CAMADA.stream()
                .map(this::obterStatusPublicacao)
                .toList();
        boolean algumEmAndamento = "EM_ANDAMENTO".equals(indicadores.status())
                || camadas.stream().anyMatch(c -> "EM_ANDAMENTO".equals(c.status()));
        return new StatusGoldConsolidado(algumEmAndamento, indicadores, camadas);
    }

    /** Todo imóvel que falhou numa execução de recálculo - sempre com motivo. */
    public List<ErroPublicacao> listarErrosIndicadores(long publicacaoIndicadoresId) {
        return jdbcTemplate.query(
                """
                SELECT cod_imovel, motivo, data_add
                  FROM gold.publicacao_indicadores_erro
                 WHERE publicacao_indicadores_id = ?
                 ORDER BY id
                """,
                (rs, rowNum) -> {
                    java.sql.Timestamp dataAdd = rs.getTimestamp("data_add");
                    return new ErroPublicacao(
                            null,
                            rs.getString("cod_imovel"),
                            rs.getString("motivo"),
                            dataAdd == null ? null : dataAdd.toLocalDateTime());
                },
                publicacaoIndicadoresId);
    }

    private long inserirControlePublicacao(Long mapaCamadaId, long versaoPublicacao, Long totalEstimado) {
        jdbcTemplate.update(
                """
                INSERT INTO gold.publicacao_camada_mapa
                    (mapa_camada_id, versao_publicacao, status, total_estimado)
                VALUES (?, ?, 'EM_ANDAMENTO', ?)
                """,
                mapaCamadaId, versaoPublicacao, totalEstimado);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM gold.publicacao_camada_mapa WHERE mapa_camada_id = ? AND versao_publicacao = ?",
                Long.class, mapaCamadaId, versaoPublicacao);
    }

    private static LinhaSilver mapearLinha(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        Number id = (Number) rs.getObject("id");
        Number municipioCodIbge = (Number) rs.getObject("municipio_cod_ibge");
        Number fonteDadoId = (Number) rs.getObject("fonte_dado_id");
        Number loteIngestaoId = (Number) rs.getObject("lote_ingestao_id");
        return new LinhaSilver(
                id == null ? null : id.longValue(),
                rs.getString("cod_natural"),
                rs.getString("rotulo"),
                municipioCodIbge == null ? null : municipioCodIbge.longValue(),
                rs.getString("wkt"),
                fonteDadoId == null ? null : fonteDadoId.longValue(),
                loteIngestaoId == null ? null : loteIngestaoId.longValue());
    }

    private record LinhaSilver(
            Long id,
            String codNatural,
            String rotulo,
            Long municipioCodIbge,
            String wkt,
            Long fonteDadoId,
            Long loteIngestaoId
    ) {}

    /** "NUNCA_EXECUTADO" quando a camada nunca foi publicada ainda - nunca 404/500, sempre uma resposta consultável. */
    public StatusPublicacaoCamada obterStatusPublicacao(String codigoCamada) {
        List<StatusPublicacaoCamada> linhas = jdbcTemplate.query(
                """
                SELECT p.id, p.versao_publicacao, p.status, p.total_estimado, p.total_processado,
                       p.total_publicado, p.total_pulado, p.mensagem_erro, p.iniciado_em, p.concluido_em
                  FROM gold.publicacao_camada_mapa p
                  JOIN gold.mapa_camada mc ON mc.id = p.mapa_camada_id
                 WHERE mc.codigo = ?
                 ORDER BY p.versao_publicacao DESC
                 FETCH FIRST 1 ROW ONLY
                """,
                (rs, rowNum) -> {
                    Number totalEstimado = (Number) rs.getObject("total_estimado");
                    java.sql.Timestamp iniciadoEm = rs.getTimestamp("iniciado_em");
                    java.sql.Timestamp concluidoEm = rs.getTimestamp("concluido_em");
                    return new StatusPublicacaoCamada(
                            rs.getLong("id"),
                            codigoCamada,
                            rs.getLong("versao_publicacao"),
                            rs.getString("status"),
                            totalEstimado == null ? null : totalEstimado.longValue(),
                            rs.getLong("total_processado"),
                            rs.getLong("total_publicado"),
                            rs.getLong("total_pulado"),
                            rs.getString("mensagem_erro"),
                            iniciadoEm == null ? null : iniciadoEm.toLocalDateTime(),
                            concluidoEm == null ? null : concluidoEm.toLocalDateTime());
                },
                codigoCamada);
        return linhas.isEmpty() ? statusCamadaNuncaExecutado(codigoCamada) : linhas.get(0);
    }

    private static StatusPublicacaoCamada statusCamadaNuncaExecutado(String codigoCamada) {
        return new StatusPublicacaoCamada(0, codigoCamada, 0, "NUNCA_EXECUTADO", null, 0, 0, 0, null, null, null);
    }

    /** Todo registro pulado/falhado numa execução de publicação - sempre com motivo (ver V067). */
    public List<ErroPublicacao> listarErros(long publicacaoId) {
        return jdbcTemplate.query(
                """
                SELECT origem_silver_id, identificador, motivo, data_add
                  FROM gold.publicacao_camada_mapa_erro
                 WHERE publicacao_camada_mapa_id = ?
                 ORDER BY id
                """,
                (rs, rowNum) -> {
                    Number origemSilverId = (Number) rs.getObject("origem_silver_id");
                    java.sql.Timestamp dataAdd = rs.getTimestamp("data_add");
                    return new ErroPublicacao(
                            origemSilverId == null ? null : origemSilverId.longValue(),
                            rs.getString("identificador"),
                            rs.getString("motivo"),
                            dataAdd == null ? null : dataAdd.toLocalDateTime());
                },
                publicacaoId);
    }

    private static String truncar(String texto, int tamanho) {
        if (texto == null) {
            return null;
        }
        return texto.length() > tamanho ? texto.substring(0, tamanho) : texto;
    }

    private Long chamarCalculo(String pacote, String procedure, String codImovel) {
        return jdbcTemplate.execute((Connection con) -> {
            try (CallableStatement cs = con.prepareCall(
                    "{call gold." + pacote + "." + procedure + "(?, ?, ?)}")) {
                cs.setString(1, codImovel);
                cs.setNull(2, Types.NUMERIC); // p_regra_calculo_id = NULL -> usa a vigente
                cs.registerOutParameter(3, Types.NUMERIC);
                cs.execute();
                long resultado = cs.getLong(3);
                return cs.wasNull() ? null : resultado;
            }
        });
    }

    private void chamarPublicarImovel(String codImovel) {
        jdbcTemplate.execute((Connection con) -> {
            try (CallableStatement cs = con.prepareCall(
                    "{call gold.pkg_gold_publicacao.publicar_imovel(?)}")) {
                cs.setString(1, codImovel);
                cs.execute();
                return null;
            }
        });
    }

    public record ResultadoPublicacaoTudo(
            int totalImoveis,
            int imoveisPublicados,
            List<String> imoveisComFalha,
            List<String> camadasPublicadas,
            List<String> camadasComFalha
    ) {}

    public record StatusPublicacaoCamada(
            long publicacaoId,
            String codigoCamada,
            long versaoPublicacao,
            String status,
            Long totalEstimado,
            long totalProcessado,
            long totalPublicado,
            long totalPulado,
            String mensagemErro,
            LocalDateTime iniciadoEm,
            LocalDateTime concluidoEm
    ) {}

    public record ErroPublicacao(
            Long origemSilverId,
            String identificador,
            String motivo,
            LocalDateTime dataAdd
    ) {}

    public record StatusPublicacaoIndicadores(
            long publicacaoId,
            String status,
            Long totalEstimado,
            long totalProcessado,
            long totalSucesso,
            long totalFalha,
            String mensagemErro,
            LocalDateTime iniciadoEm,
            LocalDateTime concluidoEm
    ) {}

    /** Visão consolidada de tudo que é publicação Gold - ver {@link #obterStatusConsolidado()}. */
    public record StatusGoldConsolidado(
            boolean algumEmAndamento,
            StatusPublicacaoIndicadores indicadores,
            List<StatusPublicacaoCamada> camadasMapa
    ) {}
}
