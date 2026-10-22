package br.gov.sp.cps.datagis.controle;

import br.gov.sp.cps.datagis.controle.monitoramento.MonitoramentoGlobalResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller responsável pelo endpoint agregador de monitoramento global
 * das esteiras Bronze, Silver e Gold em segundo plano.
 */
@RestController
@RequestMapping("/api/monitoramento")
public class MonitoramentoController {

    private final MonitoramentoService monitoramentoService;

    @Autowired
    public MonitoramentoController(MonitoramentoService monitoramentoService) {
        this.monitoramentoService = monitoramentoService;
    }

    @GetMapping("/status-global")
    public ResponseEntity<MonitoramentoGlobalResponse> statusGlobal() {
        return ResponseEntity.ok(monitoramentoService.obterStatusGlobal());
    }
}
