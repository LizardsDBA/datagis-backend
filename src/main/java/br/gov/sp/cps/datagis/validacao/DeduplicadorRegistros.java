package br.gov.sp.cps.datagis.validacao;

import br.gov.sp.cps.datagis.bronze.RegistroBruto;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 2.8. Deduplicador de Registros (Seleção do Mais Novo para a Silver).
 *
 * <p>Responsável por:
 * <ul>
 *   <li>Agrupar registros pela chave natural de negócio (ex.: {@code cod_imovel}, {@code numero_termo}, etc.);</li>
 *   <li>Quando houver ocorrências duplicadas (mesma chave natural vinda em lotes/snapshots distintos ou
 *       repetida no mesmo lote), selecionar o registro mais recente como vencedor para ser promovido à Silver;</li>
 *   <li>Critério de prioridade temporal:
 *     <ol>
 *       <li>Prioridade de negócio para o CAR: registros com {@code situacao_imovel = "AT"} têm precedência
 *           sobre registros históricos cancelados ({@code "CA"}) ou pendentes ({@code "PE"});</li>
 *       <li>Snapshot mais recente: maior {@code loteIngestaoId};</li>
 *       <li>Sequência mais recente: maior {@code dataIngestao} ou maior {@code id}.</li>
 *     </ol>
 *   </li>
 *   <li>Retornar os registros únicos promovidos e a lista detalhada de duplicatas descartadas com os motivos.</li>
 * </ul>
 */
@Component
public class DeduplicadorRegistros {

    private final ObjectMapper objectMapper;

