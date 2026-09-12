package br.gov.sp.cps.datagis.ingestao;

import br.gov.sp.cps.datagis.bronze.RegistroBruto;
import br.gov.sp.cps.datagis.bronze.RegistroBrutoRepository;
import br.gov.sp.cps.datagis.controle.FonteDado;
import br.gov.sp.cps.datagis.controle.LoteIngestao;
import br.gov.sp.cps.datagis.controle.LoteIngestaoRepository;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ciclo de vida comum a QUALQUER ingestão, independente do formato de origem: hashear
 * o conteúdo relevante, decidir se mudou desde o último lote, criar o lote e gravar os
 * registros brutos. Cada {@link IngestaoExecutor} só precisa buscar e converter os
 * dados da fonte em {@link RegistroPreparado} — o resto é sempre igual, então mora
 * aqui uma vez só.
 */
@Component
public class LoteIngestaoWriter {

    private static final String STATUS_CONCLUIDO = "CONCLUIDO";
    private static final String STATUS_SEM_MUDANCA = "SEM_MUDANCA";
    private static final String STATUS_EM_PROCESSAMENTO = "EM_PROCESSAMENTO";

    /**
     * A cada N registros gravados em {@link #gravarStreaming}, força um flush + clear
     * do persistence context do Hibernate. Sem isso, mesmo gravando um registro por vez
     * (sem lista em memória do lado da aplicação), o Hibernate mantém toda entidade
     * como "managed" na sessão até o fim da transação — pra uma fonte de milhões de
     * linhas isso vira o novo estouro de heap, só que dentro do JPA em vez da aplicação.
     */
    private static final int TAMANHO_LOTE_FLUSH = 500;

    private final LoteIngestaoRepository loteIngestaoRepository;
    private final RegistroBrutoRepository registroBrutoRepository;
    private final EntityManager entityManager;

    public LoteIngestaoWriter(
            LoteIngestaoRepository loteIngestaoRepository,
            RegistroBrutoRepository registroBrutoRepository,
            EntityManager entityManager) {
        this.loteIngestaoRepository = loteIngestaoRepository;
        this.registroBrutoRepository = registroBrutoRepository;
        this.entityManager = entityManager;
    }

    /**
     * @param conteudoParaHash só a parte do corpo bruto que representa dado de verdade
     *                         (ex.: as features de um GeoJSON, sem o envelope que traz
     *                         timestamp variável a cada chamada — ver RN018/§2.8).
     * @param registros        já prontos; calculado só se o hash indicar mudança.
     */
    /**
     * Decide se o conteúdo mudou desde o último lote da fonte, SEM gravar nada.
     *
     * <p>Existe pra quem tiver que converter cada registro antes de poder montar
     * {@code conteudoParaHash} de verdade (ex.: {@link IngestaoGeoJsonExecutor}, que
     * precisa serializar JSON e converter geometria pra WKT feature a feature) poder
     * checar "mudou?" primeiro e pular esse trabalho inteiro quando a resposta for não —
     * achado ao vivo com o circuito do CAR/PR: a conversão de ~4.590 features levava a
     * maior parte do tempo de uma execução, mesmo quando o resultado era
     * {@code SEM_MUDANCA} e nada daquilo era usado.
     */
    public boolean mudou(FonteDado fonte, String conteudoParaHash) {
        String hash = sha256Hex(conteudoParaHash);
        return loteIngestaoRepository
                .findFirstByFonteDadoIdOrderByIdDesc(fonte.getId())
                .map(anterior -> !hash.equals(anterior.getHashConteudo()))
                .orElse(true);
    }

    /**
     * Grava um lote {@code SEM_MUDANCA} direto, sem nenhum {@link RegistroPreparado} —
     * usar depois de {@link #mudou} já ter confirmado que não há nada de novo.
     */
    @Transactional
    public LoteIngestao gravarSemMudanca(FonteDado fonte, String conteudoParaHash) {
        LoteIngestao loteSemMudanca = LoteIngestao.builder()
                .fonteDado(fonte)
                .origemReferencia(fonte.getUrlOrigem())
                .hashConteudo(sha256Hex(conteudoParaHash))
                .quantidadeRegistros(0)
                .status(STATUS_SEM_MUDANCA)
                .dataExecucao(LocalDateTime.now())
                .build();
        return loteIngestaoRepository.save(loteSemMudanca);
    }

