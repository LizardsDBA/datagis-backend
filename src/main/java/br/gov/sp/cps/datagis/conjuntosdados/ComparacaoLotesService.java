package br.gov.sp.cps.datagis.conjuntosdados;

import br.gov.sp.cps.datagis.bronze.RegistroBruto;
import br.gov.sp.cps.datagis.bronze.RegistroBrutoRepository;
import br.gov.sp.cps.datagis.conjuntosdados.ComparacaoLotesDto.CampoAlterado;
import br.gov.sp.cps.datagis.conjuntosdados.ComparacaoLotesDto.DiferencaRegistro;
import br.gov.sp.cps.datagis.conjuntosdados.ComparacaoLotesDto.ResumoLotes;
import br.gov.sp.cps.datagis.controle.FonteDado;
import br.gov.sp.cps.datagis.controle.FonteDadoRepository;
import br.gov.sp.cps.datagis.controle.LoteIngestao;
import br.gov.sp.cps.datagis.controle.LoteIngestaoRepository;
import br.gov.sp.cps.datagis.gold.GoldPublicacaoService;
import br.gov.sp.cps.datagis.validacao.TransicaoBronzeSilverService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Comparação entre dois lotes consecutivos da mesma fonte de dados, no estilo "git diff":
 * identifica registros adicionados, removidos, modificados e inalterados entre o lote
 * atual e o anterior, usando {@code hash_registro} como chave primária de pareamento e,
 * quando os hashes coincidem, fazendo comparação campo a campo do payload JSON.
 *
 * <p>O hash é calculado na ingestão ({@link br.gov.sp.cps.datagis.ingestao.LoteIngestaoWriter})
 * e cobre tanto os dados quanto a geometria, então dois registros com o mesmo hash são
 * idênticos — não precisa recomparar campo a campo nesses casos.
 */
@Service
public class ComparacaoLotesService {

    private static final List<String> STATUS_PROMOVIDOS = List.of(
            TransicaoBronzeSilverService.STATUS_PROMOVIDO_SILVER,
            GoldPublicacaoService.STATUS_PROMOVIDO_GOLD);

    private final LoteIngestaoRepository loteIngestaoRepository;
    private final FonteDadoRepository fonteDadoRepository;
    private final RegistroBrutoRepository registroBrutoRepository;
    private final ObjectMapper objectMapper;

