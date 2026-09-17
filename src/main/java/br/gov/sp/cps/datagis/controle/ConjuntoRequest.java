package br.gov.sp.cps.datagis.controle;

import java.time.LocalDate;
import lombok.Data;
import org.locationtech.jts.geom.Geometry;

@Data
public class ConjuntoRequest {
    private Long fonteDadoId;
    private LocalDate competencia;
    private StatusCamada statusCamada;
    private Geometry coberturaTerritorial;
}
