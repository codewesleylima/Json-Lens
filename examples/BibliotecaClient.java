// Exemplo para colar em um projeto Spring Boot que usa Spring Cloud OpenFeign.
package exemplo.biblioteca;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import java.util.UUID;

@FeignClient(name = "biblioteca", url = "${biblioteca.url}")
public interface BibliotecaClient {
    @PostMapping("/v1/acervo/livros")
    BibliotecaResponse criar(@RequestBody LivroParaBiblioteca request);
}

record LivroParaBiblioteca(
    @NotBlank String titulo,
    @NotBlank String autor,
    @NotNull Integer anoPublicacao,
    // Este nome diverge do contrato externo (que pede "isbn"):
    @JsonProperty("codigoIsbn") String isbn
) {}

record BibliotecaResponse(UUID id, String situacao) {}
