package br.gov.sp.cps.datagis.controle;

import java.time.LocalDate;
import lombok.Builder;
import lombok.Data;
import org.locationtech.jts.geom.Geometry;

@Data
@Builder
public class ConjuntoResponse {
    private Long id;
    private Long fonteDadoId;
    private String fonteDadoNome;
    private LocalDate competencia;
    private StatusCamada statusCamada;
    private Geometry coberturaTerritorial;
}
