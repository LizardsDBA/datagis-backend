package br.gov.sp.cps.datagis.ingestao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import br.gov.sp.cps.datagis.armazenamento.ArmazenamentoArquivoBruto;
import br.gov.sp.cps.datagis.controle.FonteDado;
import br.gov.sp.cps.datagis.controle.LoteIngestao;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.geotools.api.data.SimpleFeatureStore;
import org.geotools.api.data.Transaction;
import org.geotools.api.feature.simple.SimpleFeatureType;
import org.geotools.data.DefaultTransaction;
import org.geotools.data.shapefile.ShapefileDataStore;
import org.geotools.data.shapefile.ShapefileDataStoreFactory;
import org.geotools.feature.DefaultFeatureCollection;
import org.geotools.feature.simple.SimpleFeatureBuilder;
import org.geotools.feature.simple.SimpleFeatureTypeBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

/**
 * Não usa fixture binária versionada — cria um Shapefile de verdade com o próprio
 * GeoTools (o mesmo caminho que escreve dado real em produção) e testa a leitura em
 * cima disso. Prova o item 3 do spike de compatibilidade (CLAUDE.md §5.5): GeoTools
 * abre um `.shp` e lê a primeira feição — nunca tinha sido exercitado antes.
 */
@ExtendWith(MockitoExtension.class)
class IngestaoShapefileExecutorTest {

    @Mock
    private ArmazenamentoArquivoBruto armazenamento;
    @Mock
    private LoteIngestaoWriter writer;

    @TempDir
    Path diretorioTemp;

    private IngestaoShapefileExecutor executor;

    @BeforeEach
    void configurar() {
        executor = new IngestaoShapefileExecutor(armazenamento, writer, new ObjectMapper());
    }

    @Test
    void suportarSoDependeDoFormatoEsperado() {
        assertThat(executor.suportar(FonteDado.builder().formatoEsperado("SHAPEFILE").build())).isTrue();
        assertThat(executor.suportar(FonteDado.builder().formatoEsperado("shapefile").build())).isTrue();
        assertThat(executor.suportar(FonteDado.builder().formatoEsperado("GEOJSON").build())).isFalse();
    }

    @Test
    void deveFalharSeFonteNaoForUploadManual() {
        FonteDado fonte = FonteDado.builder()
                .id(1L).nome("Fonte errada").tipoAquisicao("API_REST").formatoEsperado("SHAPEFILE").build();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> executor.executar(fonte))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("UPLOAD_MANUAL");
    }

    @Test
    void deveFalharSeAindaNaoTiverArquivoEnviado() {
        FonteDado fonte = FonteDado.builder()
                .id(1L).nome("Fonte sem upload").tipoAquisicao("UPLOAD_MANUAL").formatoEsperado("SHAPEFILE").build();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> executor.executar(fonte))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("nenhum arquivo");
    }

    @Test
    void deveLerFeicoesDeUmShapefileZipadoEPrepararUmRegistroPorFeicao() throws Exception {
        byte[] zip = criarZipComShapefileDeTeste();
        FonteDado fonte = FonteDado.builder()
                .id(5L).nome("CAR - Imóveis (PR) via Shapefile (upload)")
                .tipoAquisicao("UPLOAD_MANUAL").formatoEsperado("SHAPEFILE")
                .caminhoArquivoUpload("qualquer-endereco.zip")
                .build();
        when(armazenamento.ler(eq("qualquer-endereco.zip"))).thenReturn(new ByteArrayInputStream(zip));
        LoteIngestao loteEsperado = LoteIngestao.builder().id(20L).status("CONCLUIDO").build();
        when(writer.gravar(eq(fonte), anyString(), any())).thenReturn(loteEsperado);

        LoteIngestao resultado = executor.executar(fonte);

        assertThat(resultado).isSameAs(loteEsperado);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RegistroPreparado>> captor = ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(writer).gravar(eq(fonte), anyString(), captor.capture());

        List<RegistroPreparado> registros = captor.getValue();
        assertThat(registros).hasSize(2);
        assertThat(registros).anySatisfy(r -> {
            assertThat(r.payloadJson()).contains("Imovel A").contains("12.5");
            assertThat(r.geometriaWkt()).startsWith("POINT");
        });
        assertThat(registros).anySatisfy(r -> assertThat(r.payloadJson()).contains("Imovel B"));
    }

    /** Cria um Shapefile de verdade (via GeoTools, o mesmo escritor que a produção usaria) e zipa. */
    private byte[] criarZipComShapefileDeTeste() throws Exception {
        SimpleFeatureTypeBuilder tipoBuilder = new SimpleFeatureTypeBuilder();
        tipoBuilder.setName("imovel_teste");
        tipoBuilder.add("the_geom", Point.class);
        tipoBuilder.add("nome", String.class);
        tipoBuilder.add("area", Double.class);
        SimpleFeatureType tipo = tipoBuilder.buildFeatureType();

        File arquivoShp = diretorioTemp.resolve("teste.shp").toFile();
        ShapefileDataStoreFactory factory = new ShapefileDataStoreFactory();
        Map<String, java.io.Serializable> params = new HashMap<>();
        params.put("url", arquivoShp.toURI().toURL());
        ShapefileDataStore dataStore = (ShapefileDataStore) factory.createNewDataStore(params);
        dataStore.createSchema(tipo);

        String nomeCamada = dataStore.getTypeNames()[0];
        SimpleFeatureStore featureStore = (SimpleFeatureStore) dataStore.getFeatureSource(nomeCamada);

        GeometryFactory geometryFactory = new GeometryFactory();
        SimpleFeatureBuilder featureBuilder = new SimpleFeatureBuilder(tipo);
        List<org.geotools.api.feature.simple.SimpleFeature> feicoes = new ArrayList<>();

        featureBuilder.add(geometryFactory.createPoint(new Coordinate(-49.27, -25.43)));
        featureBuilder.add("Imovel A");
        featureBuilder.add(12.5);
        feicoes.add(featureBuilder.buildFeature("1"));

        featureBuilder.add(geometryFactory.createPoint(new Coordinate(-51.94, -23.42)));
        featureBuilder.add("Imovel B");
        featureBuilder.add(48.0);
        feicoes.add(featureBuilder.buildFeature("2"));

        DefaultFeatureCollection colecao = new DefaultFeatureCollection();
        colecao.addAll(feicoes);

        Transaction transacao = new DefaultTransaction("teste");
        featureStore.setTransaction(transacao);
        try {
            featureStore.addFeatures(colecao);
            transacao.commit();
        } finally {
            transacao.close();
        }
        dataStore.dispose();

        return zipar(diretorioTemp, "teste");
    }

    private byte[] zipar(Path diretorio, String prefixoArquivo) throws IOException {
        ByteArrayOutputStream saida = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(saida)) {
            try (var arquivos = Files.list(diretorio)) {
                for (Path arquivo : arquivos.filter(p -> p.getFileName().toString().startsWith(prefixoArquivo)).toList()) {
                    zip.putNextEntry(new ZipEntry(arquivo.getFileName().toString()));
                    Files.copy(arquivo, zip);
                    zip.closeEntry();
                }
            }
        }
        return saida.toByteArray();
    }
}
