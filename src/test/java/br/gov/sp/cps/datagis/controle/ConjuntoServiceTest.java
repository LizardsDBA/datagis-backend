package br.gov.sp.cps.datagis.controle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.locationtech.jts.io.WKTReader;

@ExtendWith(MockitoExtension.class)
class ConjuntoServiceTest {
    @Mock private ConjuntoRepository repository;
    @Mock private FonteDadoRepository fontes;
    @InjectMocks private ConjuntoService service;

    @Test
    void cadastraConjuntoComFonteExistenteEGeometriaOpcional() {
        var fonte = FonteDado.builder().id(7L).nome("CAR PR").build();
        when(fontes.findById(7L)).thenReturn(Optional.of(fonte));
        when(repository.save(any(Conjunto.class))).thenAnswer(call -> {
            Conjunto salvo = call.getArgument(0);
            salvo.setId(42L);
            return salvo;
        });
        var request = new ConjuntoRequest();
        request.setFonteDadoId(7L);
        request.setCompetencia(LocalDate.of(2026, 9, 1));
        request.setStatusCamada(StatusCamada.ATIVA);

        var response = service.criar(request);

        var captor = ArgumentCaptor.forClass(Conjunto.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getFonteDado()).isSameAs(fonte);
        assertThat(response.getId()).isEqualTo(42L);
        assertThat(response.getFonteDadoId()).isEqualTo(7L);
        assertThat(response.getFonteDadoNome()).isEqualTo("CAR PR");
        assertThat(response.getCompetencia()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(response.getStatusCamada()).isEqualTo(StatusCamada.ATIVA);
        assertThat(response.getCoberturaTerritorial()).isNull();
    }

    @Test
    void naoPersisteQuandoFonteNaoExiste() {
        when(fontes.findById(99L)).thenReturn(Optional.empty());
        var request = new ConjuntoRequest();
        request.setFonteDadoId(99L);

        assertThatThrownBy(() -> service.criar(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Fonte de Dado não encontrada: 99");
        verifyNoInteractions(repository);
    }

    @Test
    void preservaGeometriaESridNoCadastro() throws Exception {
        var geometria = new WKTReader().read("POLYGON ((-50 -25, -49 -25, -49 -24, -50 -25))");
        geometria.setSRID(4674);
        when(fontes.findById(7L)).thenReturn(Optional.of(FonteDado.builder().id(7L).build()));
        when(repository.save(any(Conjunto.class))).thenAnswer(call -> call.getArgument(0));
        var request = new ConjuntoRequest();
        request.setFonteDadoId(7L);
        request.setCompetencia(LocalDate.of(2026, 9, 1));
        request.setStatusCamada(StatusCamada.PROCESSANDO);
        request.setCoberturaTerritorial(geometria);

        var response = service.criar(request);

        var captor = ArgumentCaptor.forClass(Conjunto.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getCoberturaTerritorial().equalsExact(geometria)).isTrue();
        assertThat(captor.getValue().getCoberturaTerritorial().getSRID()).isEqualTo(4674);
        assertThat(response.getCoberturaTerritorial()).containsEntry("type", "Polygon");
    }

    @Test
    void listaConjuntosComIdentificacaoDaFonte() {
        var fonte = FonteDado.builder().id(7L).nome("CAR PR").build();
        when(repository.findAll()).thenReturn(List.of(Conjunto.builder()
                .id(42L).fonteDado(fonte).competencia(LocalDate.of(2026, 9, 1))
                .statusCamada(StatusCamada.INATIVA).build()));

        assertThat(service.listarTodos()).singleElement().satisfies(response -> {
            assertThat(response.getId()).isEqualTo(42L);
            assertThat(response.getFonteDadoNome()).isEqualTo("CAR PR");
            assertThat(response.getFonteDadoId()).isEqualTo(7L);
            assertThat(response.getStatusCamada()).isEqualTo(StatusCamada.INATIVA);
            assertThat(response.getCompetencia()).isEqualTo(LocalDate.of(2026, 9, 1));
        });
    }

    @Test
    void listaVaziaQuandoNaoHaConjuntos() {
        when(repository.findAll()).thenReturn(List.of());
        assertThat(service.listarTodos()).isEmpty();
    }
}
