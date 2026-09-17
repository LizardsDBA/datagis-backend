package br.gov.sp.cps.datagis.controle;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/conjuntos")
@RequiredArgsConstructor
public class ConjuntoController {

    private final ConjuntoService conjuntoService;

    @GetMapping
    public List<ConjuntoResponse> listarTodos() {
        return conjuntoService.listarTodos();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ConjuntoResponse criar(@RequestBody ConjuntoRequest request) {
        return conjuntoService.criar(request);
    }
}
