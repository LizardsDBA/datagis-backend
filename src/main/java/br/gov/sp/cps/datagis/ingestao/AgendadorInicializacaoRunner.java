package br.gov.sp.cps.datagis.ingestao;

import br.gov.sp.cps.datagis.controle.FonteDado;
import br.gov.sp.cps.datagis.controle.FonteDadoRepository;
import java.util.List;
import org.quartz.SchedulerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Roda uma vez, logo apos o backend subir: le as fontes ativas com
 * cron_expressao preenchida e manda o AgendamentoFonteService registrar cada
 * uma no Scheduler. E o que garante que os agendamentos existam mesmo depois
 * de um banco novo ou de uma fonte criada enquanto o backend estava parado.
 *
 * A logica de agendar em si vive no AgendamentoFonteService - o mesmo caminho
 * usado pelo FonteDadoService quando alguem salva uma fonte pela tela.
 */
@Component
public class AgendadorInicializacaoRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AgendadorInicializacaoRunner.class);

    private final FonteDadoRepository fonteDadoRepository;
    private final AgendamentoFonteService agendamentoFonteService;

    public AgendadorInicializacaoRunner(FonteDadoRepository fonteDadoRepository,
            AgendamentoFonteService agendamentoFonteService) {
        this.fonteDadoRepository = fonteDadoRepository;
        this.agendamentoFonteService = agendamentoFonteService;
    }

    @Override
    public void run(ApplicationArguments args) throws SchedulerException {
        List<FonteDado> fontesAgendaveis = fonteDadoRepository.findByAtivoTrueAndCronExpressaoIsNotNull();

        for (FonteDado fonte : fontesAgendaveis) {
            agendamentoFonteService.sincronizar(fonte);
        }

        log.info("Agendamento Quartz inicializado: {} fonte(s) agendada(s).", fontesAgendaveis.size());
    }
}