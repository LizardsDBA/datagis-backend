package br.gov.sp.cps.datagis.favorito;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Favoritos da tela inicial. Cada usuario so le e troca os PROPRIOS favoritos: o usuario
 * vem sempre do token, nunca da URL nem do corpo. Por isso o SecurityConfig so exige
 * login aqui, sem permissao especifica.
 */
@RestController
@RequestMapping("/api/favoritos")
public class FavoritoController {

    static final int MAXIMO = 20;
    /** Caminho de tela do frontend: /fontes, /admin/controle-acesso... */
    private static final Pattern ROTA = Pattern.compile("^/[a-z0-9/-]{0,199}$");

    private final FavoritoRepositorio repositorio;

    public FavoritoController(FavoritoRepositorio repositorio) {
        this.repositorio = repositorio;
    }

    @GetMapping
    public FavoritosResponse listar(@AuthenticationPrincipal Jwt usuario) {
        return new FavoritosResponse(repositorio.listar(usuario.getSubject()));
    }

    @PutMapping
    public FavoritosResponse substituir(@AuthenticationPrincipal Jwt usuario, @RequestBody FavoritosRequest request) {
        List<String> rotas = validar(request == null ? null : request.rotas());
        repositorio.substituir(usuario.getSubject(), rotas);
        return new FavoritosResponse(rotas);
    }

    /** IllegalArgumentException vira 400 no TratadorDeErro. */
    static List<String> validar(List<String> rotas) {
        if (rotas == null) {
            throw new IllegalArgumentException("Informe a lista de rotas (pode ser vazia)");
        }
        if (rotas.size() > MAXIMO) {
            throw new IllegalArgumentException("No maximo " + MAXIMO + " favoritos");
        }
        Set<String> vistas = new HashSet<>();
        for (String rota : rotas) {
            if (rota == null || !ROTA.matcher(rota).matches()) {
                throw new IllegalArgumentException("Rota invalida: " + rota);
            }
            if (!vistas.add(rota)) {
                throw new IllegalArgumentException("Rota repetida: " + rota);
            }
        }
        return List.copyOf(rotas);
    }

    public record FavoritosRequest(List<String> rotas) {
    }

    public record FavoritosResponse(List<String> rotas) {
    }
}
