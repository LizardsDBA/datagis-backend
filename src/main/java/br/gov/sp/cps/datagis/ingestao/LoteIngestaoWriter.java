package br.gov.sp.cps.datagis.ingestao;

import br.gov.sp.cps.datagis.bronze.RegistroBruto;
import br.gov.sp.cps.datagis.bronze.RegistroBrutoRepository;
import br.gov.sp.cps.datagis.controle.FonteDado;
import br.gov.sp.cps.datagis.controle.LoteIngestao;
import br.gov.sp.cps.datagis.controle.LoteIngestaoRepository;
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

    private final LoteIngestaoRepository loteIngestaoRepository;
    private final RegistroBrutoRepository registroBrutoRepository;

    public LoteIngestaoWriter(
            LoteIngestaoRepository loteIngestaoRepository, RegistroBrutoRepository registroBrutoRepository) {
        this.loteIngestaoRepository = loteIngestaoRepository;
        this.registroBrutoRepository = registroBrutoRepository;
    }

    /**
     * @param conteudoParaHash só a parte do corpo bruto que representa dado de verdade
     *                         (ex.: as features de um GeoJSON, sem o envelope que traz
     *                         timestamp variável a cada chamada — ver RN018/§2.8).
     * @param registros        já prontos; calculado só se o hash indicar mudança.
     */
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
