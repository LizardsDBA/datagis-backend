package br.gov.sp.cps.datagis.ingestao;

import br.gov.sp.cps.datagis.bronze.RegistroBruto;
import br.gov.sp.cps.datagis.bronze.RegistroBrutoRepository;
import br.gov.sp.cps.datagis.controle.FonteDado;
import br.gov.sp.cps.datagis.controle.FonteDadoRepository;
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
 * Orquestra a fatia 1 (CLAUDE.md §5.2): busca o WFS do CAR, cria o lote_ingestao e
 * grava cada feature como registro_bruto. Sem validação de geometria, sem quarentena,
 * sem reprojeção, sem paginação — ingestão bruta pura de uma página.
 */
@Service
public class IngestaoCarService {

    /** Precisa bater com o nome inserido em V003__seed_fonte_dado_car_pr.sql. */
    private static final String NOME_FONTE_CAR_PR = "CAR - Imóveis (PR)";

    private final CarWfsClient wfsClient;
    private final GeoJsonWktMapper wktMapper;
    private final FonteDadoRepository fonteDadoRepository;
    private final LoteIngestaoRepository loteIngestaoRepository;
    private final RegistroBrutoRepository registroBrutoRepository;
    private final ObjectMapper objectMapper;

    public IngestaoCarService(
            CarWfsClient wfsClient,
            GeoJsonWktMapper wktMapper,
            FonteDadoRepository fonteDadoRepository,
            LoteIngestaoRepository loteIngestaoRepository,
            RegistroBrutoRepository registroBrutoRepository,
            ObjectMapper objectMapper) {
        this.wfsClient = wfsClient;
        this.wktMapper = wktMapper;
        this.fonteDadoRepository = fonteDadoRepository;
        this.loteIngestaoRepository = loteIngestaoRepository;
        this.registroBrutoRepository = registroBrutoRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public LoteIngestao ingerir() {
        FonteDado fonte = fonteDadoRepository.findByNome(NOME_FONTE_CAR_PR)
                .orElseThrow(() -> new IllegalStateException(
                        "fonte_dado '" + NOME_FONTE_CAR_PR + "' não cadastrada (seed ausente?)"));

        String corpoBruto = wfsClient.buscarCorpoBruto();
        String hash = sha256Hex(corpoBruto);

        LoteIngestao lote = LoteIngestao.builder()
                .fonteDado(fonte)
                .origemReferencia(fonte.getUrlOrigem())
                .hashConteudo(hash)
                .status("EM_PROCESSAMENTO")
                .dataExecucao(LocalDateTime.now())
                .build();
        lote = loteIngestaoRepository.save(lote);

        JsonNode featureCollection = lerJson(corpoBruto);
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
        lote.setStatus("CONCLUIDO");
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
