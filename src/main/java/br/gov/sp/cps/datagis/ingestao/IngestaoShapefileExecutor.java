package br.gov.sp.cps.datagis.ingestao;

import br.gov.sp.cps.datagis.armazenamento.ArmazenamentoArquivoBruto;
import br.gov.sp.cps.datagis.controle.FonteDado;
import br.gov.sp.cps.datagis.controle.LoteIngestao;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.geotools.api.data.DataStore;
import org.geotools.api.data.DataStoreFinder;
import org.geotools.api.data.SimpleFeatureSource;
import org.geotools.api.feature.Property;
import org.geotools.api.feature.simple.SimpleFeature;
import org.geotools.data.simple.SimpleFeatureIterator;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.io.WKTWriter;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * Ingestão genérica pra qualquer fonte cujo {@code formatoEsperado} seja
 * {@code SHAPEFILE} — hoje só via {@code UPLOAD_MANUAL} (ver
 * {@code IngestaoExecucaoService#executarComUpload}): a fonte típica
 * (consultapublica.car.gov.br) é uma SPA com sessão que não dá pra automatizar por
 * HTTP simples, então o usuário baixa o {@code .zip} no navegador dele e sobe pro
 * sistema. O {@code .zip} pode trazer mais de um {@code .shp} (ex.: perímetro + APP +
 * Reserva Legal juntos no mesmo pacote do estado) — todos entram no mesmo lote.
 *
 * <p>Usa {@code gt-shapefile}/{@code gt-main} (GeoTools, já no {@code pom.xml} desde o
 * início, nunca exercitado até agora — é o item 3 do spike de compatibilidade,
 * CLAUDE.md §5.5). A geometria de cada feição já vem como {@link Geometry} (JTS) —
 * GeoTools usa JTS por baixo — então vira WKT com o mesmo {@link WKTWriter} usado no
 * {@link GeoJsonWktMapper}, sem parser próprio.
 *
 * <p>Mesma resiliência dos outros executores: geometria ausente ou que falhar ao
 * converter não derruba o registro (grava sem WKT), e feição ruim não derruba o lote.
 *
 * <p>Lê e grava em <b>streaming</b> (ver {@link FornecedorRegistros}), não junta as
 * feições numa lista antes: um Shapefile de 3,24 GB estourava o heap só na leitura,
 * antes de gravar a primeira linha (achado ao vivo, 2026-09-11 —
 * {@code docs/upload-shapefile-car.md} §3.4).
 */
@Service
public class IngestaoShapefileExecutor implements IngestaoExecutor {

    private static final String FORMATO = "SHAPEFILE";
    private static final String TIPO_UPLOAD_MANUAL = "UPLOAD_MANUAL";

    private final ArmazenamentoArquivoBruto armazenamento;
    private final LoteIngestaoWriter writer;
    private final ObjectMapper objectMapper;
    private final WKTWriter wktWriter = new WKTWriter();

    public IngestaoShapefileExecutor(
            ArmazenamentoArquivoBruto armazenamento, LoteIngestaoWriter writer, ObjectMapper objectMapper) {
        this.armazenamento = armazenamento;
        this.writer = writer;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean suportar(FonteDado fonte) {
        return FORMATO.equalsIgnoreCase(fonte.getFormatoEsperado());
    }

    @Override
    public LoteIngestao executar(FonteDado fonte) {
        if (!TIPO_UPLOAD_MANUAL.equals(fonte.getTipoAquisicao())) {
            throw new IllegalStateException(
                    "Fonte \"" + fonte.getNome() + "\" é SHAPEFILE mas não é UPLOAD_MANUAL — "
                            + "download automático de Shapefile ainda não foi implementado, só upload.");
        }
        if (fonte.getCaminhoArquivoUpload() == null) {
            throw new IllegalStateException(
                    "Fonte \"" + fonte.getNome() + "\" ainda não recebeu nenhum arquivo. "
                            + "Envie o .zip pelo upload antes de executar.");
        }

        Path diretorioTemp = descompactar(fonte);
        try {
            List<Path> shapefiles = listarShapefiles(diretorioTemp, fonte);
            return writer.gravarStreaming(fonte, consumidor -> lerShapefiles(shapefiles, consumidor));
        } finally {
            apagarRecursivo(diretorioTemp);
        }
    }

    /** Descompacta achatado (ignora subpastas do zip) — .shp/.dbf/.shx/.prj precisam ficar juntos. */
    private Path descompactar(FonteDado fonte) {
        Path destino;
        try {
            destino = Files.createTempDirectory("datagis-shp-" + fonte.getId());
        } catch (IOException e) {
            throw new IllegalStateException("Falha ao criar diretório temporário pra descompactar", e);
        }
        try (InputStream bruto = armazenamento.ler(fonte.getCaminhoArquivoUpload());
                ZipInputStream zip = new ZipInputStream(bruto)) {
            ZipEntry entrada;
            while ((entrada = zip.getNextEntry()) != null) {
                if (entrada.isDirectory()) {
                    continue;
                }
                String nomeArquivo = Path.of(entrada.getName()).getFileName().toString();
                Files.copy(zip, destino.resolve(nomeArquivo), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Falha ao descompactar o arquivo enviado pra \"" + fonte.getNome() + "\" — é um .zip válido?", e);
        }
        return destino;
    }

    private List<Path> listarShapefiles(Path diretorio, FonteDado fonte) {
        List<Path> shapefiles;
        try (Stream<Path> arquivos = Files.list(diretorio)) {
            shapefiles = arquivos
                    .filter(p -> p.toString().toLowerCase().endsWith(".shp"))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("Falha ao listar o conteúdo descompactado", e);
        }
        if (shapefiles.isEmpty()) {
            throw new IllegalStateException(
                    "Nenhum arquivo .shp encontrado dentro do .zip enviado pra \"" + fonte.getNome() + "\"");
        }
        return shapefiles;
    }

    /** Chama {@code consumidor} um registro por vez, pra cada .shp, sem acumular nada em lista. */
    private void lerShapefiles(List<Path> shapefiles, Consumer<RegistroPreparado> consumidor) {
        for (Path shp : shapefiles) {
            lerUmShapefile(shp, consumidor);
        }
    }

    private void lerUmShapefile(Path shp, Consumer<RegistroPreparado> consumidor) {
        DataStore dataStore = abrir(shp);
        try {
            String nomeCamada = dataStore.getTypeNames()[0];
            SimpleFeatureSource origem = dataStore.getFeatureSource(nomeCamada);
            try (SimpleFeatureIterator it = origem.getFeatures().features()) {
                while (it.hasNext()) {
                    consumidor.accept(paraRegistro(it.next()));
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Falha ao ler feições de " + shp.getFileName(), e);
        } finally {
            dataStore.dispose();
        }
    }

    private DataStore abrir(Path shp) {
        try {
            DataStore dataStore = DataStoreFinder.getDataStore(Map.of("url", shp.toUri().toURL()));
            if (dataStore == null) {
                throw new IllegalStateException("GeoTools não reconheceu " + shp.getFileName() + " como Shapefile");
            }
            return dataStore;
        } catch (MalformedURLException e) {
            throw new IllegalStateException("Caminho inválido pro Shapefile " + shp, e);
        } catch (IOException e) {
            throw new IllegalStateException("Falha ao abrir " + shp.getFileName(), e);
        }
    }

    private RegistroPreparado paraRegistro(SimpleFeature feature) {
        Map<String, Object> atributos = new LinkedHashMap<>();
        Geometry geometria = null;
        for (Property propriedade : feature.getProperties()) {
            Object valor = propriedade.getValue();
            if (valor instanceof Geometry geom) {
                geometria = geom;
                continue;
            }
            atributos.put(propriedade.getName().getLocalPart(), valor);
        }
        return new RegistroPreparado(escreverJson(atributos), paraWktOuNulo(geometria));
    }

    /** Mesmo princípio do GeoJsonWktMapper: geometria ausente/malformada não derruba o registro. */
    private String paraWktOuNulo(Geometry geometria) {
        if (geometria == null) {
            return null;
        }
        try {
            return wktWriter.write(geometria);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String escreverJson(Map<String, Object> atributos) {
        try {
            return objectMapper.writeValueAsString(atributos);
        } catch (Exception e) {
            throw new IllegalStateException("Falha ao serializar atributos do Shapefile", e);
        }
    }

    private void apagarRecursivo(Path diretorio) {
        try (Stream<Path> caminhos = Files.walk(diretorio)) {
            caminhos.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignorado) {
                    // diretorio temporario - limpeza de melhor esforco, nao trava a ingestao
                }
            });
        } catch (IOException ignorado) {
            // idem
        }
    }
}
