package br.gov.sp.cps.datagis.controle;

import java.time.LocalDate;
import lombok.Builder;
import lombok.Data;
import java.util.Map;

@Data
@Builder
public class ConjuntoResponse {
    private Long id;
    private Long fonteDadoId;
    private String fonteDadoNome;
    private LocalDate competencia;
    private StatusCamada statusCamada;
    private Map<String, Object> coberturaTerritorial;
}
