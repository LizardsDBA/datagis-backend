package br.gov.sp.cps.datagis.ingestao;

import br.gov.sp.cps.datagis.controle.FonteDado;
import br.gov.sp.cps.datagis.controle.LoteIngestao;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.io.WKTWriter;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * Ingestão genérica pra qualquer fonte cujo {@code formatoEsperado} seja {@code CSV}.
 * Cada linha vira um {@code registro_bruto} com todas as colunas no payload; se a
 * fonte declarar {@code campoGeometriaLat}/{@code campoGeometriaLon} (nomes de coluna
 * no próprio CSV), monta um ponto WKT a partir deles — sem isso, a fonte é só tabular
 * (sem geometria), o que já é suportado (a coluna é opcional).
 *
 * <p>Não sabe nada sobre qual fonte é — funciona pra qualquer CSV que caiba nesse
 * formato (cabeçalho na primeira linha, uma linha por registro).
 */
@Service
public class IngestaoCsvExecutor implements IngestaoExecutor {

    private static final String FORMATO = "CSV";

    /** A cada N linhas convertidas, reporta progresso. */
    private static final int INTERVALO_PROGRESSO = 500;

    private final HttpFetchClient httpFetchClient;
    private final LoteIngestaoWriter writer;
    private final ObjectMapper objectMapper;
    private final ProgressoExecucaoRegistry progresso;
    private final GeometryFactory geometryFactory = new GeometryFactory();
    private final WKTWriter wktWriter = new WKTWriter();

    public IngestaoCsvExecutor(
            HttpFetchClient httpFetchClient,
            LoteIngestaoWriter writer,
            ObjectMapper objectMapper,
            ProgressoExecucaoRegistry progresso) {
        this.httpFetchClient = httpFetchClient;
        this.writer = writer;
        this.objectMapper = objectMapper;
        this.progresso = progresso;
    }

    @Override
    public boolean suportar(FonteDado fonte) {
        return FORMATO.equalsIgnoreCase(fonte.getFormatoEsperado());
    }

    @Override
    public LoteIngestao executar(FonteDado fonte) {
        progresso.atualizar(fonte.getId(), FaseExecucao.BUSCANDO_DADOS, 0, null, null);
        String corpoBruto = httpFetchClient.buscarCorpoBruto(fonte.getUrlOrigem());
        List<String> linhas = corpoBruto.lines().filter(linha -> !linha.isBlank()).toList();
        if (linhas.isEmpty()) {
            throw new IllegalStateException("CSV de \"" + fonte.getNome() + "\" veio vazio");
        }

        List<String> cabecalho = dividirLinhaCsv(linhas.get(0));
        int indiceLat = indiceDaColuna(cabecalho, fonte.getCampoGeometriaLat());
        int indiceLon = indiceDaColuna(cabecalho, fonte.getCampoGeometriaLon());

        long totalLinhas = linhas.size() - 1;
        List<RegistroPreparado> registros = new ArrayList<>();
        for (int i = 1; i < linhas.size(); i++) {
            List<String> campos = dividirLinhaCsv(linhas.get(i));

            Map<String, String> registro = new LinkedHashMap<>();
            for (int c = 0; c < cabecalho.size() && c < campos.size(); c++) {
                registro.put(cabecalho.get(c), campos.get(c));
            }

            registros.add(new RegistroPreparado(
                    escreverJson(registro),
                    construirPontoWkt(campos, indiceLat, indiceLon)));

            if (i % INTERVALO_PROGRESSO == 0 || i == linhas.size() - 1) {
                progresso.atualizar(fonte.getId(), FaseExecucao.LENDO_REGISTROS, i, totalLinhas, null);
            }
        }

        // Hash so das linhas de dado (sem o cabecalho — se um dia o CSV so reordenar
        // colunas mas o dado continuar igual, isso ainda contaria como "mudou", o que
        // e aceitavel; o que importa e nunca deixar de detectar mudanca de verdade).
        String conteudoParaHash = String.join("\n", linhas.subList(1, linhas.size()));

        return writer.gravar(fonte, conteudoParaHash, registros);
    }

    private static int indiceDaColuna(List<String> cabecalho, String nomeColuna) {
        return nomeColuna == null ? -1 : cabecalho.indexOf(nomeColuna);
    }

    private String construirPontoWkt(List<String> campos, int indiceLat, int indiceLon) {
        if (indiceLat < 0 || indiceLon < 0 || indiceLat >= campos.size() || indiceLon >= campos.size()) {
            return null;
        }
        try {
            double lat = Double.parseDouble(campos.get(indiceLat).trim());
            double lon = Double.parseDouble(campos.get(indiceLon).trim());
            return wktWriter.write(geometryFactory.createPoint(new Coordinate(lon, lat)));
        } catch (NumberFormatException e) {
            // Linha sem coordenada valida - fica so no payload, sem geometria. Nao
            // aborta a ingestao inteira por causa de uma linha suja (Bronze preserva
            // o que veio, nao descarta).
            return null;
        }
    }

    private String escreverJson(Map<String, String> registro) {
        try {
            return objectMapper.writeValueAsString(registro);
        } catch (Exception e) {
            throw new IllegalStateException("Falha ao serializar linha do CSV", e);
        }
    }

    /**
     * Divide uma linha CSV respeitando aspas (campo com vírgula dentro de aspas não
     * quebra) e aspas duplicadas escapadas ({@code ""} dentro de um campo). Não trata
     * campo com quebra de linha dentro de aspas — nenhuma fonte real que usamos tem
     * isso; se aparecer um dia, precisa trocar por leitura char-a-char do arquivo
     * inteiro em vez de linha por linha.
     */
    private static List<String> dividirLinhaCsv(String linha) {
        List<String> campos = new ArrayList<>();
        StringBuilder atual = new StringBuilder();
        boolean dentroDeAspas = false;

        for (int i = 0; i < linha.length(); i++) {
            char c = linha.charAt(i);
            if (c == '"') {
                if (dentroDeAspas && i + 1 < linha.length() && linha.charAt(i + 1) == '"') {
                    atual.append('"');
                    i++;
                } else {
                    dentroDeAspas = !dentroDeAspas;
                }
            } else if (c == ',' && !dentroDeAspas) {
                campos.add(atual.toString().trim());
                atual.setLength(0);
            } else {
                atual.append(c);
            }
        }
        campos.add(atual.toString().trim());
        return campos;
    }
}
