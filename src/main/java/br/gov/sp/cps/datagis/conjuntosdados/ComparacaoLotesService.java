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
import br.gov.sp.cps.datagis.validacao.DeduplicadorRegistros;
import br.gov.sp.cps.datagis.validacao.TransicaoBronzeSilverService;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Comparação entre dois lotes consecutivos da mesma fonte de dados, no estilo "git diff":
 * identifica registros adicionados, removidos, modificados e inalterados entre o lote
 * atual e o anterior.
 *
 * <p><b>Pareamento pela chave natural da fonte</b> (no CAR, {@code cod_imovel}; nos
 * embargos, {@code numero_termo} etc.) — a mesma chave e os mesmos aliases da
 * deduplicação Bronze → Silver ({@link TransicaoBronzeSilverService#resolverChaveNatural}
 * e {@link DeduplicadorRegistros#extrairChaveNatural}). Pela posição no lote não serve:
 * um registro inserido no meio deslocaria todos os seguintes e eles apareceriam como
 * modificados, comparando registros diferentes entre si. O {@code hash_registro} também
 * não serve: a ingestão não o preenche (CLAUDE.md §9.31) e, mesmo preenchido, um registro
 * alterado teria hash diferente e apareceria como removido + adicionado, nunca como
 * modificado.
 *
 * <p>Registro sem chave natural no payload é pareado pelo conteúdo idêntico (payload +
 * geometria); o que sobra vira adicionado/removido.
 */
@Service
public class ComparacaoLotesService {

    private static final List<String> STATUS_PROMOVIDOS = List.of(
            TransicaoBronzeSilverService.STATUS_PROMOVIDO_SILVER,
            GoldPublicacaoService.STATUS_PROMOVIDO_GOLD);

    private static final Set<String> SITUACOES = Set.of(
            ComparacaoLotesDto.ADICIONADO, ComparacaoLotesDto.REMOVIDO,
            ComparacaoLotesDto.MODIFICADO, ComparacaoLotesDto.INALTERADO);

    private final LoteIngestaoRepository loteIngestaoRepository;
    private final FonteDadoRepository fonteDadoRepository;
    private final RegistroBrutoRepository registroBrutoRepository;
    private final TransicaoBronzeSilverService transicaoService;
    private final DeduplicadorRegistros deduplicador;
    private final ObjectMapper objectMapper;

    public ComparacaoLotesService(
            LoteIngestaoRepository loteIngestaoRepository,
            FonteDadoRepository fonteDadoRepository,
            RegistroBrutoRepository registroBrutoRepository,
            TransicaoBronzeSilverService transicaoService,
            DeduplicadorRegistros deduplicador,
            ObjectMapper objectMapper) {
        this.loteIngestaoRepository = loteIngestaoRepository;
        this.fonteDadoRepository = fonteDadoRepository;
        this.registroBrutoRepository = registroBrutoRepository;
        this.transicaoService = transicaoService;
        this.deduplicador = deduplicador;
        this.objectMapper = objectMapper;
    }

    /**
     * Compara os dois últimos lotes promovidos de uma fonte, retornando uma página do
     * resultado filtrado por situação (ou todas). A contagem de resumo é sempre sobre
     * o diff inteiro, independente da página/filtro pedidos.
     */
    public ComparacaoLotesDto compararUltimosLotes(Long fonteDadoId, int pagina, int tamanho, String filtroSituacao) {
        String filtro = validarFiltro(filtroSituacao);

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
        return calcularDiff(fonte, loteAtual, loteAnterior, pagina, tamanho, filtro);
    }

    /** Filtro em branco = todas as situações; valor fora das 4 conhecidas = 400. */
    private static String validarFiltro(String filtroSituacao) {
        if (filtroSituacao == null || filtroSituacao.isBlank()) {
            return null;
        }
        String filtro = filtroSituacao.trim().toUpperCase(Locale.ROOT);
        if (!SITUACOES.contains(filtro)) {
            throw new IllegalArgumentException("filtroSituacao inválido: \"" + filtroSituacao
                    + "\". Use ADICIONADO, REMOVIDO, MODIFICADO ou INALTERADO.");
        }
        return filtro;
    }

    private ComparacaoLotesDto calcularDiff(
            FonteDado fonte, LoteIngestao loteAtual, LoteIngestao loteAnterior,
            int pagina, int tamanho, String filtro) {

        List<RegistroBruto> registrosAtual = registroBrutoRepository.findByLoteIngestaoIdOrderByIdAsc(loteAtual.getId());
        List<RegistroBruto> registrosAnterior = registroBrutoRepository.findByLoteIngestaoIdOrderByIdAsc(loteAnterior.getId());

        String campoChave = transicaoService.resolverChaveNatural(transicaoService.resolverEntidadeSilver(fonte), fonte);

        // Chave natural → registros do lote, na ordem do lote. Sem chave fica à parte.
        Map<String, List<Registro>> porChaveAtual = new LinkedHashMap<>();
        Map<String, List<Registro>> porChaveAnterior = new LinkedHashMap<>();
        List<Registro> semChaveAtual = new ArrayList<>();
        List<Registro> semChaveAnterior = new ArrayList<>();
        agrupar(registrosAtual, campoChave, porChaveAtual, semChaveAtual);
        agrupar(registrosAnterior, campoChave, porChaveAnterior, semChaveAnterior);

        // Ordem do resultado: chaves do lote atual na ordem dele, depois as que só existiam antes
        Set<String> chaves = new LinkedHashSet<>(porChaveAtual.keySet());
        chaves.addAll(porChaveAnterior.keySet());

        List<Diferenca> diferencas = new ArrayList<>();
        for (String chave : chaves) {
            parear(porChaveAtual.getOrDefault(chave, List.of()),
                    porChaveAnterior.getOrDefault(chave, List.of()), true, diferencas);
        }
        parear(semChaveAtual, semChaveAnterior, false, diferencas);

        int adicionados = 0, removidos = 0, modificados = 0, inalterados = 0;
        for (Diferenca d : diferencas) {
            switch (d.situacao()) {
                case ComparacaoLotesDto.ADICIONADO -> adicionados++;
                case ComparacaoLotesDto.REMOVIDO -> removidos++;
                case ComparacaoLotesDto.MODIFICADO -> modificados++;
                default -> inalterados++;
            }
        }

        List<Diferenca> filtradas = filtro == null
                ? diferencas
                : diferencas.stream().filter(d -> filtro.equals(d.situacao())).toList();

        // Paginar — os mapas de dados só são montados para a página pedida
        int tamanhoPagina = Math.max(1, tamanho);
        int offset = Math.max(0, pagina) * tamanhoPagina;
        int fim = Math.min(offset + tamanhoPagina, filtradas.size());
        List<DiferencaRegistro> paginaResultado = offset < filtradas.size()
                ? filtradas.subList(offset, fim).stream().map(this::paraDto).toList()
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

    private void agrupar(List<RegistroBruto> registros, String campoChave,
            Map<String, List<Registro>> porChave, List<Registro> semChave) {
        for (RegistroBruto bruto : registros) {
            Registro reg = new Registro(bruto, parsearPayload(bruto.getPayload()));
            String chave = deduplicador.extrairChaveNatural(bruto, campoChave);
            if (chave == null || chave.isBlank()) {
                semChave.add(reg);
            } else {
                porChave.computeIfAbsent(chave.trim(), k -> new ArrayList<>()).add(reg);
            }
        }
    }

    /**
     * Pareia os registros de um grupo (mesma chave natural, ou o grupo dos sem chave).
     * Primeiro casa os de conteúdo idêntico (inalterado). Com chave, o que sobra é pareado
     * na ordem do lote e comparado campo a campo (modificado/inalterado); sem chave não há
     * como saber que dois registros diferentes são "o mesmo", então sobra vira
     * adicionado/removido. O excedente de um dos lados é adicionado/removido.
     */
    private void parear(List<Registro> atuais, List<Registro> anteriores, boolean temChave, List<Diferenca> saida) {
        // Índice pelo conteúdo: o grupo dos sem chave pode ter o lote inteiro
        Map<Conteudo, ArrayDeque<Registro>> anterioresPorConteudo = new HashMap<>();
        anteriores.forEach(r -> anterioresPorConteudo.computeIfAbsent(r.conteudo(), k -> new ArrayDeque<>()).add(r));

        List<Registro> restoAtual = new ArrayList<>();
        Set<Registro> casados = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Registro atual : atuais) {
            ArrayDeque<Registro> iguais = anterioresPorConteudo.get(atual.conteudo());
            Registro igual = iguais == null ? null : iguais.pollFirst();
            if (igual != null) {
                casados.add(igual);
                saida.add(new Diferenca(atual, igual, ComparacaoLotesDto.INALTERADO, List.of()));
            } else {
                restoAtual.add(atual);
            }
        }
        List<Registro> restoAnterior = anteriores.stream().filter(r -> !casados.contains(r)).toList();

        if (temChave) {
            int pares = Math.min(restoAtual.size(), restoAnterior.size());
            for (int i = 0; i < pares; i++) {
                saida.add(compararCampos(restoAtual.get(i), restoAnterior.get(i)));
            }
            restoAtual = restoAtual.subList(pares, restoAtual.size());
            restoAnterior = restoAnterior.subList(pares, restoAnterior.size());
        }

        restoAtual.forEach(r -> saida.add(new Diferenca(r, null, ComparacaoLotesDto.ADICIONADO, List.of())));
        restoAnterior.forEach(r -> saida.add(new Diferenca(null, r, ComparacaoLotesDto.REMOVIDO, List.of())));
    }

    private Diferenca compararCampos(Registro atual, Registro anterior) {
        List<CampoAlterado> camposAlterados = new ArrayList<>();
        Set<String> todasChaves = new LinkedHashSet<>(anterior.dados().keySet());
        todasChaves.addAll(atual.dados().keySet());

        for (String campo : todasChaves) {
            Object valAtual = atual.dados().get(campo);
            Object valAnterior = anterior.dados().get(campo);
            if (!ComparadorValores.iguais(valAtual, valAnterior)) {
                camposAlterados.add(new CampoAlterado(campo, valAnterior, valAtual));
            }
        }

        String wktAtual = atual.bruto().getGeometriaWkt();
        String wktAnterior = anterior.bruto().getGeometriaWkt();
        if (!Objects.equals(wktAtual, wktAnterior) && !ComparadorValores.geometriasIguais(wktAtual, wktAnterior)) {
            camposAlterados.add(new CampoAlterado("geometria_wkt",
                    resumirGeometria(wktAnterior), resumirGeometria(wktAtual)));
        }

        String situacao = camposAlterados.isEmpty() ? ComparacaoLotesDto.INALTERADO : ComparacaoLotesDto.MODIFICADO;
        return new Diferenca(atual, anterior, situacao, camposAlterados);
    }

    private DiferencaRegistro paraDto(Diferenca d) {
        Registro atual = d.atual();
        Registro anterior = d.anterior();
        return new DiferencaRegistro(
                atual != null ? atual.bruto().getId() : null,
                anterior != null ? anterior.bruto().getId() : null,
                atual != null ? atual.bruto().getNumeroLinha() : null,
                anterior != null ? anterior.bruto().getNumeroLinha() : null,
                d.situacao(),
                atual != null ? atual.dados() : null,
                anterior != null ? anterior.dados() : null,
                d.camposAlterados());
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

    /** Registro bruto com o payload já lido (lido uma vez só por registro). */
    private record Registro(RegistroBruto bruto, Map<String, Object> dados) {
        Conteudo conteudo() {
            return new Conteudo(dados, bruto.getGeometriaWkt());
        }
    }

    /** Payload + geometria: dois registros com o mesmo conteúdo são o mesmo dado. */
    private record Conteudo(Map<String, Object> dados, String geometriaWkt) {
    }

    private record Diferenca(Registro atual, Registro anterior, String situacao, List<CampoAlterado> camposAlterados) {
    }
}
