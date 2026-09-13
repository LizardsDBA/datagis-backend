package br.gov.sp.cps.datagis.ingestao;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ProgressoExecucaoRegistryTest {

    @Test
    void deveVirOcioSoQuandoNuncaExecutou() {
        ProgressoExecucaoRegistry registry = new ProgressoExecucaoRegistry();

        assertThat(registry.obter(99L)).isEmpty();
    }

    @Test
    void iniciarDeveMarcarFaseIniciando() {
        ProgressoExecucaoRegistry registry = new ProgressoExecucaoRegistry();

        registry.iniciar(1L);

        assertThat(registry.obter(1L)).isPresent();
        assertThat(registry.obter(1L).get().fase()).isEqualTo(FaseExecucao.INICIANDO);
        assertThat(registry.obter(1L).get().registrosProcessados()).isZero();
    }

    @Test
    void atualizarDeveSobrescreverOEstadoAnterior() {
        ProgressoExecucaoRegistry registry = new ProgressoExecucaoRegistry();
        registry.iniciar(1L);

        registry.atualizar(1L, FaseExecucao.GRAVANDO, 150, 500L, "arquivo 2 de 4");

        ProgressoExecucao estado = registry.obter(1L).orElseThrow();
        assertThat(estado.fase()).isEqualTo(FaseExecucao.GRAVANDO);
        assertThat(estado.registrosProcessados()).isEqualTo(150);
        assertThat(estado.totalEstimado()).isEqualTo(500L);
        assertThat(estado.detalhe()).isEqualTo("arquivo 2 de 4");
    }

    /**
     * {@link ProgressoExecucaoRegistry#finalizar} precisa preservar a última contagem
     * conhecida — é o que permite a tela mostrar "554.851 registros" na mensagem final,
     * não só "CONCLUIDO" sem número nenhum.
     */
    @Test
    void finalizarDevePreservarUltimaContagemEZerarDetalhe() {
        ProgressoExecucaoRegistry registry = new ProgressoExecucaoRegistry();
        registry.atualizar(1L, FaseExecucao.GRAVANDO, 559899, 559899L, "arquivo 4 de 4");

        registry.finalizar(1L, FaseExecucao.CONCLUIDO);

        ProgressoExecucao estado = registry.obter(1L).orElseThrow();
        assertThat(estado.fase()).isEqualTo(FaseExecucao.CONCLUIDO);
        assertThat(estado.registrosProcessados()).isEqualTo(559899);
        assertThat(estado.totalEstimado()).isEqualTo(559899L);
        assertThat(estado.detalhe()).isNull();
    }

    @Test
    void finalizarSemEstadoAnteriorNaoDeveEstourar() {
        ProgressoExecucaoRegistry registry = new ProgressoExecucaoRegistry();

        registry.finalizar(7L, FaseExecucao.ERRO);

        ProgressoExecucao estado = registry.obter(7L).orElseThrow();
        assertThat(estado.fase()).isEqualTo(FaseExecucao.ERRO);
        assertThat(estado.registrosProcessados()).isZero();
    }

    @Test
    void cadaFonteTemProgressoIndependente() {
        ProgressoExecucaoRegistry registry = new ProgressoExecucaoRegistry();

        registry.atualizar(1L, FaseExecucao.GRAVANDO, 10, 100L, null);
        registry.atualizar(2L, FaseExecucao.DESCOMPACTANDO, 3, null, "arquivo.shp");

        assertThat(registry.obter(1L).orElseThrow().fase()).isEqualTo(FaseExecucao.GRAVANDO);
        assertThat(registry.obter(2L).orElseThrow().fase()).isEqualTo(FaseExecucao.DESCOMPACTANDO);
    }
}
