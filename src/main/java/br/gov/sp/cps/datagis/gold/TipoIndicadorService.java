package br.gov.sp.cps.datagis.gold;

import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Serviço responsável pelas regras de negócio e governança dos tipos de indicadores (camada Gold).
 */
@Service
public class TipoIndicadorService {

    private final TipoIndicadorRepository repository;

    public TipoIndicadorService(TipoIndicadorRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<TipoIndicador> listar() {
        return repository.findAll();
    }

    @Transactional(readOnly = true)
    public TipoIndicador buscarPorId(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Tipo de indicador com ID " + id + " não encontrado"));
    }

    @Transactional
    public TipoIndicador cadastrar(TipoIndicadorRequest dados) {
        if (dados == null) {
            throw new IllegalArgumentException("Dados do tipo de indicador não podem ser nulos");
        }

        String codigo = validarEFormatadorCodigo(dados.codigo());
        String descricao = validarDescricao(dados.descricao());

        if (repository.existsByCodigo(codigo)) {
            throw new IllegalArgumentException("Já existe um tipo de indicador com o código " + codigo);
        }

        TipoIndicador tipo = TipoIndicador.builder()
                .codigo(codigo)
                .descricao(descricao)
                .temFonte(dados.temFonte() != null ? dados.temFonte() : true)
                .ativo(dados.ativo() != null ? dados.ativo() : true)
                .dataAdd(LocalDateTime.now())
                .build();

        return repository.save(tipo);
    }

    @Transactional
    public TipoIndicador atualizar(Long id, TipoIndicadorRequest dados) {
        if (dados == null) {
            throw new IllegalArgumentException("Dados para atualização não podem ser nulos");
        }

        TipoIndicador tipo = buscarPorId(id);

        String descricao = validarDescricao(dados.descricao());
        tipo.setDescricao(descricao);

        if (dados.temFonte() != null) {
            tipo.setTemFonte(dados.temFonte());
        }

        if (dados.ativo() != null) {
            tipo.setAtivo(dados.ativo());
        }

        // Regra de negócio: 'codigo' é imutável após a criação para garantir rastreabilidade e integridade da linhagem.
        return repository.save(tipo);
    }

    @Transactional
    public TipoIndicador desativar(Long id) {
        TipoIndicador tipo = buscarPorId(id);
        tipo.setAtivo(false);
        return repository.save(tipo);
    }

    private String validarEFormatadorCodigo(String codigo) {
        if (codigo == null || codigo.isBlank()) {
            throw new IllegalArgumentException("Código do tipo de indicador é obrigatório");
        }
        String codigoLimpo = codigo.trim();
        if (codigoLimpo.length() > 20) {
            throw new IllegalArgumentException("Código não pode ter mais de 20 caracteres");
        }
        return codigoLimpo.toUpperCase();
    }

    private String validarDescricao(String descricao) {
        if (descricao == null || descricao.isBlank()) {
            throw new IllegalArgumentException("Descrição do tipo de indicador é obrigatória");
        }
        String descricaoLimpa = descricao.trim();
        if (descricaoLimpa.length() > 200) {
            throw new IllegalArgumentException("Descrição não pode ter mais de 200 caracteres");
        }
        return descricaoLimpa;
    }
}
