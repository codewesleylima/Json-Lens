[![Wesley Lima](.github/banner.png)](https://linkedin.com/in/wesslima)

# JSON Lens — Live API Contracts para IntelliJ IDEA

[![LinkedIn](https://img.shields.io/badge/linkedin-wesslima-FF6A1A?style=flat-square&labelColor=080A0C&logo=linkedin)](https://linkedin.com/in/wesslima)

Plugin experimental **0.1.0** para visualizar em tempo quase real o request e response de métodos Spring MVC / Spring Cloud OpenFeign e verificar compatibilidade com contratos de outras aplicações.

> O repositório contém o código-fonte, exemplos e testes; não é o pacote instalável. O ZIP do plugin é gerado pelo Gradle em `build/distributions/json-lens-intellij-0.1.0.zip`.

## Recursos já implementados no código

- Tool Window `JSON Lens` na lateral direita do IntelliJ.
- Detecta `@RestController`, `@Controller`, `@FeignClient` e métodos `@GetMapping`, `@PostMapping`, `@PutMapping`, `@PatchMapping`, `@DeleteMapping`, `@RequestMapping`.
- Ao manter o cursor sobre um endpoint, analisa `@RequestBody` e tipo de retorno. Na classe, permite escolher outro método no combo.
- Ao selecionar uma chamada Java diretamente a um método `@FeignClient`, tenta resolver a assinatura de saída.
- Expande DTOs, records, atributos herdados, arrays, collections, maps, `Optional`, `ResponseEntity`, `Mono` e `Flux`; inclui anotações comuns `@JsonProperty`, `@JsonIgnore`, `@JsonNaming(SnakeCase)`, `@NotNull`, `@NotEmpty`, `@NotBlank`.
- Alterna entre **JSON de exemplo** (valores fictícios) e **esquema** (tipos/campos/requisitos).
- Observa eventos de modificação PSI de **todo o projeto**: qualquer alteração Java que invalide o PSI agenda nova análise do endpoint selecionado, com *debounce* de 350 ms e processamento em segundo plano, após indexação.
- Permite fixar um endpoint enquanto você edita outros DTOs.
- Importa diversos contratos externos `.json`, `.yaml` e `.yml`, mantendo lista local de arquivos recentemente selecionados.
- Aceita **OpenAPI 3.x**, **JSON Schema** ou um **JSON de exemplo**. Em OpenAPI, informe o método e path da operação externa; por padrão usa a rota atual.
- Compara recursivamente presença de campos obrigatórios, tipos, enum, propriedades extras e arrays. Exibe erros, avisos e caminho JSON (`$.livro.isbn`).
- Monitora no disco alterações no arquivo de contrato selecionado a cada 1,6 s e refaz a comparação.
- **Privacidade:** não chama IA nem envia código para serviços externos. O plugin apenas lê o projeto local e os contratos selecionados.

## Como compilar

**Requisitos para usar o plugin:** IntelliJ IDEA 2025.3 ou superior (build `253`+, com o plugin Java habilitado). O projeto que será analisado precisa conter fontes Java; as análises Spring reconhecem as anotações MVC e OpenFeign descritas acima. Não é necessário configurar Spring ou iniciar a aplicação para gerar as visualizações.

**Requisitos para compilar a partir do código-fonte:** JDK 21; o Gradle Wrapper incluído (Gradle 9.3.0); e acesso à Internet na **primeira compilação**, para baixar o Gradle, a IDE de desenvolvimento e as dependências. Não é necessário instalar Gradle globalmente.

1. Clone este repositório ou baixe o código-fonte e abra a pasta raiz `Json-Lens` no IntelliJ IDEA.
2. Importe como projeto Gradle. Configure o **Gradle JVM para JDK 21**.
3. Execute a task Gradle `buildPlugin` (em **Gradle > Tasks > intellij platform > buildPlugin**, ou no terminal `.\gradlew.bat buildPlugin`).
4. Depois de um build bem-sucedido, o plugin instalável ficará em `build/distributions/json-lens-intellij-0.1.0.zip` (verifique o nome real criado).
5. No IntelliJ em que quer usar: **Settings > Plugins > ⚙ > Install Plugin from Disk...** e selecione o ZIP **compilado**, não este ZIP de fontes.
6. Para rodar no IDE de desenvolvimento, use `.\gradlew.bat runIde`. Rode também `.\gradlew.bat test`, `.\gradlew.bat verifyPluginProjectConfiguration` e `.\gradlew.bat verifyPluginStructure`.

O projeto inclui um Gradle Wrapper (`gradlew`, `gradlew.bat` e `gradle/wrapper/gradle-wrapper.jar`), que fixa o Gradle 9.3.0. Use o wrapper para executar as mesmas versões localmente e na integração contínua:

```powershell
.\gradlew.bat clean buildPlugin test verifyPlugin verifyPluginStructure verifyPluginProjectConfiguration
```

Em Linux/macOS, use `./gradlew` no lugar de `.\gradlew.bat`. A primeira execução precisa de Internet para baixar o Gradle, a IDE de desenvolvimento e as dependências. O workflow `.github/workflows/build.yml` executa a validação, faz o build e publica o ZIP como artefato do GitHub Actions.

## Roteiro do exemplo da Biblioteca

1. Crie um projeto Java Spring com Spring MVC e OpenFeign. Copie o conteúdo de `examples/BibliotecaClient.java` ajustando o package/imports conforme sua aplicação.
2. Abra `BibliotecaClient.java` e coloque o cursor em `criar(...)`.
3. Abra a janela **JSON Lens**. Na aba **Request**, confirme que o campo aparece como `codigoIsbn` (por causa de `@JsonProperty`).
4. Vá até **Comparação** e importe `examples/biblioteca-openapi.yaml`. Use **Request**, `POST` e `/v1/acervo/livros`.
5. O comparador deve identificar que `isbn` está ausente e que `codigoIsbn` é extra/proibido pelo schema. Corrija `@JsonProperty("codigoIsbn")` para `@JsonProperty("isbn")`.
6. A análise deve refazer a comparação quando o documento Java for aplicado/confirmado pelo PSI. Se não atualizar imediatamente, tire e volte o cursor ao método ou use a seleção de método.
7. Para testar outro contrato, importe o arquivo adicional e selecione-o na lista de contratos.

## Diferença entre controller e cliente de integração

- **Controller (INBOUND):** indica o JSON que seu endpoint espera receber e o tipo que declara retornar.
- **Feign (OUTBOUND):** indica o tipo da requisição declarada para envio à outra aplicação.
- **Não rastreia automaticamente uma transformação** `Controller → Service → Mapper → RestTemplate/WebClient → HTTP`. Validar com 100% de fidelidade os bytes da requisição real exige interceptar HTTP em execução e/ou inspecionar os mapeamentos customizados.

## Precisão e limites conhecidos

- **Tempo real:** usa alterações estruturais e de conteúdo expostas pelo PSI, com *debounce* de ~350 ms e reanálise em background. O resultado depende do commit do documento e do índice Java disponível. Não há garantia de atualização em cada tecla enquanto o código está sintaticamente incompleto.
- **Contrato externo:** o parser lê arquivos salvos; edição não salva no editor de texto será detectada quando gravar em disco.
- Exemplos de campo não refletem dados reais. Não executa código, chama APIs ou lê valores do banco.
- Suporte a `@JsonView`, `@JsonUnwrapped`, getters computados, `@JsonTypeInfo`, `@JsonValue`, `@JsonCreator`, custom `ObjectMapper`, validações condicionais e generics complexos é parcial ou ausente; a interface exibe avisos para alguns desses casos.
- Enums com serialização especial precisam ser verificados manualmente.
- Um **JSON de exemplo** não especifica formalmente obrigatoriedade, limites ou enum. Os campos presentes são assumidos como esperados; prefira OpenAPI/JSON Schema para decisões de integração.
- OpenAPI suporta apenas `$ref` interno (`#/...`), e trata `oneOf`/`anyOf` de maneira parcial (sinalizado). A análise escolhe uma resposta HTTP comum 200/201/202/204/default, sem cobrir todos os status e esquemas possíveis.
- Não analisa automaticamente clientes REST/HTTP que não sejam Feign (RestTemplate, WebClient, Java HttpClient), nem contratos Protobuf/Avro/GraphQL, nem controllers Kotlin.
- Comparação estrutural ≠ conformidade integral em runtime. Campos dinâmicos, headers, autenticação, integração, valores, `@Pattern`, ranges, `maxLength` e outros constraints precisam de validação adicional.
- Para `Page<T>` a visualização é aproximada como array de conteúdo; a estrutura JSON real do `Page` é mais complexa.
- **Não há upload do código**; a importação de arquivos do computador só fornece dados ao plugin local.

## Evoluções recomendadas

1. Inspector de transformações `Controller → Service → Mapper → Feign` usando call graph e análise de MapStruct.
2. Inspector de `RestTemplate`, `WebClient`, Spring `RestClient`, @HttpExchange, e payloads com serialização customizada.
3. Modo de verificação de tráfego real (opt-in), com mascaramento de dados sensíveis, sem enviar conteúdo a terceiros.
4. Validação formal JSON Schema 2020-12 com `allOf`/`oneOf`/`anyOf`, formatos, regex, min/max, nullability e dereferenciamento seguro.
5. Tests end-to-end de IDE: edição de DTO A enquanto o método B permanece fixado, alteração de contrato, refatoração de nomes, enum e imports.

### Referências técnicas

- [JetBrains Tool Windows](https://plugins.jetbrains.com/docs/intellij/tool-windows.html)
- [IntelliJ IDEA Java Plugin APIs](https://plugins.jetbrains.com/docs/intellij/idea.html)
- [PSI Cookbook](https://plugins.jetbrains.com/docs/intellij/psi-cookbook.html)
- [IntelliJ Platform Gradle Plugin](https://plugins.jetbrains.com/docs/intellij/configuring-gradle.html)
- [IntelliJ API changes 2026](https://plugins.jetbrains.com/docs/intellij/api-changes-list-2026.html)
