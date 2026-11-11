# Laboratório do transporte CPI

Este laboratório executa o procedimento do [`IMPLEMENTACAO-CPI.md`](../IMPLEMENTACAO-CPI.md) de ponta a ponta, fora do tenant, do jeito mais próximo do real que dá para chegar sem um tenant SAP e sem um ServiceNow.

**O que ele é:** os cinco iFlows montados sobre o Apache Camel 3.14 (o motor do SAP Cloud Integration), executando os arquivos de [`../scripts/`](../scripts/) sem nenhuma alteração, e fazendo chamadas HTTP de verdade contra simuladores de ServiceNow, XSUAA, cTMS e CPI QAS.

**O que ele não é:** um tenant SAP. Os simuladores foram escritos a partir da documentação pública e do que o guia assume. Se a API real do cTMS devolver um campo com outro nome, ou o ServiceNow do banco tiver uma regra que eu não conheço, o laboratório não pega. Para isso existem o Passo 2, o Passo 8 e o Passo 11 do guia, no seu ambiente.

## Como rodar

Precisa de Java 11 ou mais novo, Maven, Python 3 com `pyjwt` e `cryptography`, e `openssl`.

```bash
cd cpi-transport-automation/cpi-iflows/lab
pip install pyjwt cryptography      # só na primeira vez
./run-lab.sh                        # 35 cenários x 64 combinações (cerca de 30 minutos)
./run-lab.sh extremos               # 8 combinações (cerca de 4 minutos)
./run-lab.sh padrao                 # 1 combinação, mostra cada falha (30 segundos)
./run-lab.sh padrao t13             # só os cenários com "t13" no nome
```

Saída esperada no fim:

```text
Cenários: 35 | combinações de comportamento do tenant: 64 | conferências: 17728
Combinações em que TODOS os cenários passaram: 64 de 64
```

O script baixa as bibliotecas, gera um par de chaves RSA e um certificado X.509 (como o Keystore do CPI faz), sobe os simuladores e roda os cenários. Nada sai da sua máquina.

## Como ele é montado

| Arquivo | Papel |
| --- | --- |
| [`runner/lab/IFlows.groovy`](runner/lab/IFlows.groovy) | Os cinco iFlows, passo a passo, copiados das tabelas do guia: Content Modifier, Script, Router, Request Reply (HTTP e ProcessDirect), Iterating Splitter, Exception Subprocess |
| [`runner/lab/Engine.groovy`](runner/lab/Engine.groovy) | Os tipos de passo do CPI sobre rotas do Camel: parâmetros externalizados, Allowed Header(s), Request Headers do adapter HTTP, Security Material, Keystore, monitor de mensagens |
| [`runner/lab/Scenarios.groovy`](runner/lab/Scenarios.groovy) | Os cenários e as conferências |
| [`runner/com/sap/.../Message.groovy`](runner/com/sap/gateway/ip/core/customdev/util/Message.groovy) | A classe `Message` dos scripts, apoiada num Exchange de verdade do Camel |
| [`services/lab_services.py`](services/lab_services.py) | Os simuladores |

### O que os simuladores cobram

| Sistema | Regras reproduzidas |
| --- | --- |
| ServiceNow, token | Grant `jwt-bearer`; client ID e secret; JWT RS256 conferido com a chave pública do certificado cadastrado; `kid` do Verifier Map; `aud` e `iss` iguais ao client ID; `sub` é um usuário ativo; validade com folga de 300 s; `jti` repetido é recusado |
| ServiceNow, Table API | Token Bearer; role do usuário; `sysparm_query` com `=`, `IN` e `ORDERBY`; **condição com coluna inexistente é ignorada** e a tabela inteira volta; `sysparm_fields`, `sysparm_limit`; datas em UTC com `display_value=false`; no PATCH, **campo sem coluna ou sem permissão é ignorado e a resposta é 200**; work notes vão para o diário e voltam vazias; o Flow do item fecha o chamado em `SUCCESS` e encaminha em `FAILED` |
| XSUAA | Client credentials das service keys; token por serviço; roles (403 quando falta) |
| cTMS | Nós, fila por nó, import por ID com `namedUser`, recusa de request que não está importável, ação com `running` e depois `succeeded` ou `error`, status por palavra ou por código (`IN`, `RU`, `SU`) |
| CPI QAS | Deploy devolve 202; 404 se o iFlow não está no design-time; runtime devolve 404, depois `STARTING`, depois `STARTED` ou `ERROR`; mantém o deploy antigo visível até o novo subir; responde XML sem `Accept: application/json`; CSRF opcional |
| Content Agent | **API inventada**, só para exercitar a sequência de estados da Etapa B |