    @Autowired
    public DeduplicadorRegistros(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public DeduplicadorRegistros() {
        this(new ObjectMapper());
    }

    /**
     * Deduplica uma coleção de {@link RegistroBruto} utilizando o nome do campo
     * presente no JSON do {@code payload} como chave natural.
     *
     * @param registros coleção de registros brutos a serem deduplicados
     * @param campoChave nome da chave no payload JSON (ex.: {@code "cod_imovel"}, {@code "numero_termo"})
     * @return resultado estruturado com os registros mais novos promovidos e as duplicatas descartadas
     */
    public ResultadoDeduplicacao<RegistroBruto> deduplicar(Collection<RegistroBruto> registros, String campoChave) {
        if (registros == null || registros.isEmpty()) {
            return new ResultadoDeduplicacao<>(List.of(), List.of(), 0, 0, 0);
        }

        Function<RegistroBruto, String> extratorChave = r -> extrairChaveDoPayload(r, campoChave);
        Comparator<RegistroBruto> comparadorRecente = criarComparadorRegistroBruto();

        return deduplicarGenerico(registros, extratorChave, comparadorRecente);
    }

    /**
     * Deduplica uma coleção qualquer de itens a partir de uma função extratora de chave
     * e de um comparador de precedência (onde o registro de maior prioridade/mais novo fica no topo).
     */
    public <T> ResultadoDeduplicacao<T> deduplicarGenerico(
            Collection<T> itens,
            Function<T, String> extratorChave,
            Comparator<T> comparadorRecente) {

        if (itens == null || itens.isEmpty()) {
            return new ResultadoDeduplicacao<>(List.of(), List.of(), 0, 0, 0);
        }

        // Agrupa por chave preservando ordem de inserção
        Map<String, List<T>> agrupados = new LinkedHashMap<>();
        List<T> itensSemChave = new ArrayList<>();

        for (T item : itens) {
            String chave = extratorChave != null ? extratorChave.apply(item) : null;
            if (chave == null || chave.isBlank()) {
                itensSemChave.add(item);
            } else {
                agrupados.computeIfAbsent(chave.trim(), k -> new ArrayList<>()).add(item);
            }
        }

        List<T> promovidos = new ArrayList<>();
        List<DuplicataDescartada<T>> descartados = new ArrayList<>();

        // Registros sem chave não sofrem colisão
        promovidos.addAll(itensSemChave);

        for (Map.Entry<String, List<T>> entry : agrupados.entrySet()) {
            String chave = entry.getKey();
            List<T> grupo = entry.getValue();

            if (grupo.size() == 1) {
                promovidos.add(grupo.get(0));
            } else {
                // Ordena pelo critério de 'mais recente primeiro'
                grupo.sort(comparadorRecente);

                T vencedor = grupo.get(0);
                promovidos.add(vencedor);

                // Demais ocorrências são descartadas como obsoletas
                for (int i = 1; i < grupo.size(); i++) {
                    T obsoleto = grupo.get(i);
                    String motivo = "Substituído por registro mais recente da mesma chave natural \"" + chave + "\"";
                    descartados.add(new DuplicataDescartada<>(chave, obsoleto, vencedor, motivo));
                }
            }
        }

        return new ResultadoDeduplicacao<>(
                promovidos,
                descartados,
                itens.size(),
                promovidos.size(),
                descartados.size()
        );
    }

    /**
     * Cria o comparador padrão para {@link RegistroBruto}:
     * 1º Situação CAR: "AT" vence "CA" / "PE"
     * 2º Maior loteIngestaoId (mais novo)
     * 3º Maior dataProcessamento
     * 4º Maior id
     */
    private Comparator<RegistroBruto> criarComparadorRegistroBruto() {
        return (r1, r2) -> {
            // Regra do CAR: AT tem prioridade sobre outros status
            int carPrioridade = compararPrioridadeSituacaoCar(r1, r2);
            if (carPrioridade != 0) {
                return carPrioridade;
            }

            // Maior loteIngestaoId vence
            Long lote1 = r1.getLoteIngestaoId() != null ? r1.getLoteIngestaoId() : 0L;
            Long lote2 = r2.getLoteIngestaoId() != null ? r2.getLoteIngestaoId() : 0L;
            int compLote = lote2.compareTo(lote1); // descrescente
            if (compLote != 0) {
                return compLote;
            }

            // Maior dataIngestao vence
            LocalDateTime dt1 = r1.getDataIngestao() != null ? r1.getDataIngestao() : LocalDateTime.MIN;
            LocalDateTime dt2 = r2.getDataIngestao() != null ? r2.getDataIngestao() : LocalDateTime.MIN;
            int compDt = dt2.compareTo(dt1); // decrescente
            if (compDt != 0) {
                return compDt;
            }

            // Maior id vence
            Long id1 = r1.getId() != null ? r1.getId() : 0L;
            Long id2 = r2.getId() != null ? r2.getId() : 0L;
            return id2.compareTo(id1); // decrescente
        };
    }

    private int compararPrioridadeSituacaoCar(RegistroBruto r1, RegistroBruto r2) {
        String s1 = extrairCampoDoPayload(r1, "situacao_imovel");
        if (s1 == null) {
            s1 = extrairCampoDoPayload(r1, "situacao");
        }
        String s2 = extrairCampoDoPayload(r2, "situacao_imovel");
        if (s2 == null) {
            s2 = extrairCampoDoPayload(r2, "situacao");
        }

        boolean at1 = "AT".equalsIgnoreCase(s1);
        boolean at2 = "AT".equalsIgnoreCase(s2);

        if (at1 && !at2) {
            return -1; // r1 tem prioridade (vem antes)
        }
        if (!at1 && at2) {
            return 1;  // r2 tem prioridade
        }
        return 0;
    }

    private String extrairChaveDoPayload(RegistroBruto r, String campoChave) {
        return extrairCampoDoPayload(r, campoChave);
    }

    private String extrairCampoDoPayload(RegistroBruto r, String campo) {
        if (r == null || r.getPayload() == null || r.getPayload().isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(r.getPayload());
            JsonNode node = root.path(campo);
            if (node.isMissingNode() || node.isNull()) {
                return null;
            }
            return node.asString();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Representa um registro duplicado que foi descartado em favor de uma versão mais nova.
     */
    public record DuplicataDescartada<T>(
            String chaveNatural,
            T registroDescartado,
            T registroVencedor,
            String motivo
    ) {}

    /**
     * Resultado completo do processo de deduplicação.
     */
    public record ResultadoDeduplicacao<T>(
            List<T> registrosPromovidos,
            List<DuplicataDescartada<T>> duplicatasDescartadas,
            int totalRecebidos,
            int totalPromovidos,
            int totalDescartados
    ) {
        public boolean houveDuplicatas() {
            return totalDescartados > 0;
        }
    }
}
