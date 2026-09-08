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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Busca o WFS do CAR, cria o lote_ingestao e grava cada feature como registro_bruto.
 * Sem validar geometria, sem quarentena, sem paginacao ainda — so ingestao bruta.
 *
 * <p>É um {@link IngestaoExecutor} como outro qualquer — não recebe tratamento
 * especial do disparo genérico (ver {@link IngestaoExecucaoService}). Reconhece que é
 * dele processar uma fonte pela URL de origem (única por fonte cadastrada).
 */
@Service
public class IngestaoCarService implements IngestaoExecutor {

    /** Trecho fixo da URL do WFS do CAR/SICAR — usado só para reconhecer a fonte. */
    private static final String URL_WFS_CAR = "geoserver.car.gov.br/geoserver/sicar/wfs";

    private static final String STATUS_CONCLUIDO = "CONCLUIDO";
    private static final String STATUS_SEM_MUDANCA = "SEM_MUDANCA";

    private final CarWfsClient wfsClient;
    private final GeoJsonWktMapper wktMapper;
    private final LoteIngestaoRepository loteIngestaoRepository;
    private final RegistroBrutoRepository registroBrutoRepository;
    private final ObjectMapper objectMapper;

    public IngestaoCarService(
            CarWfsClient wfsClient,
            GeoJsonWktMapper wktMapper,
            LoteIngestaoRepository loteIngestaoRepository,
            RegistroBrutoRepository registroBrutoRepository,
            ObjectMapper objectMapper) {
        this.wfsClient = wfsClient;
        this.wktMapper = wktMapper;
        this.loteIngestaoRepository = loteIngestaoRepository;
        this.registroBrutoRepository = registroBrutoRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean suportar(FonteDado fonte) {
        return fonte.getUrlOrigem() != null && fonte.getUrlOrigem().contains(URL_WFS_CAR);
    }

    @Override
    @Transactional
    public LoteIngestao executar(FonteDado fonte) {
        String corpoBruto = wfsClient.buscarCorpoBruto();
        JsonNode featureCollection = lerJson(corpoBruto);

        // O hash e so das features, nao da resposta inteira. Testamos e vimos que o
        // WFS sempre manda um timeStamp diferente a cada chamada, mesmo quando os
        // dados sao os mesmos - se hasheasse tudo, nunca ia bater de uma vez pra outra.
        String hash = sha256Hex(escreverJson(featureCollection.path("features")));

        // Se o hash bater com o ultimo lote dessa fonte, o conteudo nao mudou.
        // Mesmo assim cria um lote novo (so pra registrar que rodou), mas nao grava
        // os registros de novo.
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
                .status("EM_PROCESSAMENTO")
                .dataExecucao(LocalDateTime.now())
                .build();
        lote = loteIngestaoRepository.save(lote);

        JsonNode features = featureCollection.path("features");

        int contador = 0;
        for (JsonNode feature : features) {
            RegistroBruto registro = RegistroBruto.builder()
                    .loteIngestaoId(lote.getId())
                    .fonteDadoId(fonte.getId())
                    .numeroLinha(contador)
                    .payload(escreverJson(feature.path("properties")))
                    .geometriaWkt(wktMapper.paraWkt(feature.path("geometry")))
                    .dataIngestao(LocalDateTime.now())
                    .build();
            registroBrutoRepository.save(registro);
            contador++;
        }

        lote.setQuantidadeRegistros(contador);
        lote.setStatus(STATUS_CONCLUIDO);
        return loteIngestaoRepository.save(lote);
    }

    private JsonNode lerJson(String texto) {
        try {
            return objectMapper.readTree(texto);
        } catch (Exception e) {
            throw new IllegalStateException("Resposta do WFS do CAR não é um JSON válido", e);
        }
    }

    private String escreverJson(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            throw new IllegalStateException("Falha ao serializar properties da feature", e);
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