### As 64 combinações

Seis comportamentos do tenant não ficam claros na documentação pública. Em vez de apostar, o laboratório roda tudo nas duas hipóteses de cada um:

| Comportamento | Hipóteses |
| --- | --- |
| Propriedades do Exchange atravessam o ProcessDirect | sim / não |
| ProcessDirect repassa só os headers de Allowed Header(s) | sim / todos |
| Headers do iFlow chamado voltam para o chamador | sim / não |
| Adapter HTTP copia os headers da resposta para a mensagem | sim / não |
| Header `CamelHttpResponseCode` disponível depois da chamada | sim / não |
| Erro de um iFlow chamado chega embrulhado em outra exceção | sim / não |

## Os cenários

| Cenário | O que prova |
| --- | --- |
| t01, t02 | Passo 8: token com JWT assinado, e gravação da work note e de uma coluna `u_cpi_*` |
| t03 a t06 | `kid` errado, chave que não casa com o certificado, usuário do `sub` inexistente, alias ou credencial ausente: falha com mensagem clara |
| t07 | Coluna sem permissão de escrita: o teste de conexão acusa |
| t10, t11 | Desligado não faz nenhuma chamada; ligado sem chamados só pede token e busca (com o filtro inteiro e em UTC) |
| t12 | Campos inválidos: `FAILED` sem tocar no cTMS |
| t13 | Caminho feliz completo, com a ordem dos estados, uma work note e uma gravação por mudança, request importada, iFlow `STARTED` na versão pedida |
| t14 a t16 | Sem clique (prazo), versão diferente, iFlow que não sobe |
| t17, t18 | Request de outro dev e request com número parecido ficam na fila; duas requests do mesmo chamado dão `FAILED` |
| t19, t20 | Erro passageiro: conta tentativas e segue; na 5ª vira `FAILED` |
| t21 a t24 | Import com erro, credencial errada, falta de role de deploy, CSRF exigido |
| t25 | ServiceNow fora do ar logo depois de disparar o import: o chamado se recupera sozinho |
| t26, t27 | Falha na busca ou no token: ciclo falha no monitor e o seguinte segue normal |
| t28 | Vários chamados no ciclo, um com problema: os outros avançam |
| t29, t30 | Colunas não criadas ou estado sem permissão de escrita: falha visível, sem laço |
| t31 | Mesma versão já estava em QAS: o sucesso só vale para o deploy deste chamado |
| t32 | Status do cTMS por código |
| t33 | iFlow auxiliar não implantado: erro cita o Address |
| t34 | Dois ciclos do Timer ao mesmo tempo |
| t35 | Token do ServiceNow recusado no meio do ciclo |
| t36 | Limite de chamados por ciclo |
| t40 | Etapa B com a API inventada |

Em todos os cenários também se confere: o header `X-Trp-Snow-Token` nunca sai do CPI, o token do ServiceNow nunca vai para outro sistema, e nenhum token ou segredo aparece no chamado ou no monitor.

## O que ele achou

Na primeira execução, com os scripts da versão anterior do guia, o caminho feliz passou e 6 cenários falharam. Os seis estão descritos na seção 14.1 do guia e corrigidos nos scripts. Depois das correções: 64 de 64.

## Como chegar a um teste de verdade

O que falta só existe no ambiente real. Em ordem de esforço:

1. **Passo 2 do guia** (seis testes em PowerShell contra o cTMS e o CPI QAS). Confirma nomes de campos e status. Se algo diferir, ajuste o simulador e os parâmetros do bloco B, e rode o laboratório de novo.
2. **Passo 8** (`IF_TRP_99`) no CPI de orquestração, contra o ServiceNow de desenvolvimento.
3. **Passo 11**, os oito testes ponta a ponta.

Para um laboratório real fora do ambiente do banco: uma conta trial do SAP BTP (Integration Suite e Cloud Transport Management) e uma instância de desenvolvedor do ServiceNow, ambas gratuitas. A criação das contas e o login são seus.