    public ComparacaoLotesService(
            LoteIngestaoRepository loteIngestaoRepository,
            FonteDadoRepository fonteDadoRepository,
            RegistroBrutoRepository registroBrutoRepository,
            ObjectMapper objectMapper) {
        this.loteIngestaoRepository = loteIngestaoRepository;
        this.fonteDadoRepository = fonteDadoRepository;
        this.registroBrutoRepository = registroBrutoRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Compara os dois últimos lotes promovidos de uma fonte, retornando uma página do
     * resultado filtrado por situação (ou todas). A contagem de resumo é sempre sobre
     * o diff inteiro, independente da página/filtro pedidos.
     */
    public ComparacaoLotesDto compararUltimosLotes(Long fonteDadoId, int pagina, int tamanho, String filtroSituacao) {
        FonteDado fonte = fonteDadoRepository.findById(fonteDadoId)
                .orElseThrow(() -> new IllegalArgumentException("Fonte de dado " + fonteDadoId + " não encontrada"));

        List<LoteIngestao> ultimos = loteIngestaoRepository.findUltimosLotesPromovidos(
                fonteDadoId, STATUS_PROMOVIDOS, PageRequest.of(0, 2));

        if (ultimos.isEmpty()) {
            throw new IllegalArgumentException("Fonte \"" + fonte.getNome() + "\" não tem nenhum lote promovido.");
        }

        LoteIngestao loteAtual = ultimos.get(0);

        if (ultimos.size() < 2) {
            return ComparacaoLotesDto.semLoteAnterior(
                    loteAtual.getId(), loteAtual.getDataExecucao(), fonteDadoId, fonte.getNome());
        }

        LoteIngestao loteAnterior = ultimos.get(1);
        return calcularDiff(fonte, loteAtual, loteAnterior, pagina, tamanho, filtroSituacao);
    }

    private ComparacaoLotesDto calcularDiff(
            FonteDado fonte, LoteIngestao loteAtual, LoteIngestao loteAnterior,
            int pagina, int tamanho, String filtroSituacao) {

        List<RegistroBruto> registrosAtual = registroBrutoRepository.findByLoteIngestaoIdOrderByIdAsc(loteAtual.getId());
        List<RegistroBruto> registrosAnterior = registroBrutoRepository.findByLoteIngestaoIdOrderByIdAsc(loteAnterior.getId());

        // Mapeia por hash: hash → lista de registros (pode haver colisão, mas é raro)
        Map<String, List<RegistroBruto>> mapaAtual = agruparPorHash(registrosAtual);
        Map<String, List<RegistroBruto>> mapaAnterior = agruparPorHash(registrosAnterior);

        Set<String> todosHashes = new java.util.LinkedHashSet<>();
        todosHashes.addAll(mapaAnterior.keySet());
        todosHashes.addAll(mapaAtual.keySet());

        List<DiferencaRegistro> todasDiferencas = new ArrayList<>();
        int adicionados = 0, removidos = 0, modificados = 0, inalterados = 0;

        for (String hash : todosHashes) {
            List<RegistroBruto> doAtual = mapaAtual.getOrDefault(hash, List.of());
            List<RegistroBruto> doAnterior = mapaAnterior.getOrDefault(hash, List.of());

            if (hash == null || hash.isBlank()) {
                // Registros sem hash: comparar campo a campo pela posição
                int pares = Math.min(doAtual.size(), doAnterior.size());
                for (int i = 0; i < pares; i++) {
                    DiferencaRegistro diff = compararRegistros(doAtual.get(i), doAnterior.get(i));
                    todasDiferencas.add(diff);
                    if (ComparacaoLotesDto.MODIFICADO.equals(diff.situacao())) modificados++;
                    else inalterados++;
                }
                for (int i = pares; i < doAtual.size(); i++) {
                    todasDiferencas.add(registroAdicionado(doAtual.get(i)));
                    adicionados++;
                }
                for (int i = pares; i < doAnterior.size(); i++) {
                    todasDiferencas.add(registroRemovido(doAnterior.get(i)));
                    removidos++;
                }
                continue;
            }

            if (!doAnterior.isEmpty() && !doAtual.isEmpty()) {
                // Hash igual nos dois lados → inalterado (hash cobre payload + geometria)
                int pares = Math.min(doAtual.size(), doAnterior.size());
                for (int i = 0; i < pares; i++) {
                    todasDiferencas.add(registroInalterado(doAtual.get(i), doAnterior.get(i)));
                    inalterados++;
                }
                for (int i = pares; i < doAtual.size(); i++) {
                    todasDiferencas.add(registroAdicionado(doAtual.get(i)));
                    adicionados++;
                }
                for (int i = pares; i < doAnterior.size(); i++) {
                    todasDiferencas.add(registroRemovido(doAnterior.get(i)));
                    removidos++;
                }
            } else if (doAnterior.isEmpty()) {
                for (RegistroBruto reg : doAtual) {
                    todasDiferencas.add(registroAdicionado(reg));
                    adicionados++;
                }
            } else {
                for (RegistroBruto reg : doAnterior) {
                    todasDiferencas.add(registroRemovido(reg));
                    removidos++;
                }
            }
        }

        // Filtrar por situação, se solicitado
        List<DiferencaRegistro> filtradas = todasDiferencas;
        if (filtroSituacao != null && !filtroSituacao.isBlank()) {
            String filtro = filtroSituacao.toUpperCase(java.util.Locale.ROOT);
            filtradas = todasDiferencas.stream()
                    .filter(d -> filtro.equals(d.situacao()))
                    .collect(Collectors.toList());
        }

        // Paginar
        int offset = Math.max(0, pagina) * Math.max(1, tamanho);
        int fim = Math.min(offset + Math.max(1, tamanho), filtradas.size());
        List<DiferencaRegistro> paginaResultado = offset < filtradas.size()
                ? filtradas.subList(offset, fim)
                : List.of();

        return new ComparacaoLotesDto(
                loteAtual.getId(), loteAtual.getDataExecucao(),
                loteAnterior.getId(), loteAnterior.getDataExecucao(),
                fonte.getId(), fonte.getNome(),
                true, null,
                new ResumoLotes(registrosAtual.size(), registrosAnterior.size(),
                        adicionados, removidos, modificados, inalterados),
                paginaResultado, filtradas.size(), pagina, tamanho);
    }

    private Map<String, List<RegistroBruto>> agruparPorHash(List<RegistroBruto> registros) {
        Map<String, List<RegistroBruto>> mapa = new LinkedHashMap<>();
        for (RegistroBruto reg : registros) {
            String hash = reg.getHashRegistro();
            mapa.computeIfAbsent(hash, k -> new ArrayList<>()).add(reg);
        }
        return mapa;
    }

    private DiferencaRegistro registroAdicionado(RegistroBruto reg) {
        return new DiferencaRegistro(
                reg.getId(), null,
                reg.getNumeroLinha(), null,
                ComparacaoLotesDto.ADICIONADO,
                parsearPayload(reg.getPayload()), null,
                List.of());
    }

    private DiferencaRegistro registroRemovido(RegistroBruto reg) {
        return new DiferencaRegistro(
                null, reg.getId(),
                null, reg.getNumeroLinha(),
                ComparacaoLotesDto.REMOVIDO,
                null, parsearPayload(reg.getPayload()),
                List.of());
    }

    private DiferencaRegistro registroInalterado(RegistroBruto atual, RegistroBruto anterior) {
        return new DiferencaRegistro(
                atual.getId(), anterior.getId(),
                atual.getNumeroLinha(), anterior.getNumeroLinha(),
                ComparacaoLotesDto.INALTERADO,
                parsearPayload(atual.getPayload()),
                parsearPayload(anterior.getPayload()),
                List.of());
    }

    private DiferencaRegistro compararRegistros(RegistroBruto atual, RegistroBruto anterior) {
        Map<String, Object> dadosAtual = parsearPayload(atual.getPayload());
        Map<String, Object> dadosAnterior = parsearPayload(anterior.getPayload());

        List<CampoAlterado> camposAlterados = new ArrayList<>();
        Set<String> todasChaves = new java.util.LinkedHashSet<>();
        todasChaves.addAll(dadosAnterior.keySet());
        todasChaves.addAll(dadosAtual.keySet());

        for (String chave : todasChaves) {
            Object valAtual = dadosAtual.get(chave);
            Object valAnterior = dadosAnterior.get(chave);
            if (!ComparadorValores.iguais(valAtual, valAnterior)) {
                camposAlterados.add(new CampoAlterado(chave, valAnterior, valAtual));
            }
        }

        // Comparar geometria também
        if (!ComparadorValores.geometriasIguais(atual.getGeometriaWkt(), anterior.getGeometriaWkt())) {
            camposAlterados.add(new CampoAlterado("geometria_wkt",
                    resumirGeometria(anterior.getGeometriaWkt()),
                    resumirGeometria(atual.getGeometriaWkt())));
        }

        String situacao = camposAlterados.isEmpty()
                ? ComparacaoLotesDto.INALTERADO
                : ComparacaoLotesDto.MODIFICADO;

        return new DiferencaRegistro(
                atual.getId(), anterior.getId(),
                atual.getNumeroLinha(), anterior.getNumeroLinha(),
                situacao, dadosAtual, dadosAnterior, camposAlterados);
    }

    private Map<String, Object> parsearPayload(String payloadJson) {
        try {
            return objectMapper.readValue(payloadJson, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Map.of("_payload_bruto", payloadJson != null ? payloadJson : "");
        }
    }

    private static Object resumirGeometria(String wkt) {
        if (wkt == null) return null;
        return wkt.length() > 120 ? wkt.substring(0, 120) + "… (" + wkt.length() + " caracteres)" : wkt;
    }
}
