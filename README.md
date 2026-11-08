[![Wesley Lima](.github/banner.png)](https://linkedin.com/in/wesslima)

# JSON Lens — Live API Contracts para IntelliJ IDEA

[![LinkedIn](https://img.shields.io/badge/linkedin-wesslima-FF6A1A?style=flat-square&labelColor=080A0C&logo=linkedin)](https://linkedin.com/in/wesslima)

Plugin experimental **0.1.0** para visualizar requests e responses declarados no Java e localizar, por similaridade, controllers e clientes Feign relacionados a um cURL ou collection de API.

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
- Aceita cURL colado/carregado e collections **Postman v2.1** e **Insomnia JSON**.
- Varre os endpoints Java indexados e ranqueia separadamente controllers (`@Controller`/`@RestController`) e clientes (`@FeignClient`) por método HTTP, rota e estrutura do body.
- Cada sugestão mostra pontuação, evidências e nome do arquivo; **Abrir código** navega até a declaração.
- Analisa todas as requests de uma collection sem enviá-las; cURL e collections nunca são executados.
- Reutiliza a inspeção dos endpoints enquanto o projeto não muda e executa parsing, ranking e preparação de resultados em segundo plano.
- Renderiza resultados gradualmente e carrega detalhes ao expandir, para reduzir o trabalho inicial da interface.
- **Privacidade:** não chama IA nem envia código para serviços externos. O plugin apenas lê o projeto e os arquivos cURL/collection selecionados localmente.

## Interface web JCEF (IntelliJ)

A tool window **JSON Lens** agora renderiza a interface HTML isolada aprovada (`src/main/resources/web/json-lens.html`) em um **JBCefBrowser**, usando CSS preto/laranja. Os componentes em `src/main/resources/web/jsonlens-native.js` são apenas a camada visual e enviam ações ao Java por `JBCefJSQuery`.

- **Preview:** endpoint atual, escolha de método, pin/unpin, Request/Response, JSON de exemplo ou Schema e cópia. Os dados exibidos são produzidos pela inspeção PSI Java e atualizados após alterações no projeto.
- **Localizar interação:** cURL, Postman v2.1 e Insomnia, ranking separado de aderência, resultados preservados por origem, detalhes de campos e navegação até o método Java.
- **Contratos:** JSON de exemplo, JSON Schema, OpenAPI JSON/YAML, Request/Response e comparação estática pela engine Java. Ao clicar em **Comparar contrato** num resultado, a comparação usa aquele candidato específico.
- **Detalhes:** metadados, notas e avisos produzidos pela inspeção Java.
- **Preferências:** idioma, tema e fontes. Sem IA, envio de código ou execução de HTTP.
- **Sem JCEF:** o plugin usa a implementação anterior em Swing, que permanece no código como alternativa.

**Requisito:** IntelliJ IDEA **2025.3.1 ou superior** com suporte Java e o módulo/plugin JCEF. O suporte começa no build `253.29346.138` por causa da dependência `com.intellij.modules.jcef`. O Java Runtime da IDE precisa oferecer JCEF; em runtimes personalizados sem suporte o plugin usa Swing.

**Observação:** o checklist de validação e a edição de DTO demonstrativa presentes no HTML original foram ocultados na tool window. Para editar Java de verdade, use o editor da IDE. A inspeção é estática e não garante payload de runtime.

### Verificação manual recomendada

1. Execute `./gradlew runIde`, abra um projeto Java Spring e a janela JSON Lens.
2. Navegue entre um `@RestController` e um `@FeignClient`, escolhendo métodos, fixando e desafixando; edite um DTO e confirme atualização após debounce.
3. Teste Request, Response, Schema e Copiar; compare o exemplo de `BibliotecaClient.java` com `examples/biblioteca-openapi.yaml` e confira `codigoIsbn` versus `isbn`.
4. Importe cURL e collections, veja resultados por direção, alterne entre origens, abra o código e compare o candidato selecionado.
5. Teste larguras de **320, 390, 505 e 740 px**, fontes ampliadas, caminhos longos, foco via teclado e redução/expansão da tool window. Não deve haver texto fora dos cards.
6. Faça o teste com runtime sem JCEF (quando disponível) para verificar a interface Swing alternativa.

## Como compilar

**Requisitos para usar o plugin:** IntelliJ IDEA 2025.3.1 ou superior (build `253.29346.138`+, com os plugins Java e JCEF disponíveis). O projeto que será analisado precisa conter fontes Java; as análises Spring reconhecem as anotações MVC e OpenFeign descritas acima. Não é necessário configurar Spring ou iniciar a aplicação para gerar as visualizações.

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

## Como usar

Os passos abaixo descrevem a interface JCEF. No modo alternativo Swing, os nomes e a disposição dos controles podem variar.

### 1. Abrir o plugin e visualizar um endpoint

1. Instale o ZIP compilado conforme a seção anterior, reinicie a IDE se solicitado e abra um projeto Java Spring.
2. Aguarde a indexação e abra a tool window **JSON Lens**, na lateral da IDE.
3. No editor Java, posicione o cursor em um método de controller ou cliente Feign reconhecido. Na aba **Preview**, confira a direção, o método HTTP, a rota e o nome do endpoint.
4. Se houver mais de um método, escolha o desejado no seletor. Use **Fixar** para continuar acompanhando esse endpoint enquanto navega ou edita outros arquivos; use **Desafixar** para voltar a acompanhar o editor.
5. Alterne entre **Request** e **Response**. Ative **Mostrar schema** para trocar o JSON de exemplo pelo schema inferido; use **Copiar** para copiar a visualização atual.
6. Abra **Detalhes** para consultar a direção, rota, parâmetros e avisos da análise.

O JSON de exemplo contém valores fictícios. Alterações no código são acompanhadas com atraso aproximado de 350 ms, após a atualização do PSI; enquanto o Java estiver incompleto ou a IDE estiver indexando, o resultado pode demorar a refletir a edição.

### 2. Localizar uma interação

1. Abra **Localizar** e selecione **cURL** ou **Collection**.
2. Em **cURL**, cole o comando ou use **Abrir .txt / .curl**. Em **Collection**, carregue um arquivo JSON exportado do Postman v2.1 ou Insomnia. A prévia é formatada para leitura; para arquivos grandes, é exibido apenas o início original, mas a busca analisa o conteúdo completo.
3. Clique em **Localizar no código**. Para cada request, o plugin apresenta candidatos separados em **Recebem / Controllers** e **Enviam / Feign Clients**.
4. Confira rota, método, evidências e pontuações. Use **Expandir** para ver os exemplos inferidos de request e response e possíveis divergências estruturais; **Abrir código** leva à declaração Java.
5. Para comparar um candidato com um contrato externo, clique em **Comparar contrato** no cartão correspondente e siga os passos da próxima seção.

Os resultados de cURL e collection são mantidos separadamente durante a sessão. O divisor da área de busca pode ser arrastado para ajustar a entrada e os resultados. A busca apenas interpreta os dados: **não executa o cURL nem as requests da collection**.

Para evitar trabalho repetido, mover o cursor não refaz buscas anteriores; alterações no PSI do projeto agendam uma nova análise. A lista de endpoints é reutilizada enquanto a revisão do projeto não muda. Resultados aparecem em lotes e os detalhes são preparados ao expandir cada cartão. Essas medidas reduzem trabalho desnecessário, mas o tempo de resposta ainda depende do projeto, da indexação e do tamanho das entradas.

O **match** é um ranking heurístico de semelhança entre a requisição e o endpoint. A **aderência** é uma estimativa de comparação do payload; são medidas diferentes e nenhuma comprova o tráfego em execução. Sem body JSON analisável, a busca pode depender apenas de rota e método, e a aderência pode não estar disponível.

### 3. Comparar um contrato

1. Para comparar o endpoint aberto no editor, selecione-o em **Preview** e abra **Contratos**. Para comparar outro candidato, use **Comparar contrato** no resultado de uma busca.
2. Selecione **Request** ou **Response** e cole o contrato no campo de texto, ou clique em **Carregar contrato JSON / YAML…**. São aceitos JSON de exemplo, JSON Schema e OpenAPI 3.x em JSON ou YAML.
3. Confira o percentual estimado, erros, avisos e caminhos dos campos apontados. A análise é atualizada ao editar o contrato; **Validar contrato** também solicita a comparação.
4. Ajuste o DTO ou o contrato conforme necessário e acompanhe o resultado após a reanálise. A comparação é estática: mesmo 100% não garante compatibilidade em runtime.

Em OpenAPI, o método HTTP e a rota precisam corresponder à operação Java; a opção selecionada determina se será usado o schema do request ou de uma resposta suportada. JSON de exemplo não define formalmente campos obrigatórios. As comparações consideram a direção: no request, um controller é receptor e um Feign é emissor; no response, os papéis se invertem.

**Exemplo incluído no repositório:** adicione `examples/BibliotecaClient.java` a um projeto Java com as dependências Spring Cloud OpenFeign, Jackson e Jakarta Validation, aguarde a indexação e selecione o método `criar`. Em **Contratos**, escolha **Request** e carregue `examples/biblioteca-openapi.yaml`. O contrato espera `isbn`, mas o DTO declara `codigoIsbn` via `@JsonProperty`: examine os apontamentos e ajuste o mapeamento conforme o contrato. Esse exemplo é fictício e não envia dados a outro serviço.

### 4. Ajustar aparência

Use o botão de **configurações** no cabeçalho para escolher idioma, tema e fontes/tamanhos para informações, JSON e cURL. Os temas disponíveis são **Ember**, **Midnight**, **Base16** e **Ocean**; as preferências são salvas localmente. A interface HTML oferece famílias de fonte genéricas; no Swing, a seleção de fontes pode ser diferente.

### Formatos e limites

- cURL: até 2 MB; `--data-urlencode` não é suportado. Para comparar o body, forneça JSON.
- Collection: até 5 MB e 200 requests; use um export JSON Postman v2.1 ou Insomnia.
- Contrato na interface JCEF: até 1 MB. Em OpenAPI, são reconhecidas referências locais `#/...`, não referências externas; `allOf` é tratado parcialmente e somente a primeira alternativa de `oneOf` ou `anyOf` é analisada.
- O código de saída Feign pode fornecer indícios estáticos de mapeamento de valores, mas transformações indiretas, mappers e valores em runtime podem não ser comprovados.

## Diferença entre controller e cliente de integração

- **Controller (INBOUND):** indica o JSON que seu endpoint espera receber e o tipo que declara retornar.
- **Feign (OUTBOUND):** indica o tipo da requisição declarada para envio à outra aplicação.
- **Não rastreia automaticamente uma transformação** `Controller → Service → Mapper → RestTemplate/WebClient → HTTP`. Validar com 100% de fidelidade os bytes da requisição real exige interceptar HTTP em execução e/ou inspecionar os mapeamentos customizados.

## Precisão e limites conhecidos

- **Tempo real:** usa alterações estruturais e de conteúdo expostas pelo PSI, com *debounce* de ~350 ms e reanálise em background. O resultado depende do commit do documento e do índice Java disponível. Não há garantia de atualização em cada tecla enquanto o código está sintaticamente incompleto.
- A busca é uma **heurística local** baseada em método, rota e body declarado; a pontuação não prova o fluxo de execução nem associa automaticamente controller → service → mapper → Feign.
- O body da collection/cURL é um exemplo concreto, não um schema formal; campos ausentes não significam necessariamente incompatibilidade.
- Exemplos de campo não refletem dados reais. Não executa código, chama APIs ou lê valores do banco.
- Suporte a `@JsonView`, `@JsonUnwrapped`, getters computados, `@JsonTypeInfo`, `@JsonValue`, `@JsonCreator`, custom `ObjectMapper`, validações condicionais e generics complexos é parcial ou ausente; a interface exibe avisos para alguns desses casos.
- Enums com serialização especial precisam ser verificados manualmente.
- Um body de exemplo não especifica formalmente campos obrigatórios, limites ou enums; a busca usa sua estrutura apenas como sinal de similaridade.
- A busca de collection aceita Postman v2.1 e Insomnia JSON; formatos/versões não reconhecidos precisam ser exportados novamente ou fornecidos como cURL.
- Não analisa automaticamente clientes REST/HTTP que não sejam Feign (RestTemplate, WebClient, Java HttpClient), nem contratos Protobuf/Avro/GraphQL, nem controllers Kotlin.
- Similaridade estática ≠ conformidade integral em runtime. Campos dinâmicos, headers, autenticação, transformação de payloads, valores e constraints precisam de validação adicional.
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