    @Transactional
    public LoteIngestao gravar(FonteDado fonte, String conteudoParaHash, List<RegistroPreparado> registros) {
        String hash = sha256Hex(conteudoParaHash);

        boolean semMudanca = loteIngestaoRepository
                .findFirstByFonteDadoIdOrderByIdDesc(fonte.getId())
                .map(anterior -> hash.equals(anterior.getHashConteudo()))
                .orElse(false);

        if (semMudanca) {
            LoteIngestao loteSemMudanca = LoteIngestao.builder()
                    .fonteDado(fonte)
                    .origemReferencia(fonte.getUrlOrigem())
                    .hashConteudo(hash)
                    .quantidadeRegistros(0)
                    .status(STATUS_SEM_MUDANCA)
                    .dataExecucao(LocalDateTime.now())
                    .build();
            return loteIngestaoRepository.save(loteSemMudanca);
        }

        LoteIngestao lote = LoteIngestao.builder()
                .fonteDado(fonte)
                .origemReferencia(fonte.getUrlOrigem())
                .hashConteudo(hash)
                .status(STATUS_EM_PROCESSAMENTO)
                .dataExecucao(LocalDateTime.now())
                .build();
        lote = loteIngestaoRepository.save(lote);

        int contador = 0;
        for (RegistroPreparado registro : registros) {
            RegistroBruto bruto = RegistroBruto.builder()
                    .loteIngestaoId(lote.getId())
                    .fonteDadoId(fonte.getId())
                    .numeroLinha(contador)
                    .payload(registro.payloadJson())
                    .geometriaWkt(registro.geometriaWkt())
                    .dataIngestao(LocalDateTime.now())
                    .build();
            registroBrutoRepository.save(bruto);
            contador++;
        }

        lote.setQuantidadeRegistros(contador);
        lote.setStatus(STATUS_CONCLUIDO);
        return loteIngestaoRepository.save(lote);
    }

    /**
     * Mesmo ciclo de vida de {@link #gravar}, mas pra fontes grandes demais pra
     * materializar numa {@code List} antes de gravar (achado ao vivo com um Shapefile
     * de 3,24 GB — ver {@link FornecedorRegistros}). Grava cada registro assim que
     * chega, com flush/clear periódico do Hibernate (ver {@link #TAMANHO_LOTE_FLUSH}).
     *
     * <p>Só se sabe se "mudou desde o último lote" depois de ler tudo — igual no
     * método antigo — só que aqui isso significa gravar tudo primeiro; se o hash final
     * bater com o do lote anterior, os registros recém-gravados são apagados em bloco
     * (um {@code DELETE} por {@code lote_ingestao_id}, não um por linha) e o lote vira
     * {@code SEM_MUDANCA}. É mais I/O que o caminho antigo no caso "sem mudança", mas
     * não precisa mais caber a fonte inteira na memória pra chegar nessa resposta.
     */
    @Transactional
    public LoteIngestao gravarStreaming(FonteDado fonte, FornecedorRegistros fornecedor) {
        String hashAnterior = loteIngestaoRepository
                .findFirstByFonteDadoIdOrderByIdDesc(fonte.getId())
                .map(LoteIngestao::getHashConteudo)
                .orElse(null);

        LoteIngestao lote = LoteIngestao.builder()
                .fonteDado(fonte)
                .origemReferencia(fonte.getCaminhoArquivoUpload())
                .status(STATUS_EM_PROCESSAMENTO)
                .dataExecucao(LocalDateTime.now())
                .build();
        lote = loteIngestaoRepository.save(lote);
        Long loteId = lote.getId();

        MessageDigest digest = criarDigestSha256();
        int[] contador = {0};
        fornecedor.fornecer(registro -> {
            if (contador[0] > 0) {
                digest.update((byte) '\n');
            }
            digest.update(registro.payloadJson().getBytes(StandardCharsets.UTF_8));

            RegistroBruto bruto = RegistroBruto.builder()
                    .loteIngestaoId(loteId)
                    .fonteDadoId(fonte.getId())
                    .numeroLinha(contador[0])
                    .payload(registro.payloadJson())
                    .geometriaWkt(registro.geometriaWkt())
                    .dataIngestao(LocalDateTime.now())
                    .build();
            registroBrutoRepository.save(bruto);
            contador[0]++;

            if (contador[0] % TAMANHO_LOTE_FLUSH == 0) {
                entityManager.flush();
                entityManager.clear();
            }
        });

        String hash = HexFormat.of().formatHex(digest.digest());
        boolean semMudanca = hash.equals(hashAnterior);

        // O clear() acima desanexa "lote" — save() re-anexa (merge) pelo id, sem problema.
        if (semMudanca) {
            entityManager.flush();
            registroBrutoRepository.deleteByLoteIngestaoId(loteId);
            lote.setQuantidadeRegistros(0);
            lote.setStatus(STATUS_SEM_MUDANCA);
        } else {
            lote.setQuantidadeRegistros(contador[0]);
            lote.setStatus(STATUS_CONCLUIDO);
        }
        lote.setHashConteudo(hash);
        return loteIngestaoRepository.save(lote);
    }

    private static MessageDigest criarDigestSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponível na JVM", e);
        }
    }

    private static String sha256Hex(String texto) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(texto.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponível na JVM", e);
        }
    }
}
