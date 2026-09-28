package br.gov.sp.cps.datagis.ingestao;

import br.gov.sp.cps.datagis.controle.FonteDado;
import br.gov.sp.cps.datagis.controle.LoteIngestao;

/**
 * Sabe processar um tipo específico de fonte de dado. O disparo genérico
 * ({@link IngestaoExecucaoService}) nunca conhece qual fonte está por trás de um
 * {@code fonteId} — pergunta a cada executor registrado "você sabe processar essa
 * fonte?" (ver {@link #suportar}) e usa o primeiro que responder que sim.
 *
 * <p>Cadastrar uma fonte nova (CAR, IBGE, embargos, o que for) não exige mexer em
 * nenhum controller/service genérico — só criar um novo {@code @Service} implementando
 * esta interface. É o mesmo ponto onde o agendamento automático via Quartz (§3.2 do
 * CLAUDE.md) vai plugar mais adiante, disparando {@link #executar} em vez do usuário.
 */
public interface IngestaoExecutor {

    /** Esta implementação sabe buscar e gravar o dado bruto desta fonte? */
    boolean suportar(FonteDado fonte);

    /** Busca o dado da fonte e grava como registro_bruto, criando o lote_ingestao. */
    LoteIngestao executar(FonteDado fonte);
}
