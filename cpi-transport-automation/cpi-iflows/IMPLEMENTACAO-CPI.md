# Implementação no CPI — Transporte automatizado de iFlows

**Guia de construção passo a passo · versão 2.0 · 10 de novembro de 2026**

Este guia implementa, dentro do SAP Integration Suite (CPI), a arquitetura descrita em [`../ARQUITETURA-TRANSPORTE-CPI.md`](../ARQUITETURA-TRANSPORTE-CPI.md): o desenvolvedor abre um chamado no ServiceNow, o CPI leva o iFlow pela rota do cTMS, faz o deploy em QAS e devolve o resultado ao chamado. O próprio ServiceNow fecha o chamado.

Siga os passos na ordem. Cada passo diz o que criar, onde clicar, o que preencher e como conferir antes de ir para o próximo.

> **O que mudou na versão 2.0 (revisão completa da 1.0)**
>
> - **Roteiro em duas etapas.** Etapa A funciona **hoje, com a sua rota de transporte atual** (o dev ainda clica em Transport). Etapa B liga o export automático depois da migração para o Content Agent.
> - **iFlows mais simples de montar.** O script decide a rota e os roteadores só comparam um valor. Saíram os subprocessos locais e 6 passos. A lista de parâmetros obrigatórios caiu para poucos itens; o resto tem padrão no script.
> - **Correções nos scripts.** Troca da API `getApi` (descontinuada pela SAP) por `getService`; a checagem do deploy agora confere a **data do deploy** e avisa quando a versão que subiu é diferente da pedida; os status do cTMS aceitam palavra ou código curto; a busca no ServiceNow é montada e codificada pelo script.
> - **Passos novos:** folha de valores, testes das APIs antes de construir, pedido pronto para o time do ServiceNow, iFlow de teste de conexão, e uma tabela de problemas comuns.
> - O procedimento foi executado num laboratório ([`lab/`](lab/README.md)): os cinco iFlows montados sobre o Apache Camel 3.14 (o motor do Cloud Integration), com os scripts reais, falando HTTP com simuladores de ServiceNow, cTMS e CPI QAS. São 35 cenários em 64 combinações de comportamento do tenant, mais 100 verificações unitárias dos scripts (Groovy 2.4 e 4). O laboratório achou 6 pontos fracos, já corrigidos nesta versão (seção 14.1).
> - **Os iFlows ainda não rodaram em nenhum tenant SAP nem contra um ServiceNow de verdade**: a primeira execução real é a sua. O laboratório diminui o risco, não substitui esse teste.

---

## Sumário

1. [Visão geral](#1-visão-geral)
2. [Roteiro de execução](#2-roteiro-de-execução)
3. [Folha de valores](#3-folha-de-valores)
4. [Passo 1 — BTP: service keys](#4-passo-1--btp-service-keys)
5. [Passo 2 — Testar as APIs antes de construir](#5-passo-2--testar-as-apis-antes-de-construir)
6. [Passo 3 — ServiceNow](#6-passo-3--servicenow)
7. [Passo 4 — CPI: Keystore e Security Material](#7-passo-4--cpi-keystore-e-security-material)
8. [Passo 5 — CPI: pacote e noções do editor](#8-passo-5--cpi-pacote-e-noções-do-editor)
9. [Passo 6 — IF_TRP_04_SnowUpdate](#9-passo-6--if_trp_04_snowupdate)
10. [Passo 7 — IF_TRP_03_SnowToken](#10-passo-7--if_trp_03_snowtoken)
11. [Passo 8 — IF_TRP_99_ConnectionTest](#11-passo-8--if_trp_99_connectiontest)
12. [Passo 9 — IF_TRP_02_AdvanceTicket](#12-passo-9--if_trp_02_advanceticket)
13. [Passo 10 — IF_TRP_01_Orchestrator](#13-passo-10--if_trp_01_orchestrator)
14. [Passo 11 — Testes ponta a ponta](#14-passo-11--testes-ponta-a-ponta)
15. [Etapa B — Ligar o export por API](#15-etapa-b--ligar-o-export-por-api)
16. [Problemas comuns e soluções](#16-problemas-comuns-e-soluções)
17. [Operação](#17-operação)
18. [Pontos a confirmar](#18-pontos-a-confirmar)
19. [Referência de parâmetros](#19-referência-de-parâmetros)
20. [Anexo — scripts Groovy completos](#20-anexo--scripts-groovy-completos)

---

## 1. Visão geral

### 1.1 O que muda e o que não muda

| Pergunta | Resposta |
| --- | --- |
| O Automation Pilot continua? | **Não, no caminho principal.** O CPI assume a orquestração. O catálogo `cpitrans` fica como referência |
| A rota de transporte continua? | **Sim.** O cTMS continua levando o conteúdo de DEV para QAS |
| Precisa de API Management? | **Não.** O CPI só chama APIs de outros sistemas; ninguém de fora chama o CPI |
| Quem fecha o chamado? | **O ServiceNow**, quando o CPI grava `SUCCESS` ou `FAILED` |
| Como o CPI fala com o ServiceNow? | OAuth 2.0 com **JWT assinado por certificado** (grant `jwt-bearer`), um dos estilos do Hub de Arquitetura |
| Quem chama quem? | Só o CPI chama o ServiceNow. O ServiceNow não chama o CPI |

### 1.2 Como funciona

A cada 2 minutos o CPI pergunta ao ServiceNow quais chamados de transporte estão em andamento. Para cada um, ele dá **um passo** e grava o novo estado no próprio chamado. Nada fica esperando dentro do CPI.

```text
Estado no chamado      O que o CPI faz nesse ciclo
---------------------  ------------------------------------------------------------
PENDING                Etapa A: pede ao dev o clique em Transport  -> WAITING_TR
                       Etapa B: cria o export no Content Agent     -> EXPORTING
WAITING_TR (Etapa A)   procura na fila do cTMS a request com o nº do chamado;
                       achou: importa SÓ essa request              -> IMPORTING
EXPORTING  (Etapa B)   export pronto? importa SÓ essa request      -> IMPORTING
IMPORTING              import terminou? dispara o deploy em QAS    -> DEPLOYING
DEPLOYING              iFlow STARTED, deploy novo, versão certa?   -> SUCCESS
qualquer estado        erro definitivo, prazo vencido              -> FAILED
```

### 1.3 Os iFlows

| iFlow | Gatilho | Função |
| --- | --- | --- |
| `IF_TRP_01_Orchestrator` | Timer (a cada 2 min) | Pega o token, lista os chamados em andamento e manda um a um para o `IF_TRP_02` |
| `IF_TRP_02_AdvanceTicket` | ProcessDirect `/trp/advance` | Dá um passo no chamado: fila e import no cTMS, deploy e checagem no CPI QAS |
| `IF_TRP_03_SnowToken` | ProcessDirect `/trp/snow-token` | Assina o JWT com a chave do Keystore e troca pelo token do ServiceNow |
| `IF_TRP_04_SnowUpdate` | ProcessDirect `/trp/snow-update` | Atualiza o chamado (estado, IDs, notas) |
| `IF_TRP_99_ConnectionTest` | Timer (uma vez, no deploy) | Temporário: testa o token e a gravação no ServiceNow |

Regras que valem para todos:

- **Propriedades não passam de um iFlow para outro pelo ProcessDirect; headers passam.** Por isso o token do ServiceNow vai no header `X-Trp-Snow-Token`, e cada iFlow o guarda numa propriedade e esvazia o header logo no primeiro passo.
- **Cada chamada HTTP envia só os headers listados** no campo *Request Headers*. Nunca use `*`, para o token não vazar para outro sistema.
- **Log em nível Info.** Use Trace só durante um teste e desligue em seguida: o trace grava corpos que contêm tokens.

---

## 2. Roteiro de execução

### 2.1 Duas etapas

| | Etapa A — agora | Etapa B — depois |
| --- | --- | --- |
| Rota de transporte | A **atual** (modo Transport Management Service) | Content Agent + cTMS (ver [`../MIGRACAO-CPI.md`](../MIGRACAO-CPI.md)) |
| Quem gera a request | O dev clica em **Transport** e escreve o nº do chamado na descrição | O CPI, pela API de export do Content Agent |
| Parâmetro | `EXPORT_MODE = MANUAL_TR` | `EXPORT_MODE = API` |
| O que o CPI automatiza | Achar a request do chamado, importar só ela, deploy, checagem e retorno ao chamado | Tudo, sem clique |
| Depende de | Nada além deste guia | Migração concluída e API de export confirmada (seção 18) |

Faça a Etapa A inteira primeiro. Ela já entrega valor, valida toda a integração com o ServiceNow e o cTMS, e a Etapa B só acrescenta duas rotas num iFlow.

### 2.2 Checklist geral

- [ ] **Passo 1** — 2 service keys criadas no BTP (cTMS e deploy em QAS)
- [ ] **Passo 2** — 6 testes de API feitos; respostas anotadas
- [ ] **Passo 3** — ServiceNow: colunas, item de catálogo, flow, cliente OAuth e usuário de integração
- [ ] **Passo 4** — CPI: par de chaves do JWT criado; certificado entregue ao ServiceNow; 3 credenciais criadas
- [ ] **Passo 5** — Pacote criado
- [ ] **Passo 6** — `IF_TRP_04_SnowUpdate` no ar
- [ ] **Passo 7** — `IF_TRP_03_SnowToken` no ar
- [ ] **Passo 8** — Teste de conexão passou (token e work note)
- [ ] **Passo 9** — `IF_TRP_02_AdvanceTicket` no ar
- [ ] **Passo 10** — `IF_TRP_01_Orchestrator` no ar, ainda desligado
- [ ] **Passo 11** — Testes ponta a ponta aprovados; orquestrador ligado
- [ ] **Etapa B** — depois da migração

### 2.3 Quem faz o quê

| Atividade | Quem |
| --- | --- |
| Passos 1, 2, 4 a 11 | Você (BTP e CPI) |
| Passo 3 | Time do ServiceNow, a partir do pedido pronto da seção 6.1. Você entrega o certificado e recebe de volta 5 valores |
| Decisão sobre onde fica a chave do JWT (Keystore do CPI ou HSM) | Segurança da Informação. Este guia usa o Keystore do CPI |

---

## 3. Folha de valores

Preencha esta folha conforme avança. Guarde os segredos num cofre, nunca em e-mail, chamado ou repositório.

| # | Valor | De onde vem | Onde é usado | Seu valor |
| --- | --- | --- | --- | --- |
| 1 | `uri` do cTMS | Service key `key-tms-trp` (Passo 1) | Parâmetro `CTMS_URI` | |
| 2 | Token URL do cTMS | `uaa.url` da mesma key + `/oauth/token` | Credencial `TRP_CTMS` | |
| 3 | Client ID e secret do cTMS | `uaa.clientid` / `uaa.clientsecret` | Credencial `TRP_CTMS` | (cofre) |
| 4 | ID numérico do nó de QAS | Teste 1 do Passo 2 | Parâmetro `CTMS_QAS_NODE_ID` | |
| 5 | `url` da API do CPI QAS | Service key `key-pi-api-qas-deploy` | Parâmetro `CPI_QAS_URL` | |
| 6 | `tokenurl` do CPI QAS | Mesma key | Credencial `TRP_CPI_QAS` | |
| 7 | Client ID e secret do CPI QAS | Mesma key | Credencial `TRP_CPI_QAS` | (cofre) |
| 8 | Host do ServiceNow | Time do ServiceNow | Parâmetro `SNOW_HOST` (ex.: `bradesco.service-now.com`) | |
| 9 | URL do token do ServiceNow | Time do ServiceNow | Parâmetro `SNOW_TOKEN_URL` | |
| 10 | Client ID e secret do cliente OAuth | Time do ServiceNow | Credencial `TRP_SNOW_CLIENT` | (cofre) |
| 11 | Key ID (`kid`) | Combinado com o time do ServiceNow (ex.: `trp-cpi-2026`) | Parâmetro `SNOW_JWT_KID` | |
| 12 | Usuário de integração | Time do ServiceNow (ex.: `svc_cpi_transport`) | Parâmetro `SNOW_JWT_SUB` | |
| 13 | sys_id do item de catálogo | Time do ServiceNow (seção 6.3) | Parâmetro `SNOW_CAT_ITEM_SYSID` | |
| 14 | sys_id de um chamado de teste | Um chamado aberto por você | Parâmetro `TEST_SYS_ID` (Passo 8) | |
| 15 | iFlow, versão e pacote de teste | Um iFlow sem efeito colateral em DEV | Testes do Passo 11 | |

---

## 4. Passo 1 — BTP: service keys

Crie keys **novas**, só para o orquestrador. Não reaproveite as keys que as destinations usam: assim você pode trocar ou revogar uma sem derrubar o transporte.

### 4.1 Key do cTMS

1. BTP Cockpit → subaccount **TMS** → **Services → Instances and Subscriptions**.
2. Em **Instances**, abra a instância do Cloud Transport Management (`CTM_Service_Key`, plano `standard`).
3. **Service Keys → Create**. Nome: `key-tms-trp`. Sem parâmetros. **Create**.
4. Abra a key (**View**) e copie para a folha de valores: `uri`, `uaa.url`, `uaa.clientid`, `uaa.clientsecret`.
5. O token URL é `uaa.url` + `/oauth/token`.

### 4.2 Instância e key de deploy em QAS

1. Subaccount **QAS** → **Instances and Subscriptions → Create**.
2. Service: **Process Integration Runtime**. Plan: **api**. Instance Name: `pi-api-qas-deploy`. **Next**.
3. Em **Parameters**, mude para a visão JSON e cole:

```json
{
  "grant-types": ["client_credentials"],
  "roles": [
    "WorkspacePackagesRead",
    "WorkspaceArtifactsDeploy",
    "MonitoringDataRead"
  ]
}
```

4. **Create**. Quando a instância estiver pronta: **Service Keys → Create** → `key-pi-api-qas-deploy`.
5. Copie para a folha de valores: `url`, `tokenurl`, `clientid`, `clientsecret` (ficam dentro de `oauth`).

Para que serve cada role: `WorkspaceArtifactsDeploy` faz o deploy; `MonitoringDataRead` lê o status em runtime; `WorkspacePackagesRead` lê o iFlow no workspace. Se a tela de criação mostrar nomes diferentes, use os equivalentes que aparecerem.

**Conferência:** você tem duas keys abertas e 7 valores anotados (linhas 1 a 3 e 5 a 7 da folha).

---

## 5. Passo 2 — Testar as APIs antes de construir

Antes de montar qualquer iFlow, chame as APIs à mão. Isso confirma as credenciais e mostra o formato real das respostas, que é o que os parâmetros do `IF_TRP_02` precisam refletir. Os caminhos da API do cTMS usados aqui são os da versão 2; confira na referência (api.sap.com, pacote Cloud Transport Management) se algum responder 404.

No Windows, abra o **PowerShell** e cole o bloco de preparação (troque os valores):

```powershell
function Get-BtpToken($tokenUrl, $clientId, $secret) {
  $basic = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("${clientId}:${secret}"))
  (Invoke-RestMethod -Method Post -Uri $tokenUrl `
     -Headers @{Authorization = "Basic $basic"} `
     -Body "grant_type=client_credentials" `
     -ContentType "application/x-www-form-urlencoded").access_token
}

# cTMS (key-tms-trp)
$tms      = "<uri>"
$tmsToken = Get-BtpToken "<uaa.url>/oauth/token" "<uaa.clientid>" "<uaa.clientsecret>"
$tmsH     = @{Authorization = "Bearer $tmsToken"; Accept = "application/json"}

# CPI QAS (key-pi-api-qas-deploy)
$cpi      = "<url>"
$cpiToken = Get-BtpToken "<tokenurl>" "<clientid>" "<clientsecret>"
$cpiH     = @{Authorization = "Bearer $cpiToken"; Accept = "application/json"}
```

### Teste 1 — Listar os nós do cTMS

```powershell
Invoke-RestMethod -Uri "$tms/v2/nodes" -Headers $tmsH | ConvertTo-Json -Depth 5
```

Ache o nó de QAS da sua rota e anote o `id` numérico (linha 4 da folha).

```powershell
$node = "<id do nó de QAS>"
```

### Teste 2 — Ver a fila do nó de QAS

Antes, clique em **Transport** num pacote de teste no CPI DEV, com a descrição `TESTE-API-01`.

```powershell
Invoke-RestMethod -Uri "$tms/v2/nodes/$node/transportRequests" -Headers $tmsH | ConvertTo-Json -Depth 6
```

Anote, olhando a resposta:

- a lista vem dentro de `transportRequests`, ou a resposta já é a lista?
- cada request tem `id`, `description` e `status`?
- qual é o valor de `status` da request que acabou de chegar (por exemplo `initial` ou `in`)?
- a lista traz também requests antigas, já importadas?

### Teste 3 — Importar só uma request

```powershell
$trId = <id da request TESTE-API-01>
$body = @{ namedUser = "teste-manual"; transportRequests = @($trId) } | ConvertTo-Json
$imp  = Invoke-RestMethod -Method Post -Uri "$tms/v2/nodes/$node/transportRequests/import" `
          -Headers $tmsH -Body $body -ContentType "application/json"
$imp | ConvertTo-Json -Depth 5
```

Anote o nome do campo que traz o ID da ação (esperado: `actionId`).

### Teste 4 — Acompanhar a ação de import

```powershell
Invoke-RestMethod -Uri "$tms/v2/actions/$($imp.actionId)" -Headers $tmsH | ConvertTo-Json -Depth 6
```

Repita até terminar. Anote o campo de status e os valores que aparecem **durante** e **no fim** (por exemplo `running` e `succeeded`, ou códigos curtos como `ru` e `su`). Confira no CPI QAS que o pacote chegou no workspace (**Design**).

### Teste 5 — Ler o status de um iFlow em runtime no QAS

Use o ID técnico de um iFlow que já está implantado em QAS:

```powershell
$iflow = "<ID técnico>"
Invoke-RestMethod -Uri "$cpi/api/v1/IntegrationRuntimeArtifacts('$iflow')" -Headers $cpiH | ConvertTo-Json -Depth 5
```

Esperado: um objeto `d` com `Status` (`STARTED`), `Version` e `DeployedOn`.

### Teste 6 — Deploy por API

Use o iFlow de teste que chegou no Teste 4:

```powershell
$iflow = "<ID técnico do iFlow de teste>"
Invoke-WebRequest -UseBasicParsing -Method Post -Headers $cpiH `
  -Uri "$cpi/api/v1/DeployIntegrationDesigntimeArtifact?Id='$iflow'&Version='active'" |
  Select-Object StatusCode, Content
```

Esperado: `StatusCode` 202. Depois repita o Teste 5 com esse iFlow até `Status` = `STARTED`.

### O que fazer com o que você anotou

| Se você viu | Então |
| --- | --- |
| Tudo como esperado (`transportRequests`, `id`, `description`, `actionId`, `status` com `succeeded` ou `su`) | Não precisa de nenhum parâmetro opcional. O script já usa esses padrões |
| A fila com outro caminho | Crie o parâmetro `CTMS_QUEUE_PATH` com o caminho certo (use `#NODE_ID#` no lugar do ID) |
| O ID da ação em outro campo | Parâmetro `CTMS_ACTIONID_JSONPATH` (ex.: `action.id`) |
| O status da ação em outro campo ou com outros valores | Parâmetros `CTMS_ACTION_STATUS_JSONPATH`, `CTMS_ACTION_OK_VALUES`, `CTMS_ACTION_FAILED_VALUES` |
| O status de request "importável" diferente de `initial`, `in`, `repeatable`, `re` | Parâmetro `CTMS_IMPORTABLE_VALUES` |
| Erro 403 no Teste 6 falando de CSRF | Veja a linha "CSRF" na seção 16 |
| Erro 401 | Token URL ou secret errado, ou credencial de outro ambiente |
| Erro 403 | cTMS: key de instância que não é plano `standard`. CPI: falta role na instância `pi-api-qas-deploy` |

---

## 6. Passo 3 — ServiceNow

### 6.1 Pedido pronto para o time do ServiceNow

Envie este pedido ao time do ServiceNow, junto com o certificado público gerado na seção 7.1.

> **Pedido: integração "Transporte CPI DEV → QAS"**
>
> 1. **Cliente OAuth de entrada** no estilo *OAuth 2.0 com JWT assinado por certificado* (grant `jwt-bearer`), para o cliente "SAP CPI – Transporte". Cadastrar o certificado anexo e um JWT Verifier Map com Key ID `trp-cpi-2026`. *User Field* = User ID (`user_name`).
> 2. **Usuário de integração** `svc_cpi_transport` (Web service access only), com permissão para **ler** registros de `sc_req_item` do item de catálogo abaixo e **gravar** somente nas colunas `u_cpi_*` e em `work_notes`.
> 3. **Colunas novas** em `sc_req_item` (lista na seção 6.2).
> 4. **Item de catálogo** "Transporte CPI DEV → QAS" com as variáveis da seção 6.3.
> 5. **Flow** do item (seção 6.4): copia as variáveis para as colunas, grava `PENDING`, espera o resultado e fecha o chamado.
>
> **Preciso de volta:** host da instância, URL do token, client ID e client secret, confirmação do Key ID e do usuário, e o sys_id do item de catálogo.

O restante desta seção detalha cada item, para o time do ServiceNow ou para você acompanhar.

### 6.2 Colunas novas em `sc_req_item`

**System Definition → Tables →** abra `Requested Item [sc_req_item]` **→ Columns → New** (uma por linha):

| Column label | Column name | Type | Tamanho / valores |
| --- | --- | --- | --- |
| CPI artifact ID | `u_cpi_artifact_id` | String | 100 |
| CPI artifact version | `u_cpi_artifact_version` | String | 20 |
| CPI package ID | `u_cpi_package_id` | String | 100 |
| CPI transport status | `u_cpi_transport_status` | Choice | `PENDING`, `WAITING_TR`, `EXPORTING`, `IMPORTING`, `DEPLOYING`, `SUCCESS`, `FAILED` (valor = rótulo, em maiúsculas) |
| CPI stage since | `u_cpi_stage_since` | Date/Time | — |
| CPI retries | `u_cpi_retries` | Integer | padrão 0 |
| CPI operation ID | `u_cpi_operation_id` | String | 100 |
| CPI transport request | `u_cpi_tr_id` | String | 40 |
| CPI action ID | `u_cpi_action_id` | String | 40 |
| CPI message | `u_cpi_message` | String | 255 |

Os nomes precisam ser exatamente esses. A Table API **ignora em silêncio** campos com nome errado: o chamado viria com os campos vazios e falharia na validação.

Por que colunas, e não só as variáveis do item: a Table API não devolve variáveis de catálogo como campos do registro. O flow copia os valores para as colunas, e o CPI lê as colunas.

### 6.3 Item de catálogo

**Service Catalog → Catalog Definitions → Maintain Items → New**. Nome: `Transporte CPI DEV → QAS`. Depois, na lista **Variables → New**:

| Question | Name | Type | Obrigatória | Validação (regex) |
| --- | --- | --- | --- | --- |
| ID técnico do iFlow | `u_artifact_id` | Single Line Text | Sim | `^[A-Za-z0-9_.-]+$` |
| Versão em DEV | `u_artifact_version` | Single Line Text | Sim | `^\d+\.\d+\.\d+$` |
| ID técnico do pacote | `u_package_id` | Single Line Text | Sim | `^[A-Za-z0-9_.-]+$` |
| Ambiente de destino | `u_target_env` | Select Box (só `QAS`), read-only | Sim | — |
| Justificativa | `u_justification` | Multi Line Text | Sim | — |
| Mudança relacionada | `u_change_reference` | Single Line Text | Não | — |

Texto de ajuda sugerido para o dev, na Etapa A:

> 1. Salve a versão do iFlow no CPI DEV (**Save as version**).
> 2. Abra este chamado e anote o número (RITM…).
> 3. No pacote do CPI DEV, clique em **Transport** e escreva o número do chamado na descrição.
> 4. Acompanhe o status aqui. Não importe nada à mão no cTMS.

**sys_id do item (linha 13 da folha):** abra o item, clique com o botão direito na barra de título do formulário → **Copy sys_id**.

### 6.4 Flow do item

**Flow Designer → New → Flow**. Nome: `Transporte CPI DEV → QAS`. Run As: **System User**. Depois de pronto, selecione este flow no campo **Flow** (aba Process Engine) do item de catálogo.

| # | Passo | Configuração |
| --- | --- | --- |
| Trigger | **Service Catalog** | — |
| 1 | **Get Catalog Variables** | Submitted Request = Trigger → Requested Item Record; Template Catalog Item = o item da 6.3; selecione as variáveis |
| 2 | **Update Record** | Record = Trigger → Requested Item Record. Campos: `u_cpi_artifact_id` = variável `u_artifact_id`; `u_cpi_artifact_version` = `u_artifact_version`; `u_cpi_package_id` = `u_package_id`; `u_cpi_transport_status` = `PENDING`; `u_cpi_retries` = 0 |
| 3 | **Wait For Condition** | Record = Requested Item; condição: `CPI transport status` **is one of** `SUCCESS`, `FAILED`. Timeout opcional: 3 horas |
| 4 | **If** `CPI transport status` is `SUCCESS` | **Update Record**: State = Closed Complete |
| 5 | **Else** | **Update Record**: Assignment group = grupo de suporte de integração; work notes "Transporte falhou. Ver campo CPI message." Opcional: **Send Notification** ao solicitante |
| — | **Activate** | Em rascunho o flow não dispara |

O flow não faz nenhuma chamada REST. Ele só prepara o chamado, espera o CPI gravar o resultado e fecha.

Se o processo do banco preferir uma **business rule** no lugar dos passos 3 a 5: *before update* em `sc_req_item`, condição `CPI transport status` *changes*, com o script:

```javascript
(function executeRule(current, previous) {
    if (current.u_cpi_transport_status == 'SUCCESS') {
        current.state = 3; // Closed Complete (confira o valor na sua instância)
    } else if (current.u_cpi_transport_status == 'FAILED') {
        current.assignment_group.setDisplayValue('<grupo de suporte de integração>');
    }
})(current, previous);
```

### 6.5 Cliente OAuth com JWT

Quem faz precisa da role `oauth_admin`.

1. **Certificado.** Navegue para `sys_certificate.list` → **New**. Name: `SAP CPI Transporte`. Format: PEM. Cole o conteúdo do certificado público gerado no CPI (seção 7.1). Salve.
2. **Endpoint.** **System OAuth → Application Registry → New → Create an OAuth JWT API endpoint for external clients**.

| Campo | Valor |
| --- | --- |
| Name | `SAP CPI - Transporte` |
| Client ID | Gerado sozinho. Anote |
| Client Secret | Deixe em branco para gerar. Anote depois de salvar |
| User Field | **User ID** (`user_name`) |
| Enable JTI Verification | Marcado (o CPI manda um `jti` novo a cada pedido) |
| Clock Skew | 300 (padrão) |
| Access Token Lifespan | Padrão da instância |

3. **JWT Verifier Maps** (lista relacionada no fim do formulário) → **New**. Name: `cpi-transporte`. Kid: `trp-cpi-2026`. Sys certificate: o certificado do item 1. Salve.

Como o ServiceNow valida o JWT do CPI:

| Campo do JWT | Valor que o CPI manda | O que o ServiceNow confere |
| --- | --- | --- |
| `kid` (cabeçalho) | `SNOW_JWT_KID` | Acha o Verifier Map e o certificado |
| `aud` e `iss` | O client ID | Precisam ser iguais ao Client ID do endpoint |
| `sub` | `SNOW_JWT_SUB` | Acha o usuário pelo *User Field* |
| `exp` | Agora + 5 min | Não vencido (com o Clock Skew) |
| `jti` | Um identificador novo por pedido | Não repetido |
| Assinatura | RS256 com a chave do Keystore do CPI | Confere com o certificado |

### 6.6 Usuário de integração

1. **User Administration → Users → New**. User ID: `svc_cpi_transport`. Marque **Web service access only**. Ativo.
2. Permissões (o time do ServiceNow decide a forma, por role e ACL):
   - **ler** `sc_req_item`, pelo menos os registros do item de catálogo do transporte, incluindo os campos `cat_item`, `number`, `sys_id` e os `u_cpi_*`;
   - **gravar** somente nos campos `u_cpi_*` e em `work_notes`;
   - acesso à Table API (algumas instâncias exigem uma role própria para REST, como `snc_platform_rest_api_access`).
3. A Table API só devolve os registros que o usuário pode ler. Se a busca voltar vazia com chamados abertos, o motivo quase sempre é ACL.

**Conferência do Passo 3:** linhas 8 a 13 da folha preenchidas; um chamado de teste aberto pelo portal aparece com `CPI transport status = PENDING` e as três colunas `u_cpi_artifact_id`, `u_cpi_artifact_version` e `u_cpi_package_id` preenchidas. Copie o sys_id desse chamado (linha 14).

---

## 7. Passo 4 — CPI: Keystore e Security Material

Tudo no tenant de orquestração (nesta fase, o **CPI de QAS**), em **Monitor → Integrations and APIs → Manage Security**.

### 7.1 Par de chaves do JWT

A chave é gerada dentro do CPI. A chave privada nunca sai do tenant.

1. Tile **Keystore** → **Create → Key Pair**.
2. Preencha:

| Campo | Valor |
| --- | --- |
| Alias | `trp_snow_jwt` |
| Key Type | RSA |
| Key Size | 2048 |
| Signature Algorithm | SHA-256/RSA |
| Common Name (CN) | `cpi-transporte-servicenow` |
| Organization, Country etc. | Conforme o padrão do banco |
| Valid Until | Conforme a política do banco (ex.: 1 ano) |

3. **Create**. A entrada aparece com o tipo **Key Pair**.
4. Selecione a entrada → **Download → Certificate** (arquivo `.cer`).
5. Entregue o `.cer` ao time do ServiceNow (seção 6.5, item 1). Se pedirem em PEM e o arquivo vier binário, converta com `openssl x509 -inform der -in trp_snow_jwt.cer -out trp_snow_jwt.pem`.
6. Anote a data de validade num calendário, com alerta 30 dias antes.

### 7.2 Credenciais

Tile **Security Material** → **Create**.

**`TRP_SNOW_CLIENT`** — tipo **User Credentials**

| Campo | Valor |
| --- | --- |
| Name | `TRP_SNOW_CLIENT` |
| Type | User Credentials |
| User | Client ID do cliente OAuth do ServiceNow |
| Password | Client secret |

**`TRP_CTMS`** — tipo **OAuth2 Client Credentials**

| Campo | Valor |
| --- | --- |
| Name | `TRP_CTMS` |
| Token Service URL | `uaa.url` + `/oauth/token` (linha 2 da folha) |
| Client ID / Client Secret | Da `key-tms-trp` |
| Client Authentication | Send as Request Header |
| Content Type | `application/x-www-form-urlencoded` |
| Scope | Vazio |

**`TRP_CPI_QAS`** — tipo **OAuth2 Client Credentials**, mesmo padrão, com o `tokenurl`, `clientid` e `clientsecret` da `key-pi-api-qas-deploy`.

Clique em **Deploy** em cada uma.

**Conferência:** no Keystore existe `trp_snow_jwt` (Key Pair); no Security Material existem `TRP_SNOW_CLIENT`, `TRP_CTMS` e `TRP_CPI_QAS` com status **Deployed**.

---

## 8. Passo 5 — CPI: pacote e noções do editor

### 8.1 Criar o pacote

1. **Design → Integrations and APIs → Create**.
2. Name: `Transporte CPI (Orquestração)`. Technical Name: `Z_TRANSPORTE_CPI`. Short description livre. **Save**.

### 8.2 Como fazer cada coisa no editor

Os nomes dos menus podem variar um pouco conforme a versão do tenant. O que não muda é o tipo de cada passo.

| Quero | Como fazer |
| --- | --- |
| Criar um iFlow | No pacote: **Edit → Artifacts → Add → Integration Flow**. Preencha o **Name**; o ID é gerado. Abra o iFlow e clique em **Edit** |
| Remetente por ProcessDirect | Arraste a seta da caixa **Sender** até o evento **Start**. Na lista de adapters escolha **ProcessDirect**. Aba **Connection → Address** |
| Começar por Timer | Apague o **Start** e a caixa **Sender**. Na paleta: **Events → Timer**. Ligue o Timer ao primeiro passo |
| Adicionar um passo | Clique na paleta (ou no `+` que aparece ao selecionar um passo) e solte sobre a linha entre dois passos |
| Content Modifier | Paleta **Message Transformers → Content Modifier** |
| Groovy Script | Paleta **Message Transformers → Script → Groovy Script** |
| Router | Paleta **Message Routing → Router** |
| Iterating Splitter | Paleta **Message Routing → Splitter → Iterating Splitter** |
| Request Reply | Paleta **Call → External Call → Request Reply** |
| Receiver (destino de uma chamada) | Paleta **Participants → Receiver**. Solte fora da caixa do processo e dê um nome. Use **um Receiver para cada chamada** |
| Ligar uma chamada ao destino | Arraste a seta do passo **Request Reply** até a caixa do **Receiver** e escolha o adapter (**HTTP** ou **ProcessDirect**) |
| Exception Subprocess | Paleta **Process → Exception Subprocess**. Solte **dentro** da caixa Integration Process |
| Ver as propriedades de um passo | Clique no passo; as abas aparecem na parte de baixo da tela |
| Ver as propriedades do iFlow | Clique numa área vazia **fora** da caixa do processo (aba **Runtime Configuration**) |

### 8.3 Scripts

Cada iFlow usa **um** arquivo de script, com várias funções.

1. Adicione o primeiro passo **Groovy Script** do iFlow. O editor abre um script novo (`script1.groovy`).
2. Apague todo o conteúdo e cole o conteúdo do arquivo indicado (pasta [`scripts/`](scripts/) ou o anexo, seção 20). **OK**.
3. Clique no passo → aba **Processing** → campo **Script Function**: digite o nome da função desse passo (por exemplo `prepareUpdate`).
4. Nos **próximos** passos de script do mesmo iFlow: adicione o passo, feche o editor que abrir sem salvar, e na aba **Processing** use **Select** (ou **Assign**) em **Script File** para escolher o **mesmo** arquivo. Depois preencha a **Script Function**.

Atenção ao nome da função: é sensível a maiúsculas. Um nome errado só aparece como erro na execução.

### 8.4 Parâmetros externalizados

Onde este guia mostrar `{{NOME}}`, digite exatamente assim no campo, com as chaves duplas. O CPI transforma isso num parâmetro.

Para preencher os valores: salve o iFlow, volte ao pacote, e no iFlow use **Actions (…) → Configure**. Preencha e **Save**. Isso pode ser refeito a qualquer momento, sem editar o iFlow; depois é só **Deploy** de novo.

Regras:

- Não escreva `${...}` no **valor** de um parâmetro: o CPI trataria como expressão.
- Um parâmetro não pode ficar vazio em alguns campos. Quando o guia disser "opcional", use `-` para dizer "vazio". Os scripts entendem `-` como vazio.

### 8.5 Configuração comum a todos os iFlows

Em cada iFlow, clique fora da caixa do processo → aba **Runtime Configuration** → **Allowed Header(s)**: `X-Trp-Snow-Token`.

Em tese o ProcessDirect já repassa headers entre iFlows. Deixar esse header liberado evita o erro "Token do ServiceNow ausente" se o seu tenant filtrar.

### 8.6 Salvar, implantar e conferir

1. **Save** (canto superior direito). Corrija os erros que a checagem apontar.
2. **Deploy**.
3. **Monitor → Integrations and APIs → Manage Integration Content**: o iFlow precisa ficar em **Started**.
4. Nível de log: nessa mesma tela, selecione o iFlow → **Log Configuration → Log Level**.
5. Execuções: **Monitor → Monitor Message Processing**.

---

## 9. Passo 6 — IF_TRP_04_SnowUpdate

Atualiza o chamado. É o primeiro porque os outros dependem dele. É também o mais simples: use-o para pegar a mão do editor.

**Script:** [`scripts/TRP_04_SnowUpdate.groovy`](scripts/TRP_04_SnowUpdate.groovy)

### 9.1 Montagem

```text
Sender --ProcessDirect--> Start -> [1 Script] -> [2 Request Reply] -> [3 Script] -> End
                                                        |
                                                        +--HTTP--> Receiver "ServiceNow"
```

1. Crie o iFlow `IF_TRP_04_SnowUpdate` e clique em **Edit**.
2. Ligue **Sender → Start** com o adapter **ProcessDirect**. Address: `/trp/snow-update`.
3. Apague a caixa **Receiver** que veio no modelo, se ela estiver ligada ao End. Você vai criar o seu.
4. Adicione os passos da tabela, nesta ordem, entre o Start e o End.
5. Adicione um **Receiver** chamado `ServiceNow` e ligue o passo 2 a ele com o adapter **HTTP**.

| # | Nome do passo | Tipo | Configuração |
| --- | --- | --- | --- |
| 1 | Preparar atualização | Groovy Script | Script Function: `prepareUpdate` |
| 2 | Atualizar chamado | Request Reply → HTTP | Tabela 9.2 |
| 3 | Limpar | Groovy Script | Script Function: `finishUpdate` |

### 9.2 Adapter HTTP do passo 2 (aba Connection)

| Campo | Valor |
| --- | --- |
| Address | `https://{{SNOW_HOST}}/api/now/table/{{SNOW_TABLE}}/${property.SYS_ID}` |
| Query | Vazio |
| Proxy Type | Internet |
| Method | **PATCH** |
| Authentication | None |
| Timeout (in ms) | 60000 |
| Throw Exception on Failure | Marcado |
| Request Headers | `Authorization\|Content-Type\|Accept` |
| Response Headers | Deixe o valor padrão |

No campo, os nomes são separados por barra vertical, sem espaços: `Authorization|Content-Type|Accept`. Vale o mesmo para todas as tabelas de adapter deste guia.

Se **PATCH** não aparecer na lista de métodos do seu tenant, use **PUT**. Na Table API do ServiceNow os dois atualizam o registro; confirme no teste do Passo 8 que só os campos enviados mudaram.

### 9.3 Runtime, parâmetros e deploy

1. **Runtime Configuration → Allowed Header(s)**: `X-Trp-Snow-Token`.
2. **Save**. No pacote: **Configure**:

| Parâmetro | Valor |
| --- | --- |
| `SNOW_HOST` | Host do ServiceNow, sem `https://` (linha 8 da folha) |
| `SNOW_TABLE` | `sc_req_item` |

3. **Deploy** e confira **Started**.

O que o iFlow espera receber: o header `X-Trp-Snow-Token` e o corpo `{"sys_id":"...","fields":{...}}`. O teste real acontece no Passo 8.

---

## 10. Passo 7 — IF_TRP_03_SnowToken

Monta e assina o JWT e troca pelo token do ServiceNow.

**Script:** [`scripts/TRP_03_SnowToken.groovy`](scripts/TRP_03_SnowToken.groovy)

### 10.1 Montagem

```text
Sender --ProcessDirect--> Start -> [1 Content Modifier] -> [2 Script] -> [3 Request Reply] -> [4 Script] -> End
                                                                                |
                                                                                +--HTTP--> Receiver "ServiceNow_Token"
```

Sender: ProcessDirect, Address `/trp/snow-token`.

| # | Nome do passo | Tipo | Configuração |
| --- | --- | --- | --- |
| 1 | Config | Content Modifier | Aba **Exchange Property**, tabela 10.2 |
| 2 | Assinar JWT | Groovy Script | Script Function: `buildTokenRequest` |
| 3 | Pedir token | Request Reply → HTTP | Tabela 10.3 |
| 4 | Extrair token | Groovy Script | Script Function: `parseTokenResponse` |

### 10.2 Content Modifier "Config" (aba Exchange Property)

Para cada linha: **Add** → Action = Create, Name = a coluna Name, Source Type = **Constant**, Source Value = a coluna Value.

| Name | Source Value | Obrigatório |
| --- | --- | --- |
| `SNOW_CLIENT_CREDENTIAL` | `{{SNOW_CLIENT_CREDENTIAL}}` | Sim |
| `SNOW_JWT_KEY_ALIAS` | `{{SNOW_JWT_KEY_ALIAS}}` | Sim |
| `SNOW_JWT_KID` | `{{SNOW_JWT_KID}}` | Sim |
| `SNOW_JWT_SUB` | `{{SNOW_JWT_SUB}}` | Sim |
| `SNOW_ASSERTION_PARAM` | `{{SNOW_ASSERTION_PARAM}}` | Sim |
| `SNOW_JWT_AUD` | `{{SNOW_JWT_AUD}}` | Opcional |
| `SNOW_JWT_ISS` | `{{SNOW_JWT_ISS}}` | Opcional |
| `SNOW_TOKEN_SCOPE` | `{{SNOW_TOKEN_SCOPE}}` | Opcional |

### 10.3 Adapter HTTP do passo 3

| Campo | Valor |
| --- | --- |
| Address | `{{SNOW_TOKEN_URL}}` |
| Proxy Type | Internet |
| Method | POST |
| Authentication | None |
| Timeout (in ms) | 30000 |
| Throw Exception on Failure | Marcado |
| Request Headers | `Content-Type\|Accept` |
| Response Headers | Deixe o valor padrão |

### 10.4 Runtime, parâmetros e deploy

**Allowed Header(s)**: `X-Trp-Snow-Token`. **Save → Configure**:

| Parâmetro | Valor |
| --- | --- |
| `SNOW_TOKEN_URL` | `https://<host>/oauth_token.do` (linha 9 da folha) |
| `SNOW_CLIENT_CREDENTIAL` | `TRP_SNOW_CLIENT` |
| `SNOW_JWT_KEY_ALIAS` | `trp_snow_jwt` |
| `SNOW_JWT_KID` | O Key ID combinado (linha 11) |
| `SNOW_JWT_SUB` | O usuário de integração (linha 12) |
| `SNOW_ASSERTION_PARAM` | `assertion` |
| `SNOW_JWT_AUD` | `-` (o script usa o client ID) |
| `SNOW_JWT_ISS` | `-` (o script usa o client ID) |
| `SNOW_TOKEN_SCOPE` | `-` |

**Deploy** e confira **Started**.

Cuidados:

- O corpo do passo 3 contém o client secret e o JWT. Mantenha o log em **Info**.
- O token do ServiceNow dura cerca de 30 minutos e não tem renovação; cada ciclo pede um novo.
- O JWT vale 5 minutos e leva um identificador único (`jti`), por causa da verificação de JTI do ServiceNow.

---

## 11. Passo 8 — IF_TRP_99_ConnectionTest

iFlow temporário. Ele roda **uma vez a cada deploy** e prova, de uma só vez: Keystore, credencial, JWT, cadastro no ServiceNow, token, gravação no chamado e a existência das colunas `u_cpi_*` com permissão de escrita.

**Script:** [`scripts/TRP_99_ConnectionTest.groovy`](scripts/TRP_99_ConnectionTest.groovy)

### 11.1 Montagem

```text
Timer -> [1 Content Modifier] -> [2 Request Reply] -> [3 Script] -> [4 Router] --DO_UPDATE = true--> [5 Request Reply] -> [6 Script] -> End
                                        |                              |                                  |
                          ProcessDirect /trp/snow-token                +--(padrão)--> End        ProcessDirect /trp/snow-update
```

| # | Nome do passo | Tipo | Configuração |
| --- | --- | --- | --- |
| 0 | Timer | Timer (Start) | Aba **Scheduler**: **Run Once** |
| 1 | Config | Content Modifier | Exchange Property: `TEST_SYS_ID` = Constant `{{TEST_SYS_ID}}` |
| 2 | Token | Request Reply → **ProcessDirect** | Receiver `TRP_03`. Address: `/trp/snow-token` |
| 3 | Depois do token | Groovy Script | Script Function: `afterToken` |
| 4 | Atualizar? | Router | Rota 1: Expression Type **Non-XML**, Condition `${property.DO_UPDATE} = 'true'`. Rota 2: **Default Route**, direto para um **End** |
| 5 | Work note | Request Reply → **ProcessDirect** | Receiver `TRP_04`. Address: `/trp/snow-update` |
| 6 | Depois da atualização | Groovy Script | Script Function: `afterUpdate` |

### 11.2 Teste 1 — só o token

1. **Configure**: `TEST_SYS_ID` = `-`. **Deploy**.
2. **Monitor Message Processing**: procure `IF_TRP_99_ConnectionTest`.
3. **Sucesso:** status **Completed** e, nos detalhes da mensagem, em **Custom Headers**, `TRP_TokenTest = OK - token com N caracteres, expira em N s`.
4. **Falha:** abra a mensagem com erro (a do `IF_TRP_03`), leia o texto do erro e vá para a seção 16.

### 11.3 Teste 2 — token e gravação

1. **Configure**: `TEST_SYS_ID` = sys_id do chamado de teste (linha 14). **Deploy** de novo.
2. **Sucesso:** Custom Header `TRP_UpdateTest = OK - work note e campo u_cpi_message gravados…` e, no chamado, a work note "[Transporte CPI] Teste de conexão do CPI. Pode ignorar." e o campo `u_cpi_message` = "Teste de conexão do CPI".
3. Confira no chamado que nenhum outro campo mudou.
4. **Falha com `[TRP-GRAVACAO] O ServiceNow respondeu 200 mas não gravou: u_cpi_message…`:** a coluna não existe em `sc_req_item` ou o usuário de integração não pode gravar nela. O ServiceNow não devolve erro nesse caso (responde 200 e ignora o campo), por isso o `IF_TRP_04` confere a resposta. Volte à seção 6.2 (colunas) e 6.6 (ACL).

Depois dos dois testes, **Undeploy** o `IF_TRP_99_ConnectionTest`. Mantenha-o no pacote: ele serve de novo quando o certificado for trocado.

---

## 12. Passo 9 — IF_TRP_02_AdvanceTicket

O coração da automação: recebe um chamado e executa **um** passo.

**Script:** [`scripts/TRP_02_AdvanceTicket.groovy`](scripts/TRP_02_AdvanceTicket.groovy)

### 12.1 Desenho

O script `prepare` lê o chamado e grava a propriedade `ROUTE`. O primeiro roteador só olha esse valor. Os casos que não precisam de chamada HTTP (prazo vencido, campos inválidos, pedir o clique ao dev) o próprio `prepare` resolve, com `ROUTE = DONE`.

```text
Sender --ProcessDirect /trp/advance--> Start
  -> [1 Config] -> [2 Script prepare] -> [3 Router "Rota"]
        |-- CHECK_QUEUE  -> [HTTP GET fila]      -> [Script findTrByTicket]  --+
        |-- CHECK_IMPORT -> [HTTP GET ação]      -> [Script evaluateImport]  --+
        |-- CHECK_DEPLOY -> [HTTP GET runtime]   -> [Script evaluateDeploy]  --+
        |-- (Etapa B) EXPORT, CHECK_EXPORT                                   --+
        |-- padrão (DONE) ---------------------------------------------------+
                                                                              v
  -> [4 Router "Próximo"]
        |-- START_IMPORT -> [Script buildImportRequest] -> [HTTP POST import] -> [Script handleImportResponse] --+
        |-- START_DEPLOY -> [HTTP POST deploy] -> [Script handleDeployResponse]                               --+
        |-- padrão ---------------------------------------------------------------------------------------------+
                                                                                                                 v
  -> [5 Router "Atualizar?"]
        |-- UPDATE = true -> [Script buildUpdateRequest] -> [ProcessDirect /trp/snow-update] -> End
        |-- padrão -> End

Exception Subprocess:  Error Start -> [Script handleError] -> [ProcessDirect /trp/snow-update] -> End Message
```

**Juntar rotas:** as rotas de um roteador podem terminar no mesmo passo seguinte. Basta arrastar a seta do último passo de cada rota até o próximo roteador. Se o editor não permitir no seu tenant, repita o roteador seguinte no fim de cada rota.

### 12.2 Passos 1 e 2

Sender: ProcessDirect, Address `/trp/advance`.

**1 — Content Modifier "Config"** (aba Exchange Property, Source Type = Constant):

| Name | Source Value | Bloco |
| --- | --- | --- |
| `EXPORT_MODE` | `{{EXPORT_MODE}}` | A — obrigatório |
| `CTMS_URI` | `{{CTMS_URI}}` | A — obrigatório |
| `CTMS_QAS_NODE_ID` | `{{CTMS_QAS_NODE_ID}}` | A — obrigatório |
| `CTMS_NAMED_USER` | `{{CTMS_NAMED_USER}}` | A — recomendado |
| `MAX_RETRIES` | `{{MAX_RETRIES}}` | A — recomendado |
| `TIMEOUT_WAITING_TR_MIN` | `{{TIMEOUT_WAITING_TR_MIN}}` | A — recomendado |
| `TIMEOUT_IMPORT_MIN` | `{{TIMEOUT_IMPORT_MIN}}` | A — recomendado |
| `TIMEOUT_DEPLOY_MIN` | `{{TIMEOUT_DEPLOY_MIN}}` | A — recomendado |

Os parâmetros dos blocos B (ajustes do cTMS) e C (Etapa B) estão na seção 19. Só crie os do bloco B se o Passo 2 mostrou algo diferente do padrão.

**2 — Groovy Script "Preparar"**: Script Function `prepare`.

### 12.3 Passo 3 — Router "Rota"

Para cada rota: clique na seta que sai do roteador → aba **Processing** → Expression Type **Non-XML** → Condition Expression. Marque a última como **Default Route**.

| Ordem | Nome da rota | Condition Expression | Passos da rota |
| --- | --- | --- | --- |
| 1 | Fila | `${property.ROUTE} = 'CHECK_QUEUE'` | Request Reply **A** → Script `findTrByTicket` |
| 2 | Import | `${property.ROUTE} = 'CHECK_IMPORT'` | Request Reply **B** → Script `evaluateImport` |
| 3 | Deploy | `${property.ROUTE} = 'CHECK_DEPLOY'` | Request Reply **C** → Script `evaluateDeploy` |
| 4 | Padrão | Default Route | Nenhum (seta direto para o roteador "Próximo") |

As rotas `EXPORT` e `CHECK_EXPORT` entram na Etapa B (seção 15).

### 12.4 Passo 4 — Router "Próximo"

| Ordem | Nome da rota | Condition Expression | Passos da rota |
| --- | --- | --- | --- |
| 1 | Iniciar import | `${property.NEXT} = 'START_IMPORT'` | Script `buildImportRequest` → Request Reply **D** → Script `handleImportResponse` |
| 2 | Iniciar deploy | `${property.NEXT} = 'START_DEPLOY'` | Request Reply **E** → Script `handleDeployResponse` |
| 3 | Padrão | Default Route | Nenhum (seta direto para o roteador "Atualizar?") |

### 12.5 Passo 5 — Router "Atualizar?"

| Ordem | Nome da rota | Condition Expression | Passos da rota |
| --- | --- | --- | --- |
| 1 | Atualizar | `${property.UPDATE} = 'true'` | Script `buildUpdateRequest` → Request Reply **F** → End |
| 2 | Padrão | Default Route | End |

### 12.6 As chamadas (Request Reply)

Crie um **Receiver** para cada chamada. Em todas as chamadas HTTP: Proxy Type **Internet**, Timeout **60000**, Response Headers no valor padrão (os scripts funcionam com ou sem os headers da resposta; isso foi testado no laboratório).

| Ref. | Receiver | Adapter | Address | Query | Method | Authentication / Credential Name | Request Headers | Throw Exception on Failure |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| A | `cTMS_Fila` | HTTP | `${property.CTMS_QUEUE_URL}` | Vazio | GET | OAuth2 Client Credentials / `{{CRED_CTMS}}` | `Accept` | Marcado |
| B | `cTMS_Acao` | HTTP | `${property.CTMS_ACTION_URL}` | Vazio | GET | OAuth2 Client Credentials / `{{CRED_CTMS}}` | `Accept` | Marcado |
| C | `CPI_QAS_Runtime` | HTTP | `{{CPI_QAS_URL}}/api/v1/IntegrationRuntimeArtifacts(%27${property.ARTIFACT_ID}%27)` | Vazio | GET | OAuth2 Client Credentials / `{{CRED_CPI_QAS}}` | `Accept` | **Desmarcado** |
| D | `cTMS_Import` | HTTP | `${property.CTMS_IMPORT_URL}` | Vazio | POST | OAuth2 Client Credentials / `{{CRED_CTMS}}` | `Content-Type\|Accept` | Marcado |
| E | `CPI_QAS_Deploy` | HTTP | `{{CPI_QAS_URL}}/api/v1/DeployIntegrationDesigntimeArtifact` | `Id=%27${property.ARTIFACT_ID}%27&Version=%27active%27` | POST | OAuth2 Client Credentials / `{{CRED_CPI_QAS}}` | `Accept` | Marcado |
| F | `TRP_04` | ProcessDirect | `/trp/snow-update` | — | — | — | — | — |

Observações:

- **C fica com "Throw Exception on Failure" desmarcado** de propósito: logo depois do deploy o iFlow ainda não aparece no runtime e a API responde 404. O script trata isso como "espere o próximo ciclo".
- `%27` é a aspa simples codificada. Digite exatamente assim.
- Na chamada **A**, o campo Query pode receber um filtro (por exemplo `status=in`) se o Teste 2 do Passo 2 mostrou que a fila devolve muitas requests antigas e a API aceitar o filtro. O script já ignora requests que não estão importáveis, com ou sem filtro.

### 12.7 Exception Subprocess

Adicione um **Exception Subprocess** dentro da caixa Integration Process:

| # | Passo | Tipo | Configuração |
| --- | --- | --- | --- |
| 1 | Início | Error Start Event | Já vem no subprocesso |
| 2 | Classificar erro | Groovy Script | Script Function: `handleError` |
| 3 | Atualizar chamado | Request Reply → ProcessDirect | Receiver `TRP_04_Erro`. Address: `/trp/snow-update` |
| 4 | Fim | **End Message** | Não use Error End: o orquestrador precisa seguir para o próximo chamado |

O que o `handleError` faz:

- **Erro passageiro** (HTTP 408, 425, 429, 500, 502, 503, 504 ou falha de rede): soma uma tentativa no chamado e mantém o estado. O próximo ciclo tenta de novo. Na quinta tentativa (`MAX_RETRIES`), vira `FAILED`.
- **Erro definitivo** (400, 401, 403, 404 e outros): `FAILED`, com o motivo em `u_cpi_message` e nas work notes.
- O erro também aparece no monitor do CPI, em **Custom Headers → TRP_Error**.

### 12.8 Runtime, parâmetros e deploy

**Allowed Header(s)**: `X-Trp-Snow-Token`. **Save → Configure**:

| Parâmetro | Valor na Etapa A |
| --- | --- |
| `EXPORT_MODE` | `MANUAL_TR` |
| `CTMS_URI` | `uri` do cTMS (linha 1 da folha) |
| `CTMS_QAS_NODE_ID` | ID numérico do nó de QAS (linha 4) |
| `CTMS_NAMED_USER` | `cpi-orquestracao` |
| `MAX_RETRIES` | `5` |
| `TIMEOUT_WAITING_TR_MIN` | `60` |
| `TIMEOUT_IMPORT_MIN` | `15` |
| `TIMEOUT_DEPLOY_MIN` | `10` |
| `CPI_QAS_URL` | `url` da API do CPI QAS (linha 5), sem barra no fim |
| `CRED_CTMS` | `TRP_CTMS` |
| `CRED_CPI_QAS` | `TRP_CPI_QAS` |

**Deploy** e confira **Started**.

### 12.9 Como o deploy é conferido

O `evaluateDeploy` só grava `SUCCESS` quando as três coisas são verdade:

1. o iFlow está `STARTED` no runtime de QAS;
2. a data do deploy (`DeployedOn`) é **posterior** ao pedido de deploy deste chamado, com 2 minutos de tolerância. Isso evita confundir com um deploy antigo do mesmo iFlow;
3. a versão em runtime é a que o chamado pediu.

Se o iFlow subiu, mas numa **versão diferente** da pedida, o chamado vira `FAILED` com a explicação. Isso acontece quando o dev informa uma versão e o DEV tinha outra salva na hora do Transport: o transporte leva o que está salvo no DEV.

---

## 13. Passo 10 — IF_TRP_01_Orchestrator

**Script:** [`scripts/TRP_01_Orchestrator.groovy`](scripts/TRP_01_Orchestrator.groovy)

### 13.1 Montagem

```text
Timer -> [1 Config] -> [2 Router "Ligado?"] --TRP_ENABLED diferente de true--> End
                              |
                           (padrão)
                              v
   [3 Request Reply /trp/snow-token] -> [4 Script prepareSearch] -> [5 Request Reply HTTP GET chamados]
   -> [6 Script ticketsToLines] -> [7 Router "Há chamados?"] --TICKET_COUNT = 0--> End
                                          |
                                       (padrão)
                                          v
   [8 Iterating Splitter] -> [9 Script lineToRequest] -> [10 Request Reply /trp/advance] -> End
```

| # | Nome do passo | Tipo | Configuração |
| --- | --- | --- | --- |
| 0 | Timer | Timer (Start) | Tabela 13.2 |
| 1 | Config | Content Modifier | Exchange Property (Constant): `TRP_ENABLED` = `{{TRP_ENABLED}}`; `SNOW_CAT_ITEM_SYSID` = `{{SNOW_CAT_ITEM_SYSID}}`; `SNOW_LIMIT` = `{{SNOW_LIMIT}}` |
| 2 | Ligado? | Router | Rota 1 (Non-XML): `${property.TRP_ENABLED} != 'true'` → End. Rota 2: Default Route → passo 3 |
| 3 | Token | Request Reply → ProcessDirect | Receiver `TRP_03`. Address `/trp/snow-token` |
| 4 | Preparar busca | Groovy Script | Script Function: `prepareSearch` |
| 5 | Buscar chamados | Request Reply → HTTP | Tabela 13.3 |
| 6 | Um por linha | Groovy Script | Script Function: `ticketsToLines` |
| 7 | Há chamados? | Router | Rota 1 (Non-XML): `${property.TICKET_COUNT} = '0'` → End. Rota 2: Default Route → passo 8 |
| 8 | Separar | Iterating Splitter | Tabela 13.4 |
| 9 | Preparar chamado | Groovy Script | Script Function: `lineToRequest` |
| 10 | Avançar chamado | Request Reply → ProcessDirect | Receiver `TRP_02`. Address `/trp/advance` |

Não adicione Exception Subprocess neste iFlow. Se o token ou a busca falharem, a mensagem fica como **Failed** no monitor (é o alerta que você quer ver), nenhum chamado muda de estado e o próximo ciclo tenta de novo.

### 13.2 Timer (aba Scheduler)

| Campo | Valor |
| --- | --- |
| Tipo | **Schedule to Recur** |
| Schedule Recurrence | Daily |
| Every | 2 min (se não existir essa opção, use 5 min) |
| Between | 07:00 e 22:00 (ajuste ao horário de trabalho dos devs) |
| Time Zone | (UTC-03:00) Brasília / São Paulo |

### 13.3 Adapter HTTP do passo 5

| Campo | Valor |
| --- | --- |
| Address | `https://{{SNOW_HOST}}/api/now/table/{{SNOW_TABLE}}` |
| Query | `${property.SNOW_QUERY}` |
| Proxy Type | Internet |
| Method | GET |
| Authentication | None |
| Timeout (in ms) | 60000 |
| Throw Exception on Failure | Marcado |
| Request Headers | `Authorization\|Accept` |
| Response Headers | Deixe o valor padrão |

O script `prepareSearch` monta a query já codificada. Ela pede só os chamados do item de catálogo do transporte que estão num estado em andamento, do mais antigo para o mais novo, com datas em UTC.

### 13.4 Iterating Splitter do passo 8

| Campo | Valor |
| --- | --- |
| Expression Type | **Line Break** |
| Grouping | 1 |
| Streaming | Desmarcado |
| Parallel Processing | Desmarcado |
| Stop on Exception | **Desmarcado** (um chamado com erro não trava os outros) |

### 13.5 Runtime, parâmetros e deploy

**Allowed Header(s)**: `X-Trp-Snow-Token`. **Save → Configure**:

| Parâmetro | Valor |
| --- | --- |
| `TRP_ENABLED` | `false` (por enquanto) |
| `SNOW_HOST` | Host do ServiceNow |
| `SNOW_TABLE` | `sc_req_item` |
| `SNOW_CAT_ITEM_SYSID` | sys_id do item de catálogo (linha 13 da folha) |
| `SNOW_LIMIT` | `20` |

**Deploy** e confira **Started**. Com `TRP_ENABLED = false`, cada ciclo termina no primeiro roteador, sem chamar nada.

**Só pode existir um orquestrador ligado.** Se o mesmo pacote for implantado em outro tenant com `TRP_ENABLED = true`, os dois vão processar os mesmos chamados.

---

## 14. Passo 11 — Testes ponta a ponta

Deixe o log do `IF_TRP_02` em **Debug** durante os testes e volte para **Info** no fim. Para achar as execuções de um chamado: **Monitor Message Processing**, filtro por ID com o número do chamado (o script grava o número como Application ID).

| # | Teste | Como fazer | Resultado esperado |
| --- | --- | --- | --- |
| 1 | Ligar sem chamados | Feche ou conclua os chamados de teste. `TRP_ENABLED = true`, **Deploy** | A cada 2 min: `IF_TRP_01` Completed e `IF_TRP_03` Completed. Nenhuma execução do `IF_TRP_02` |
| 2 | Campos inválidos | Abra um chamado com versão `abc` (se a validação do formulário deixar) ou altere a coluna `u_cpi_artifact_version` à mão para `abc` e o status para `PENDING` | No ciclo seguinte: `FAILED`, `u_cpi_message` = "Campos inválidos…", chamado atribuído ao suporte |
| 3 | Caminho feliz | Salve uma versão do iFlow de teste em DEV. Abra o chamado. Clique em **Transport** com o nº do chamado na descrição | `PENDING` → `WAITING_TR` → `IMPORTING` → `DEPLOYING` → `SUCCESS`. Work notes de cada passo. iFlow em QAS na versão pedida. Chamado fechado |
| 4 | Chamado sem clique | Abra um chamado e **não** clique em Transport. Para não esperar 60 min, use `TIMEOUT_WAITING_TR_MIN = 4` | `WAITING_TR` e, depois do prazo, `FAILED` com "Prazo do estado WAITING_TR vencido" |
| 5 | Versão diferente | Abra um chamado pedindo `9.9.9` e faça o Transport da versão real | `FAILED` com "subiu em QAS na versão X, mas o chamado pediu 9.9.9" |
| 6 | iFlow com erro | Transporte um iFlow de teste que não sobe (por exemplo, com uma credencial inexistente num adapter de polling) | `FAILED` com "ficou em ERROR em QAS" |
| 7 | Request de outro dev | Deixe na fila uma request sem nº de chamado e rode o teste 3 de novo | Só a request do chamado é importada. A outra continua na fila |
| 8 | Desligar | `TRP_ENABLED = false`, **Deploy** | Chamados novos ficam em `PENDING` até religar |

Em cada teste, confira três lugares: o **chamado** (status, `u_cpi_tr_id`, work notes), o **cTMS** (a request saiu da fila; log da ação) e o **CPI QAS** (versão e status em **Manage Integration Content**).

Aprovados os testes 1 a 7: `TIMEOUT_WAITING_TR_MIN` de volta para `60`, log em **Info**, `TRP_ENABLED = true`, e libere o item de catálogo para um time piloto antes de abrir para todos.

### 14.1 O que o laboratório já provou (e o que ele não prova)

Os oito testes acima, e mais 27, já foram executados no laboratório da pasta [`lab/`](lab/README.md). Para repetir: `cd lab && ./run-lab.sh` (precisa de Java, Maven, Python 3 e openssl).

**Como é o laboratório:** os cinco iFlows são montados passo a passo como nas tabelas deste guia, sobre o Apache Camel 3.14, e executam os arquivos de `scripts/` sem alteração. As chamadas HTTP são de verdade, contra simuladores que reproduzem as regras que mais derrubam integrações: o ServiceNow confere a assinatura RS256 do JWT com o certificado X.509, o `kid`, `aud`, `iss`, `sub`, validade e `jti` repetido; ignora em silêncio campo sem coluna ou sem permissão; ignora filtro inválido e devolve a tabela inteira. O cTMS tem fila, import por ID e ação. O CPI QAS devolve 404 logo após o deploy, depois `STARTING`, depois `STARTED` ou `ERROR`.

**Por que 64 combinações:** seis comportamentos do tenant não ficam claros na documentação pública (as propriedades atravessam o ProcessDirect? só os headers liberados passam? os headers voltam? o adapter HTTP copia os headers da resposta? o código HTTP fica disponível? o erro de um iFlow chamado chega embrulhado?). O laboratório roda tudo nas 64 combinações. O procedimento passa em todas, então não depende de qual delas é a do seu tenant.

**Pontos fracos que o laboratório achou e que esta versão corrige:**

| # | O que acontecia | Correção |
| --- | --- | --- |
| 1 | Coluna `u_cpi_*` inexistente ou sem permissão de escrita: o ServiceNow respondia 200, o chamado ficava em `PENDING` para sempre e ganhava uma work note a cada 2 minutos, sem erro em lugar nenhum | O `IF_TRP_04` confere a resposta e falha com `[TRP-GRAVACAO]`; o `IF_TRP_99` grava `u_cpi_message` para acusar isso já no Passo 8 |
| 2 | Coluna `u_cpi_transport_status` inexistente ou ilegível: o ServiceNow ignorava o filtro e devolvia todos os chamados do item | O `IF_TRP_01` falha com "chamados fora do filtro" e não processa nada |
| 3 | ServiceNow fora do ar logo depois de o import ser disparado: o chamado ficava em `WAITING_TR` até vencer o prazo, com a request já importada | O ciclo seguinte vê a request em import ou importada na fila e segue para o deploy |
| 4 | Request de outro chamado com número parecido (`RITM00100119` contém `RITM0010011`) era contada como duplicada | O número precisa aparecer como palavra inteira na descrição |
| 5 | Dois ciclos do Timer ao mesmo tempo: o segundo recebia "request não importável" do cTMS e marcava `FAILED` | Import recusado vira nova conferência da fila no ciclo seguinte (até `MAX_RETRIES`) |
| 6 | Sem o header `CamelHttpResponseCode`, a consulta do runtime marcava `FAILED` | O resultado é deduzido pelo corpo da resposta |

**O que o laboratório não prova:** que os simuladores se comportam como os sistemas reais em tudo. Os nomes de campos e status da API do cTMS, o comportamento do ServiceNow do banco (servidor de autorização, ACLs, Flow) e as telas do editor do CPI continuam sendo confirmados só no seu ambiente: Passo 2, Passo 8 e os testes da tabela acima. A Etapa B foi exercitada com uma API de export inventada, só para validar a sequência de estados.

---

## 15. Etapa B — Ligar o export por API

Só depois de: (1) migração para o Content Agent concluída ([`../MIGRACAO-CPI.md`](../MIGRACAO-CPI.md)); (2) API de export do Content Agent confirmada e testada à mão (seção 18, ponto 1).

### 15.1 Preparação

1. A documentação oficial do Content Agent ("Consuming APIs" e "Create Instance") diz que as APIs só existem no plano **`application`**, com a role **Export**. A sua instância `Content_Agent` é `standard`: ela atende a tela e o botão Transport, não a API. Crie uma instância do Content Agent no plano `application` com a role `Export`, no subaccount de onde o conteúdo sai (DEV), e nela a service key `key-cas-dev-trp`. As destinations `CloudIntegration` e `TransportManagementService` precisam existir nesse mesmo subaccount.
2. No CPI de orquestração: crie a credencial **OAuth2 Client Credentials** `TRP_CAS_DEV` com o token URL, client ID e secret dessa key.
3. Se a migração criou um nó de QAS novo (`QAS_CAS`), pegue o ID dele (Teste 1 do Passo 2).

### 15.2 Mudanças no `IF_TRP_02`

**No Content Modifier "Config"**, acrescente as propriedades do bloco C (seção 19.3).

**No Router "Rota"**, acrescente duas rotas, antes da rota padrão, e termine as duas no roteador "Próximo":

| Nome da rota | Condition Expression | Passos da rota |
| --- | --- | --- |
| Exportar | `${property.ROUTE} = 'EXPORT'` | Script `buildExportRequest` → Request Reply **G** → Script `handleExportResponse` |
| Export pronto? | `${property.ROUTE} = 'CHECK_EXPORT'` | Request Reply **H** → Script `evaluateExportStatus` |

| Ref. | Receiver | Address | Method | Credential Name | Request Headers | Throw Exception on Failure |
| --- | --- | --- | --- | --- | --- | --- |
| G | `CAS_Export` | `${property.CAS_EXPORT_URL}` | POST | `{{CRED_CAS_DEV}}` | `Content-Type\|Accept` | Marcado |
| H | `CAS_Status` | `${property.CAS_STATUS_URL}` | GET | `{{CRED_CAS_DEV}}` | `Accept` | Marcado |

### 15.3 Virada

1. **Configure**: preencha o bloco C, `EXPORT_MODE = API`, `CTMS_QAS_NODE_ID` = nó novo, `TIMEOUT_EXPORT_MIN = 15`.
2. **Deploy**.
3. Teste com um chamado **sem** clicar em Transport: `PENDING` → `EXPORTING` → `IMPORTING` → `DEPLOYING` → `SUCCESS`.
4. Atualize o texto de ajuda do item de catálogo: o dev só abre o chamado.

Para voltar à Etapa A a qualquer momento: `EXPORT_MODE = MANUAL_TR` e **Deploy**.

---

## 16. Problemas comuns e soluções

Comece sempre pelo **texto do erro**: no chamado (`u_cpi_message` e work notes) ou no monitor do CPI (mensagem com status Failed, ou **Custom Headers → TRP_Error**).

### 16.1 Token e ServiceNow

| Sintoma | Causa provável | O que fazer |
| --- | --- | --- |
| "Credencial TRP_SNOW_CLIENT não encontrada no Security Material" | Credencial não implantada, ou nome diferente do parâmetro `SNOW_CLIENT_CREDENTIAL` | Confira o nome e o status **Deployed** |
| "Alias … não é uma chave privada no Keystore" | Alias errado, ou a entrada é só um certificado | A entrada precisa ser do tipo **Key Pair** e o alias igual ao `SNOW_JWT_KEY_ALIAS` |
| HTTP 401 no pedido de token, `invalid_client` | Client ID ou secret errados | Refaça a credencial `TRP_SNOW_CLIENT` |
| HTTP 400 ou 401 no pedido de token, `invalid_grant` ou erro de JWT | Uma das checagens do JWT falhou | Confira, nesta ordem: `SNOW_JWT_KID` igual ao Kid do Verifier Map; certificado cadastrado é o do alias `trp_snow_jwt`; `SNOW_JWT_SUB` é um usuário ativo e bate com o *User Field*; `aud` e `iss` iguais ao Client ID (deixe `SNOW_JWT_AUD` e `SNOW_JWT_ISS` como `-`). Peça ao time do ServiceNow o log do erro |
| "Resposta de token sem access_token" | O servidor respondeu 200 sem token | O texto do erro traz o `error` devolvido. Se o servidor de autorização for o IdP corporativo, confira `SNOW_TOKEN_URL`, `SNOW_ASSERTION_PARAM` (`client_assertion`), `SNOW_JWT_AUD` e `SNOW_TOKEN_SCOPE` |
| Token ok, mas a busca dá 401 | O token não vale para a Table API (escopo) | Time do ServiceNow: escopo e *auth scope* do cliente OAuth |
| Token ok, mas a busca ou a gravação dá 403 | ACL ou role do usuário de integração | Seção 6.6 |
| A busca volta vazia com chamado aberto | ACL esconde o registro; `SNOW_CAT_ITEM_SYSID` errado; o flow não gravou `PENDING`; fora do horário do Timer | Confira cada item. Teste a mesma busca no REST API Explorer do ServiceNow com o usuário de integração |
| Chamado vai para `FAILED` com "Campos inválidos" e os campos estão preenchidos no formulário | As colunas `u_cpi_artifact_id`, `u_cpi_artifact_version`, `u_cpi_package_id` estão vazias ou com nome diferente | O flow precisa copiar as variáveis para as colunas (6.4, passo 2). Nomes exatos da 6.2 |
| HTTP 404 ao gravar | `SNOW_TABLE` errado, ou o método PATCH não é aceito | Confira a tabela; troque para PUT (9.2) |
| `[TRP-GRAVACAO] O ServiceNow respondeu 200 mas não gravou: …` | A coluna citada não existe em `sc_req_item`, ou o usuário de integração não tem permissão de escrita nela. O ServiceNow não devolve erro: ignora o campo | Crie a coluna com o nome exato (6.2) e libere a escrita (6.6). Depois rode o `IF_TRP_99` de novo |
| `IF_TRP_01` Failed com "O ServiceNow devolveu N de M chamados fora do filtro" | A coluna `u_cpi_transport_status` não existe ou o usuário de integração não pode lê-la. O ServiceNow ignora a condição e devolve todos os chamados do item | Mesma correção. Nenhum chamado é alterado enquanto isso |
| O prazo estoura na hora, ou nunca estoura | `u_cpi_stage_since` gravado fora de UTC | O CPI grava em UTC pela Table API. Não preencha esse campo por script com horário local |

### 16.2 Entre iFlows

| Sintoma | Causa provável | O que fazer |
| --- | --- | --- |
| "Token do ServiceNow ausente (header X-Trp-Snow-Token)" | O header não chegou ao iFlow chamado | **Runtime Configuration → Allowed Header(s)** = `X-Trp-Snow-Token` nos iFlows 02 e 04 |
| Erro de ProcessDirect dizendo que não há consumidor para o endereço | O iFlow de destino não está **Started**, ou o Address tem diferença de letra | Confira os três endereços: `/trp/snow-token`, `/trp/snow-update`, `/trp/advance` |
| Erro de script dizendo que o método não existe | Nome errado em **Script Function** | Compare com o nome da tabela do passo; maiúsculas contam |
| Erro "unable to resolve class" no script | O conteúdo do script não foi colado inteiro | Recole o arquivo completo, incluindo os `import` |
| O roteador falha por não achar rota | Faltou marcar a **Default Route** | Marque a rota padrão em cada roteador |
| `IF_TRP_02` nunca é chamado | `TRP_ENABLED` diferente de `true`; busca vazia | Veja a execução do `IF_TRP_01`: em qual roteador terminou |

### 16.3 cTMS

| Sintoma | Causa provável | O que fazer |
| --- | --- | --- |
| HTTP 401 | Credencial `TRP_CTMS` com token URL sem `/oauth/token`, ou secret trocado | Refaça com os dados da `key-tms-trp` |
| HTTP 403 | Key de uma instância que não é plano `standard` | Crie a key na instância `standard` |
| HTTP 404 na fila, no import ou na ação | `CTMS_QAS_NODE_ID` errado, ou caminho diferente na sua versão da API | Repita os testes do Passo 2 e ajuste os parâmetros do bloco B |
| Fica em `WAITING_TR` e a request está na fila | A descrição da request não contém o número do chamado como palavra inteira (`RITM0012345`, sem letra ou dígito colado antes ou depois); a request está em outro nó; o status dela não é "importável" | Compare a descrição com o número (RITM…); confira o nó; veja o `status` no Teste 2 e ajuste `CTMS_IMPORTABLE_VALUES` |
| Work note "O cTMS recusou o import da request N; a fila será conferida de novo" | Outro ciclo (ou alguém na tela do cTMS) começou o import antes | Nada: o ciclo seguinte acompanha pela fila. Se a recusa se repetir `MAX_RETRIES` vezes, o chamado vai para `FAILED` com o texto do cTMS |
| Work note "A request N já estava importada no cTMS" | O import aconteceu e o chamado não ficou sabendo (ServiceNow fora do ar na hora, ou import feito à mão) | Nada: o deploy segue normalmente |
| `FAILED` "A request N do chamado está com status ERROR na fila" | Um import dessa request falhou fora da automação | Veja o log no cTMS, corrija, e volte o chamado para `WAITING_TR` depois de um novo Transport |
| `FAILED` "Há mais de uma request importável na fila com …" | O dev clicou em Transport duas vezes com o mesmo número | Apague a request sobrando no cTMS e volte o status do chamado para `WAITING_TR` |
| `FAILED` "cTMS não devolveu o ID da ação de import" | O campo do ID da ação tem outro nome | Parâmetro `CTMS_ACTIONID_JSONPATH` |
| Fica em `IMPORTING` até o prazo | O status final da ação tem um valor que o script não conhece | Veja o valor no Teste 4 e ajuste `CTMS_ACTION_OK_VALUES` |
| `FAILED` "Import … terminou em ERROR" | O import falhou no destino | Abra o log da ação no cTMS (o ID está em `u_cpi_action_id`) |

### 16.4 Deploy no CPI QAS

| Sintoma | Causa provável | O que fazer |
| --- | --- | --- |
| HTTP 401 no deploy ou na consulta | Credencial `TRP_CPI_QAS` | Refaça com a `key-pi-api-qas-deploy` |
| HTTP 403 no deploy | Falta `WorkspaceArtifactsDeploy` | Atualize a instância `pi-api-qas-deploy` e crie uma key nova |
| HTTP 403 no deploy falando de **CSRF** | O tenant exige token CSRF mesmo com OAuth (não é o comum) | Antes do deploy, faça um GET em `{{CPI_QAS_URL}}/api/v1/` com o header `X-CSRF-Token: Fetch`, e mande no POST o `X-CSRF-Token` devolvido e os cookies. Me avise que eu acrescento esse passo ao script |
| HTTP 404 no deploy | O iFlow não existe no workspace de QAS com esse ID | O ID do chamado é o técnico? O import trouxe mesmo o pacote? |
| HTTP 403 na consulta do runtime | Falta `MonitoringDataRead` | Atualize a instância e crie uma key nova |
| "Consulta do runtime em QAS não devolveu JSON" | A chamada **C** foi sem `Accept: application/json` e a API respondeu em XML | Request Headers da chamada C = `Accept` (12.6) |
| Tentativas contando com "Consulta do runtime em QAS retornou HTTP desconhecido: …" | O tenant não expõe o código HTTP e a resposta não é a esperada. O texto depois dos dois-pontos é a resposta do CPI QAS | Leia a resposta: normalmente é credencial ou role (linhas acima) |
| `FAILED` "ficou em ERROR em QAS" | O iFlow não iniciou em QAS | **Manage Integration Content** no CPI QAS mostra o motivo (credencial, endpoint, parâmetro de QAS) |
| `FAILED` "subiu em QAS na versão X, mas o chamado pediu Y" | A versão salva em DEV na hora do Transport não era a do chamado | Salve a versão certa em DEV, novo Transport, novo chamado |
| Fica em `DEPLOYING` até o prazo, com o iFlow `STARTED` | O deploy não mudou a data (`DeployedOn`), ou os relógios diferem mais de 2 minutos | Veja `DeployedOn` no Teste 5. Se o deploy realmente aconteceu, me mande o valor para ajustarmos a tolerância |

---

## 17. Operação

| Situação | O que fazer |
| --- | --- |
| Parar tudo | `TRP_ENABLED = false` no `IF_TRP_01` (**Configure**) e **Deploy** |
| Achar as execuções de um chamado | **Monitor Message Processing**, busca pelo número do chamado |
| Reprocessar um chamado em `FAILED` | Corrija a causa. Depois altere `u_cpi_transport_status` no chamado: `PENDING` recomeça do início; `WAITING_TR` procura a request de novo; `IMPORTING` ou `DEPLOYING` refaz só a checagem. Ao voltar para um estado com prazo, atualize também `u_cpi_stage_since` para agora |
| Chamado parado num estado | O Timer está rodando? O ciclo do `IF_TRP_01` terminou com erro? Está dentro do horário configurado? |
| Uma atualização no ServiceNow falhou depois de o import ter sido iniciado | A execução fica **Failed** no monitor e o chamado não muda. No ciclo seguinte o CPI vê na fila do cTMS que a request está em import ou já importada e segue sozinho para o deploy (work note "já estava importada") |
| Mais de 20 chamados em andamento ao mesmo tempo | Cada ciclo pega os 20 mais antigos (`SNOW_LIMIT`), e chamado esperando o clique do dev ocupa vaga. Aumente `SNOW_LIMIT` |
| Um ciclo demora mais que o intervalo do Timer | Dois ciclos podem rodar juntos. O procedimento tolera (testado no laboratório), mas se virar rotina aumente o intervalo para 5 minutos |
| Trocar o certificado do JWT | Crie um par novo no Keystore (alias novo). Entregue o certificado; o ServiceNow cadastra um **segundo** Verifier Map com Kid novo. Troque `SNOW_JWT_KEY_ALIAS` e `SNOW_JWT_KID`, **Deploy**, rode o `IF_TRP_99`. Só então remova o par e o Verifier Map antigos |
| Trocar uma service key | Crie a key nova, atualize a credencial no Security Material (**Deploy** da credencial), teste, e só então apague a key antiga no BTP |
| Mudar um script | Edite o arquivo em `scripts/`, rode os testes locais ([`tests/README.md`](tests/README.md)), cole no iFlow e **Deploy** |

---

## 18. Pontos a confirmar

| # | Ponto | Impacto | Como confirmar |
| --- | --- | --- | --- |
| 1 | API de export do Content Agent: caminho, corpo, status e onde vem o ID da request. Já confirmado na documentação: as APIs só existem no plano `application` com a role `Export` (15.1). Falta o contrato da API, que fica atrás de login no api.sap.com | Só a Etapa B. Parâmetros do bloco C | Com o seu login: api.sap.com → "Content Agent Service" → especificação da API |
| 2 | Caminhos e campos da API v2 do cTMS (fila, import por ID, ação) | Etapa A. O script usa os caminhos padrão | Passo 2 deste guia. Se diferir, bloco B |
| 3 | Servidor de autorização do ServiceNow (o próprio ServiceNow ou o IdP corporativo), nome do parâmetro (`assertion` ou `client_assertion`) e escopo | Parâmetros do `IF_TRP_03` | Time do ServiceNow / Hub de Arquitetura |
| 4 | Keystore do CPI aceito como cofre da chave, ou HSM obrigatório | Se HSM: a assinatura do JWT sai do script e vira uma chamada ao HSM | Segurança da Informação |
| 5 | Método PATCH disponível no adapter HTTP do tenant | Passo 6. Alternativa: PUT | Lista de métodos do adapter |
| 6 | Intervalo de 2 minutos no Timer | Passo 10. Alternativa: 5 minutos | Lista de opções do Timer |
| 7 | Juntar rotas de um roteador no mesmo passo | Passo 9. Alternativa: repetir o roteador em cada rota | Editor do tenant |
| 8 | Repasse do header `X-Trp-Snow-Token` pelo ProcessDirect | Todos. Já prevenido com Allowed Header(s). O laboratório rodou com e sem repasse de propriedades e de headers: funciona nos dois | Passo 8 |
| 9 | Deploy por API sem token CSRF com OAuth | Passo 9. Alternativa na seção 16.4 | Teste 6 do Passo 2 |

O que **não** foi testado: a montagem dos iFlows num tenant SAP real e as chamadas reais ao ServiceNow, ao cTMS e ao CPI QAS. O que **foi** testado: os scripts com 100 verificações unitárias (Groovy 2.4 e 4) e o procedimento inteiro no laboratório da seção 14.1 (35 cenários em 64 combinações de comportamento do tenant, sobre o Apache Camel 3.14, com HTTP e JWT de verdade contra simuladores).

---

## 19. Referência de parâmetros

### 19.1 Bloco A — usados na Etapa A

| Parâmetro | iFlow | Exemplo | Padrão no script |
| --- | --- | --- | --- |
| `SNOW_HOST` | 01, 04 | `bradesco.service-now.com` | — |
| `SNOW_TABLE` | 01, 04 | `sc_req_item` | — |
| `SNOW_CAT_ITEM_SYSID` | 01 | sys_id do item de catálogo | — (obrigatório) |
| `SNOW_LIMIT` | 01 | `20` | 20 |
| `TRP_ENABLED` | 01 | `true` / `false` | — |
| `SNOW_TOKEN_URL` | 03 | `https://<host>/oauth_token.do` | — |
| `SNOW_CLIENT_CREDENTIAL` | 03 | `TRP_SNOW_CLIENT` | — |
| `SNOW_JWT_KEY_ALIAS` | 03 | `trp_snow_jwt` | — |
| `SNOW_JWT_KID` | 03 | `trp-cpi-2026` | — |
| `SNOW_JWT_SUB` | 03 | `svc_cpi_transport` | — |
| `SNOW_ASSERTION_PARAM` | 03 | `assertion` | `assertion` |
| `SNOW_JWT_AUD` / `SNOW_JWT_ISS` | 03 | `-` | client ID |
| `SNOW_TOKEN_SCOPE` | 03 | `-` | sem escopo |
| `EXPORT_MODE` | 02 | `MANUAL_TR` | `MANUAL_TR` |
| `CTMS_URI` | 02 | `uri` da key do cTMS | — (obrigatório) |
| `CTMS_QAS_NODE_ID` | 02 | `42` | — (obrigatório) |
| `CTMS_NAMED_USER` | 02 | `cpi-orquestracao` | `cpi-orquestracao` |
| `CPI_QAS_URL` | 02 | `https://<tenant>.it-cpi<nnn>.cfapps.<região>.hana.ondemand.com` | — |
| `CRED_CTMS` / `CRED_CPI_QAS` | 02 | `TRP_CTMS` / `TRP_CPI_QAS` | — |
| `TIMEOUT_WAITING_TR_MIN` | 02 | `60` | 60 |
| `TIMEOUT_IMPORT_MIN` | 02 | `15` | 15 |
| `TIMEOUT_DEPLOY_MIN` | 02 | `10` | 10 |
| `MAX_RETRIES` | 02 | `5` | 5 |
| `TEST_SYS_ID` | 99 | sys_id de um chamado de teste, ou `-` | — |

### 19.2 Bloco B — ajustes do cTMS (opcionais)

Só crie a propriedade no Content Modifier "Config" do `IF_TRP_02` se o Passo 2 mostrou algo diferente do padrão.

| Parâmetro | Padrão no script |
| --- | --- |
| `CTMS_QUEUE_PATH` | `/v2/nodes/#NODE_ID#/transportRequests` |
| `CTMS_IMPORT_PATH` | `/v2/nodes/#NODE_ID#/transportRequests/import` |
| `CTMS_ACTION_PATH` | `/v2/actions/#ACTION_ID#` |
| `CTMS_ACTIONID_JSONPATH` | `actionId` |
| `CTMS_ACTION_STATUS_JSONPATH` | `status` |
| `CTMS_ACTION_OK_VALUES` | `succeeded,su,warning,wa` |
| `CTMS_ACTION_FAILED_VALUES` | `error,er,fatal,fa` |
| `CTMS_IMPORTABLE_VALUES` | `initial,in,repeatable,re` |
| `CTMS_RUNNING_VALUES` (request em import) | `running,ru` |
| `CTMS_IMPORTED_VALUES` (request já importada) | `succeeded,su` |
| `ACTIVE_STATES` (iFlow 01) | `PENDING,EXPORTING,WAITING_TR,IMPORTING,DEPLOYING` |
| `SNOW_JWT_TTL_SECONDS` (iFlow 03) | `300` |

As comparações de valores não diferenciam maiúsculas de minúsculas. Os caminhos de campo usam ponto, por exemplo `action.id` ou `result.0.status`.

### 19.3 Bloco C — Etapa B (Content Agent)

Os exemplos são provisórios: troque pelos valores da documentação oficial (seção 18, ponto 1).

| Parâmetro | Exemplo provisório | O que é |
| --- | --- | --- |
| `CAS_DEV_URL` | URL da `key-cas-dev-trp` | Base da API do Content Agent |
| `CAS_EXPORT_PATH` | `/CONFIRMAR/export` | Caminho do POST que cria o export |
| `CAS_EXPORT_BODY_TEMPLATE` | `{"contentType":"CloudIntegration","resources":[{"id":"#PACKAGE_ID#"}],"description":"#TICKET#"}` | Corpo do POST. Marcadores: `#PACKAGE_ID#`, `#ARTIFACT_ID#`, `#VERSION#`, `#TICKET#` |
| `CAS_OPID_JSONPATH` | `id` | Onde vem o ID da operação na resposta |
| `CAS_STATUS_PATH` | `/CONFIRMAR/export/#OPERATION_ID#` | Caminho do GET de status |
| `CAS_STATUS_JSONPATH` | `status` | Campo de status |
| `CAS_DONE_VALUES` | `COMPLETED,SUCCEEDED,SUCCESS` | Valores de sucesso |
| `CAS_FAILED_VALUES` | `FAILED,ERROR` | Valores de falha |
| `CAS_TRID_JSONPATH` | `transportRequest.id` | Onde vem o ID da transport request |
| `TIMEOUT_EXPORT_MIN` | `15` | Prazo do estado `EXPORTING` |
| `CRED_CAS_DEV` | `TRP_CAS_DEV` | Credencial das chamadas G e H |

---

## 20. Anexo — scripts Groovy completos

Os mesmos arquivos de [`scripts/`](scripts/), reproduzidos aqui para copiar e colar.


### TRP_01_Orchestrator.groovy

```groovy
/*
 * TRP_01_Orchestrator.groovy
 * iFlow: IF_TRP_01_Orchestrator
 *
 * Funções usadas nos passos de Script do iFlow:
 *   prepareSearch   -> depois da chamada ao IF_TRP_03: guarda o token e monta a busca na Table API
 *   ticketsToLines  -> depois do GET na Table API (um chamado por linha, para o Iterating Splitter)
 *   lineToRequest   -> dentro do splitter, antes de chamar o IF_TRP_02
 */
import com.sap.gateway.ip.core.customdev.util.Message
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.Field

/** Campos do sc_req_item que o IF_TRP_02 precisa. */
@Field static final String DEFAULT_ACTIVE_STATES = "PENDING,EXPORTING,WAITING_TR,IMPORTING,DEPLOYING"
@Field static final List<String> FIELDS = [
    "sys_id", "number",
    "u_cpi_transport_status", "u_cpi_stage_since", "u_cpi_retries",
    "u_cpi_artifact_id", "u_cpi_artifact_version", "u_cpi_package_id",
    "u_cpi_operation_id", "u_cpi_tr_id", "u_cpi_action_id"
]

String prop(Message m, String k) {
    String v = ((m.getProperty(k) ?: "") as String).trim()
    return v == "-" ? "" : v
}

/**
 * Lê {"access_token": "..."} devolvido pelo IF_TRP_03, guarda numa propriedade
 * e monta os headers e a query (já codificada) do GET na Table API.
 */
def Message prepareSearch(Message message) {
    String body = message.getBody(String)
    def json = body ? new JsonSlurper().parseText(body) : [:]
    if (!json.access_token) {
        throw new IllegalStateException("IF_TRP_03 não devolveu access_token")
    }
    String token = json.access_token as String
    message.setProperty("SNOW_TOKEN", token)

    if (!prop(message, "SNOW_CAT_ITEM_SYSID")) {
        throw new IllegalStateException("Parâmetro SNOW_CAT_ITEM_SYSID vazio")
    }
    String states = prop(message, "ACTIVE_STATES") ?: DEFAULT_ACTIVE_STATES
    String encodedQuery = "cat_item=" + prop(message, "SNOW_CAT_ITEM_SYSID") +
                          "^u_cpi_transport_statusIN" + states.replace(" ", "") +
                          "^ORDERBYsys_created_on"
    String query = "sysparm_query=" + URLEncoder.encode(encodedQuery, "UTF-8") +
                   "&sysparm_fields=" + URLEncoder.encode(FIELDS.join(","), "UTF-8") +
                   "&sysparm_display_value=false" +
                   "&sysparm_exclude_reference_link=true" +
                   "&sysparm_limit=" + (prop(message, "SNOW_LIMIT") ?: "20")
    message.setProperty("SNOW_QUERY", query)

    message.setHeader("Authorization", "Bearer " + token)
    message.setHeader("Accept", "application/json")
    message.setBody("")
    return message
}

/**
 * Converte {"result":[{...},{...}]} em um JSON por linha.
 * O Iterating Splitter (Expression Type = Line Break) separa cada chamado.
 */
def Message ticketsToLines(Message message) {
    message.setHeader("Authorization", "")      // o token não segue adiante neste header
    String body = message.getBody(String)
    def json = body ? new JsonSlurper().parseText(body) : [:]
    List result = (json.result ?: []) as List

    // O ServiceNow IGNORA condições de filtro inválidas (coluna que não existe ou que o usuário não
    // pode ler) e devolve a tabela inteira. Se voltou chamado fora dos estados pedidos, é isso.
    List<String> states = (prop(message, "ACTIVE_STATES") ?: DEFAULT_ACTIVE_STATES).split(",").collect { it.trim() }
    List outside = result.findAll { t -> !(((t.u_cpi_transport_status ?: "") as String) in states) }
    if (outside) {
        throw new IllegalStateException("O ServiceNow devolveu " + outside.size() + " de " + result.size() +
            " chamados fora do filtro (ex.: " + (outside[0].number ?: "?") + "). O filtro foi ignorado: confira se a coluna " +
            "u_cpi_transport_status existe em " + "sc_req_item e se o usuário de integração pode lê-la. Nada foi processado.")
    }

    List<String> lines = result.collect { t ->
        Map ticket = [:]
        FIELDS.each { f -> ticket[f] = (t[f] ?: "") as String }
        JsonOutput.toJson(ticket)
    }

    message.setProperty("TICKET_COUNT", lines.size() as String)
    message.setBody(lines.join("\n"))
    return message
}

/**
 * Dentro do splitter: o corpo é um chamado (uma linha JSON).
 * Propriedades não atravessam o ProcessDirect, então o token vai num header
 * que o IF_TRP_02 lê e esvazia logo no primeiro passo.
 */
def Message lineToRequest(Message message) {
    String line = (message.getBody(String) ?: "").trim()
    if (!line) {
        throw new IllegalStateException("Linha vazia no splitter")
    }
    def ticket = new JsonSlurper().parseText(line)
    message.setHeader("SAP_ApplicationID", ticket.number as String)
    message.setHeader("X-Trp-Snow-Token", message.getProperty("SNOW_TOKEN") as String)
    message.setHeader("Content-Type", "application/json")
    message.setBody(line)
    return message
}
```

### TRP_02_AdvanceTicket.groovy

```groovy
/*
 * TRP_02_AdvanceTicket.groovy
 * iFlow: IF_TRP_02_AdvanceTicket
 *
 * Avança UM passo da máquina de estados de um chamado por ciclo:
 *   PENDING -> EXPORTING -> IMPORTING -> DEPLOYING -> SUCCESS
 *   (contingência sem API de export: PENDING -> WAITING_TR -> IMPORTING ...)
 *   qualquer estado -> FAILED (erro definitivo, prazo vencido ou tentativas esgotadas)
 *
 * Funções (uma por passo de Script no iFlow):
 *   prepare               -> lê o chamado, decide a rota (propriedade ROUTE) e já resolve
 *                            os casos que não precisam de chamada HTTP (prazo vencido,
 *                            campos inválidos, contingência PENDING -> WAITING_TR)
 *   buildExportRequest    -> corpo do POST de export no Content Agent
 *   handleExportResponse  -> PENDING -> EXPORTING
 *   evaluateExportStatus  -> export concluído? define NEXT = START_IMPORT
 *   findTrByTicket        -> contingência: acha na fila a request com o nº do chamado
 *   buildImportRequest    -> corpo do POST de import por ID no cTMS
 *   handleImportResponse  -> -> IMPORTING (grava ID da request e da ação)
 *   evaluateImport        -> import concluído? define NEXT = START_DEPLOY
 *   handleDeployResponse  -> -> DEPLOYING
 *   evaluateDeploy        -> STARTED, implantado depois do pedido e na versão pedida -> SUCCESS
 *   buildUpdateRequest    -> monta o pedido para o IF_TRP_04 (ServiceNow)
 *   handleError           -> Exception Subprocess: erro transitório ou definitivo
 *                            (o erro também aparece no monitor, em Custom Headers > TRP_Error)
 *
 * Valores de ROUTE: EXPORT, CHECK_EXPORT, CHECK_QUEUE, CHECK_IMPORT, CHECK_DEPLOY, DONE
 * Valores de NEXT : START_IMPORT, START_DEPLOY ou vazio
 *
 * Os endpoints do Content Agent e do cTMS vêm de parâmetros externalizados,
 * porque precisam ser confirmados no tenant (ver IMPLEMENTACAO-CPI.md).
 */
import com.sap.gateway.ip.core.customdev.util.Message
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.Field

import java.text.SimpleDateFormat

@Field static final String SNOW_DATE_FORMAT = "yyyy-MM-dd HH:mm:ss"
@Field static final List<Integer> TRANSIENT_HTTP = [408, 425, 429, 500, 502, 503, 504]
/** Tolerância entre o relógio do orquestrador e o do tenant de QAS. */
@Field static final long CLOCK_SKEW_MS = 120000L
/** Caminhos padrão da API v2 do cTMS (podem ser trocados por parâmetro). */
@Field static final String DEFAULT_QUEUE_PATH = "/v2/nodes/#NODE_ID#/transportRequests"
@Field static final String DEFAULT_IMPORT_PATH = "/v2/nodes/#NODE_ID#/transportRequests/import"
@Field static final String DEFAULT_ACTION_PATH = "/v2/actions/#ACTION_ID#"
/** Marca do erro "o ServiceNow não gravou" lançado pelo IF_TRP_04 (TRP_04_SnowUpdate.groovy). */
@Field static final String WRITE_MARKER = "[TRP-GRAVACAO]"
/** Respostas do cTMS ao import que podem ser só corrida entre dois ciclos (request já em import). */
@Field static final List<Integer> IMPORT_REJECTED_HTTP = [400, 404, 409, 412, 422, 423]

// ---------------------------------------------------------------- utilitários

/** Lê uma propriedade. O valor "-" conta como vazio (para parâmetros opcionais). */
String prop(Message m, String k) {
    String v = ((m.getProperty(k) ?: "") as String).trim()
    return v == "-" ? "" : v
}

String nowUtc() {
    def f = new SimpleDateFormat(SNOW_DATE_FORMAT)
    f.setTimeZone(TimeZone.getTimeZone("UTC"))
    return f.format(new Date())
}

Date parseUtc(String s) {
    def f = new SimpleDateFormat(SNOW_DATE_FORMAT)
    f.setTimeZone(TimeZone.getTimeZone("UTC"))
    return f.parse(s)
}

def bodyJson(Message m) {
    String b = m.getBody(String)
    return b ? new JsonSlurper().parseText(b) : [:]
}

/** Lê um caminho simples com pontos, ex.: "d.Status" ou "result.0.id". */
def jsonPath(Object obj, String path) {
    if (!path) return null
    return path.split("\\.").inject(obj) { acc, k ->
        if (acc == null) return null
        if (acc instanceof List && k.isInteger()) return acc[k as int]
        if (acc instanceof Map) return acc[k]
        return null
    }
}

/** Troca os marcadores #NOME# pelos valores do chamado. */
String fill(String template, Message m) {
    return (template ?: "")
        .replace("#PACKAGE_ID#", prop(m, "PACKAGE_ID"))
        .replace("#ARTIFACT_ID#", prop(m, "ARTIFACT_ID"))
        .replace("#VERSION#", prop(m, "VERSION"))
        .replace("#TICKET#", prop(m, "NUMBER"))
        .replace("#OPERATION_ID#", prop(m, "OPERATION_ID"))
        .replace("#NODE_ID#", prop(m, "CTMS_QAS_NODE_ID"))
        .replace("#ACTION_ID#", prop(m, "ACTION_ID"))
        .replace("#TR_ID#", prop(m, "TR_ID"))
}

/** Lista de valores separados por vírgula, em maiúsculas; usa o padrão se o parâmetro estiver vazio. */
List<String> csvUpper(String s, String fallback) {
    ((s ?: "") ?: fallback).split(",").collect { it.trim().toUpperCase() }.findAll { it }
}

/** Grava um Custom Header no log da mensagem (ignora se não estiver rodando no CPI). */
void logHeader(Message m, String name, String value) {
    try {
        def log = messageLogFactory.getMessageLog(m)
        if (log != null) log.addCustomHeaderProperty(name, (value ?: "").take(200))
    } catch (MissingPropertyException ignored) {
        // fora do CPI (testes locais) não existe messageLogFactory
    }
}

void setJsonHeaders(Message m) {
    m.setHeader("Content-Type", "application/json")
    m.setHeader("Accept", "application/json")
}

/** Marca que o chamado precisa ser atualizado no ServiceNow. */
void setUpdate(Message m, Map fields, String note) {
    m.setProperty("UPD_FIELDS", JsonOutput.toJson(fields))
    m.setProperty("UPD_NOTE", note ?: "")
    m.setProperty("UPDATE", "true")
}

/** Muda de estado: grava o início do novo estado e zera as tentativas. */
void moveTo(Message m, String state, Map extra, String note) {
    Map f = [u_cpi_transport_status: state, u_cpi_stage_since: nowUtc(), u_cpi_retries: "0"]
    if (extra) f.putAll(extra)
    setUpdate(m, f, note)
}

void fail(Message m, String reason) {
    String r = reason ?: "Falha sem detalhe"
    moveTo(m, "FAILED", [u_cpi_message: r.take(250)], "FALHA: " + r)
    m.setProperty("NEXT", "")
}

/** DeployedOn do OData: "/Date(1767225600000)/" (JSON) ou "2026-02-25T09:46:43.180" (UTC). */
Long parseDeployedOn(Object value) {
    String s = (value ?: "") as String
    def m = (s =~ /\/Date\((\d+)([+-]\d+)?\)\//)
    if (m.find()) return m.group(1) as Long
    if (s ==~ /\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}.*/) {
        try {
            def f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss")
            f.setTimeZone(TimeZone.getTimeZone("UTC"))
            return f.parse(s.take(19)).getTime()
        } catch (Exception ignored) {
            return null
        }
    }
    return null
}

// ---------------------------------------------------------------- prepare

def Message prepare(Message message) {
    def t = bodyJson(message)
    Map map = [
        SYS_ID      : "sys_id",
        NUMBER      : "number",
        STATE       : "u_cpi_transport_status",
        STAGE_SINCE : "u_cpi_stage_since",
        RETRIES     : "u_cpi_retries",
        ARTIFACT_ID : "u_cpi_artifact_id",
        VERSION     : "u_cpi_artifact_version",
        PACKAGE_ID  : "u_cpi_package_id",
        OPERATION_ID: "u_cpi_operation_id",
        TR_ID       : "u_cpi_tr_id",
        ACTION_ID   : "u_cpi_action_id"
    ]
    map.each { k, f -> message.setProperty(k, ((t[f] ?: "") as String).trim()) }
    if (!prop(message, "RETRIES")) message.setProperty("RETRIES", "0")
    message.setProperty("UPDATE", "false")
    message.setProperty("NEXT", "")

    // Token: chega em header e vira propriedade; o header é esvaziado
    String token = message.getHeaders().get("X-Trp-Snow-Token") as String
    if (!token) throw new IllegalStateException("Token do ServiceNow ausente (header X-Trp-Snow-Token)")
    message.setProperty("SNOW_TOKEN", token)
    message.setHeader("X-Trp-Snow-Token", "")
    message.setHeader("SAP_ApplicationID", prop(message, "NUMBER"))
    message.setHeader("Accept", "application/json")
    message.setBody("")

    String state = prop(message, "STATE")
    boolean active = state in ["PENDING", "EXPORTING", "WAITING_TR", "IMPORTING", "DEPLOYING"]

    // Configuração mínima (só cobra quando há algo a fazer)
    if (active) {
        ["CTMS_URI", "CTMS_QAS_NODE_ID"].each { k ->
            if (!prop(message, k)) throw new IllegalStateException("Parâmetro " + k + " vazio no IF_TRP_02")
        }
    }

    // URLs resolvidas (endpoints configuráveis, com padrão quando o parâmetro não existe)
    String base = prop(message, "CTMS_URI")
    message.setProperty("CAS_EXPORT_URL", prop(message, "CAS_DEV_URL") + fill(prop(message, "CAS_EXPORT_PATH"), message))
    message.setProperty("CAS_STATUS_URL", prop(message, "CAS_DEV_URL") + fill(prop(message, "CAS_STATUS_PATH"), message))
    message.setProperty("CTMS_QUEUE_URL", base + fill(prop(message, "CTMS_QUEUE_PATH") ?: DEFAULT_QUEUE_PATH, message))
    message.setProperty("CTMS_ACTION_URL", base + fill(prop(message, "CTMS_ACTION_PATH") ?: DEFAULT_ACTION_PATH, message))

    // 1. Prazo do estado atual
    Map<String, String> limits = [
        EXPORTING : prop(message, "TIMEOUT_EXPORT_MIN") ?: "15",
        WAITING_TR: prop(message, "TIMEOUT_WAITING_TR_MIN") ?: "60",
        IMPORTING : prop(message, "TIMEOUT_IMPORT_MIN") ?: "15",
        DEPLOYING : prop(message, "TIMEOUT_DEPLOY_MIN") ?: "10"
    ]
    if (limits[state] && prop(message, "STAGE_SINCE")) {
        long elapsedMin = ((System.currentTimeMillis() - parseUtc(prop(message, "STAGE_SINCE")).getTime()) / 60000L) as long
        message.setProperty("STAGE_ELAPSED_MIN", elapsedMin as String)
        if (elapsedMin > (limits[state] as long)) {
            fail(message, "Prazo do estado " + state + " vencido (" + elapsedMin + " min; limite " + limits[state] + " min).")
            message.setProperty("ROUTE", "DONE")
            return message
        }
    }

    // 2. Primeiro passo: valida os campos e escolhe o modo de export
    if (state == "PENDING") {
        List<String> errors = []
        if (!(prop(message, "ARTIFACT_ID") ==~ /[A-Za-z0-9_.\-]+/)) errors << "ID técnico do iFlow inválido"
        if (!(prop(message, "VERSION") ==~ /(\d+\.\d+\.\d+|active)/)) errors << "Versão inválida (ex.: 1.0.3)"
        if (!(prop(message, "PACKAGE_ID") ==~ /[A-Za-z0-9_.\-]+/)) errors << "ID técnico do pacote inválido"
        if (errors) {
            fail(message, "Campos inválidos no chamado: " + errors.join("; "))
            message.setProperty("ROUTE", "DONE")
        } else if (prop(message, "EXPORT_MODE").toUpperCase() == "API") {
            message.setProperty("ROUTE", "EXPORT")
        } else {
            moveTo(message, "WAITING_TR", [:],
                   "Aguardando a transport request. Clique em Transport no pacote do CPI DEV e escreva " +
                   prop(message, "NUMBER") + " na descrição.")
            message.setProperty("ROUTE", "DONE")
        }
        return message
    }

    // 3. Demais estados
    Map<String, String> routes = [
        EXPORTING : "CHECK_EXPORT",
        WAITING_TR: "CHECK_QUEUE",
        IMPORTING : "CHECK_IMPORT",
        DEPLOYING : "CHECK_DEPLOY"
    ]
    message.setProperty("ROUTE", routes[state] ?: "DONE")
    return message
}

// ---------------------------------------------------------------- export (Content Agent)

def Message buildExportRequest(Message message) {
    setJsonHeaders(message)
    message.setBody(fill(prop(message, "CAS_EXPORT_BODY_TEMPLATE"), message))
    return message
}

def Message handleExportResponse(Message message) {
    def opId = jsonPath(bodyJson(message), prop(message, "CAS_OPID_JSONPATH") ?: "id")
    message.setBody("")
    if (!opId) {
        fail(message, "Content Agent não devolveu o ID da operação de export")
        return message
    }
    moveTo(message, "EXPORTING", [u_cpi_operation_id: opId as String],
           "Export criado no Content Agent (operação " + opId + ").")
    return message
}

def Message evaluateExportStatus(Message message) {
    def body = bodyJson(message)
    String status = ((jsonPath(body, prop(message, "CAS_STATUS_JSONPATH") ?: "status") ?: "") as String).toUpperCase()
    message.setBody("")
    if (status in csvUpper(prop(message, "CAS_DONE_VALUES"), "COMPLETED,SUCCEEDED,SUCCESS")) {
        def trId = jsonPath(body, prop(message, "CAS_TRID_JSONPATH"))
        if (!trId) {
            fail(message, "Export concluído sem ID de transport request na resposta")
            return message
        }
        message.setProperty("TR_ID", trId as String)
        message.setProperty("NEXT", "START_IMPORT")
    } else if (status in csvUpper(prop(message, "CAS_FAILED_VALUES"), "FAILED,ERROR")) {
        fail(message, "Export falhou no Content Agent (status " + status + ")")
    }
    // qualquer outro status: ainda em andamento, nada a fazer neste ciclo
    return message
}

// ---------------------------------------------------------------- contingência (sem API de export)

/** O número do chamado aparece na descrição como palavra inteira? (RITM0010011 não casa com RITM00100119) */
boolean mentions(Object description, String ticket) {
    if (!ticket) return false
    String pattern = "(?<![A-Za-z0-9])" + java.util.regex.Pattern.quote(ticket) + "(?![A-Za-z0-9])"
    return (((description ?: "") as String) =~ pattern).find()
}

def Message findTrByTicket(Message message) {
    def body = bodyJson(message)
    List list = (body instanceof List) ? body : ((body.transportRequests ?: []) as List)
    String ticket = prop(message, "NUMBER")
    List<String> importable = csvUpper(prop(message, "CTMS_IMPORTABLE_VALUES"), "INITIAL,IN,REPEATABLE,RE")
    List<String> running = csvUpper(prop(message, "CTMS_RUNNING_VALUES"), "RUNNING,RU")
    List<String> imported = csvUpper(prop(message, "CTMS_IMPORTED_VALUES"), "SUCCEEDED,SU")
    List<String> broken = csvUpper(prop(message, "CTMS_ACTION_FAILED_VALUES"), "ERROR,ER,FATAL,FA")

    Closure<String> statusOf = { tr -> ((tr.status ?: "") as String).toUpperCase() }
    List mine = list.findAll { tr -> mentions(tr.description, ticket) }
    List ready = mine.findAll { tr -> !statusOf(tr) || statusOf(tr) in importable }
    message.setBody("")

    if (ready.size() > 1) {
        fail(message, "Há mais de uma request importável na fila com " + ticket + " na descrição")
    } else if (ready.size() == 1) {
        message.setProperty("TR_ID", ready[0].id as String)
        message.setProperty("NEXT", "START_IMPORT")
    } else if (mine.any { tr -> statusOf(tr) in running }) {
        // import já em andamento (disparado por outro ciclo ou por alguém na tela do cTMS): espera
    } else if (mine.any { tr -> statusOf(tr) in imported }) {
        // A request já foi importada, mas o chamado não ficou sabendo (por exemplo, o ServiceNow
        // estava fora do ar na hora de gravar). Segue direto para o deploy.
        def done = mine.findAll { tr -> statusOf(tr) in imported }.max { tr -> (tr.id as String).isLong() ? (tr.id as long) : 0L }
        message.setProperty("TR_ID", done.id as String)
        message.setProperty("TR_RECOVERED", "true")
        message.setProperty("NEXT", "START_DEPLOY")
    } else if (mine.any { tr -> statusOf(tr) in broken }) {
        def bad = mine.find { tr -> statusOf(tr) in broken }
        fail(message, "A request " + bad.id + " do chamado está com status " + statusOf(bad) + " na fila do nó de QAS no cTMS.")
    }
    // nenhuma: o dev ainda não clicou em Transport; espera o próximo ciclo
    return message
}

// ---------------------------------------------------------------- import (cTMS)

def Message buildImportRequest(Message message) {
    String tr = prop(message, "TR_ID")
    def trValue = tr.isLong() ? (tr as long) : tr
    setJsonHeaders(message)
    message.setProperty("CTMS_IMPORT_URL", prop(message, "CTMS_URI") + fill(prop(message, "CTMS_IMPORT_PATH") ?: DEFAULT_IMPORT_PATH, message))
    message.setBody(JsonOutput.toJson([
        namedUser        : prop(message, "CTMS_NAMED_USER") ?: "cpi-orquestracao",
        transportRequests: [trValue]
    ]))
    return message
}

def Message handleImportResponse(Message message) {
    def actionId = jsonPath(bodyJson(message), prop(message, "CTMS_ACTIONID_JSONPATH") ?: "actionId")
    message.setBody("")
    if (!actionId) {
        fail(message, "cTMS não devolveu o ID da ação de import")
        return message
    }
    moveTo(message, "IMPORTING",
           [u_cpi_tr_id: prop(message, "TR_ID"), u_cpi_action_id: actionId as String],
           "Import da request " + prop(message, "TR_ID") + " iniciado no nó de QAS (ação " + actionId + ").")
    message.setProperty("NEXT", "")
    return message
}

def Message evaluateImport(Message message) {
    String status = ((jsonPath(bodyJson(message), prop(message, "CTMS_ACTION_STATUS_JSONPATH") ?: "status") ?: "") as String).toUpperCase()
    message.setBody("")
    if (status in csvUpper(prop(message, "CTMS_ACTION_OK_VALUES"), "SUCCEEDED,SU,WARNING,WA")) {
        message.setProperty("NEXT", "START_DEPLOY")
    } else if (status in csvUpper(prop(message, "CTMS_ACTION_FAILED_VALUES"), "ERROR,ER,FATAL,FA")) {
        fail(message, "Import da request " + prop(message, "TR_ID") + " terminou em " + status +
                      " no cTMS. Veja o log da ação " + prop(message, "ACTION_ID") + ".")
    }
    return message
}

// ---------------------------------------------------------------- deploy (CPI QAS)

def Message handleDeployResponse(Message message) {
    message.setBody("")
    Map extra = prop(message, "TR_ID") ? [u_cpi_tr_id: prop(message, "TR_ID")] : [:]
    String recovered = prop(message, "TR_RECOVERED") == "true" ?
        "A request " + prop(message, "TR_ID") + " já estava importada no cTMS. " : ""
    moveTo(message, "DEPLOYING", extra, recovered + "Deploy do iFlow " + prop(message, "ARTIFACT_ID") + " disparado em QAS.")
    message.setProperty("NEXT", "")
    return message
}

def Message evaluateDeploy(Message message) {
    String raw = message.getBody(String) ?: ""
    def json = null
    try {
        json = raw.trim() ? new JsonSlurper().parseText(raw) : null
    } catch (Exception ignored) {
        // corpo que não é JSON (por exemplo XML, quando o header Accept não chegou)
    }
    int code = (message.getHeaders().get("CamelHttpResponseCode") ?: 0) as int
    if (code == 0) {
        // O header com o código HTTP não está disponível: deduz pela resposta.
        String err = (json instanceof Map && json.error) ? JsonOutput.toJson(json.error).toLowerCase() : ""
        if (json instanceof Map && json.d instanceof Map) code = 200
        else if (err.contains("not found") || err.contains("notfound")) code = 404
    }
    if (code == 404) {               // ainda não apareceu no runtime
        message.setBody("")
        return message
    }
    if (code == 200 && !(json instanceof Map && json.d instanceof Map)) {
        throw new IllegalStateException("Consulta do runtime em QAS não devolveu JSON (confira o header Accept: application/json " +
                                        "no Request Headers da chamada): " + raw.take(120))
    }
    if (code != 200) {
        // sem código conhecido, trata como passageiro: as tentativas contam e o motivo fica registrado
        String prefix = (code == 0 || code in TRANSIENT_HTTP) ? "TRANSIENT: " : ""
        throw new IllegalStateException(prefix + "Consulta do runtime em QAS retornou HTTP " + (code ?: "desconhecido") +
                                        (raw ? ": " + raw.take(200) : ""))
    }
    def d = json.d
    String status = ((d?.Status ?: "") as String).toUpperCase()
    String runtimeVersion = (d?.Version ?: "") as String
    String wanted = prop(message, "VERSION")
    String artifact = prop(message, "ARTIFACT_ID")
    message.setBody("")

    // O que está no runtime é o deploy deste chamado, ou um deploy antigo?
    Long deployedOn = parseDeployedOn(d?.DeployedOn)
    Long since = prop(message, "STAGE_SINCE") ? parseUtc(prop(message, "STAGE_SINCE")).getTime() : null
    boolean fresh = (deployedOn == null || since == null) ? true : (deployedOn >= since - CLOCK_SKEW_MS)

    if (status in ["ERROR", "FAILED"]) {
        fail(message, "iFlow " + artifact + " ficou em " + status + " em QAS. Veja Monitor > Manage Integration Content no CPI QAS.")
    } else if (status == "STARTED" && fresh) {
        if (wanted == "active" || runtimeVersion == wanted) {
            moveTo(message, "SUCCESS", [u_cpi_message: "STARTED " + runtimeVersion],
                   "iFlow " + artifact + " versão " + runtimeVersion + " em QAS com status STARTED " +
                   "(transport request " + prop(message, "TR_ID") + ").")
        } else {
            fail(message, "iFlow " + artifact + " subiu em QAS na versão " + runtimeVersion + ", mas o chamado pediu " + wanted +
                          ". O transporte leva a versão que estava salva em DEV no momento do export.")
        }
    }
    // STARTING, ou STARTED de um deploy antigo: espera o próximo ciclo
    return message
}

// ---------------------------------------------------------------- atualização do chamado

def Message buildUpdateRequest(Message message) {
    def fields = new JsonSlurper().parseText(prop(message, "UPD_FIELDS") ?: "{}")
    String note = prop(message, "UPD_NOTE")
    if (note) fields.work_notes = "[Transporte CPI] " + note
    message.setHeader("X-Trp-Snow-Token", prop(message, "SNOW_TOKEN"))
    message.setHeader("Content-Type", "application/json")
    message.setBody(JsonOutput.toJson([sys_id: prop(message, "SYS_ID"), fields: fields]))
    return message
}

// ---------------------------------------------------------------- erros

/** A exceção e as causas dela (o erro de um iFlow chamado pode chegar embrulhado em outra exceção). */
List causeChain(Object ex) {
    List chain = []
    def cur = ex
    while (cur != null && chain.size() < 10 && !chain.any { it.is(cur) }) {
        chain << cur
        cur = cur.getCause()
    }
    return chain
}

def Message handleError(Message message) {
    def ex = message.getProperty("CamelExceptionCaught")
    List chain = causeChain(ex)
    List<String> messages = chain.collect { (it.getMessage() ?: "") as String }.findAll { it }
    int code = 0
    String detail = messages ? messages[0] : (ex != null ? ex.toString() : "erro desconhecido")

    // Erro HTTP do adapter (a classe muda entre versões do runtime; testa pelo método)
    def httpEx = chain.find { it.metaClass.respondsTo(it, "getStatusCode") }
    if (httpEx != null) {
        code = httpEx.getStatusCode() as int
        String resp = httpEx.metaClass.respondsTo(httpEx, "getResponseBody") ? (httpEx.getResponseBody() ?: "") as String : ""
        detail = "HTTP " + code + ": " + resp.take(300)
    }

    logHeader(message, "TRP_Error", (prop(message, "NUMBER") ?: "?") + " / " + (prop(message, "STATE") ?: "?") + ": " + detail)

    if (!prop(message, "SYS_ID") || !prop(message, "SNOW_TOKEN")) {
        // Falhou antes de ler o chamado: não há o que atualizar no ServiceNow
        throw new IllegalStateException("Erro antes de identificar o chamado: " + detail)
    }
    String notSaved = messages.find { it.contains(WRITE_MARKER) }
    if (notSaved) {
        // O ServiceNow não está gravando os campos: tentar de novo só encheria o chamado de work notes.
        throw new IllegalStateException(notSaved.substring(notSaved.indexOf(WRITE_MARKER)))
    }

    boolean isTransient = (code in TRANSIENT_HTTP) ||
                          messages.any { it.startsWith("TRANSIENT") } ||
                          chain.any { it instanceof java.net.SocketTimeoutException || it instanceof java.net.ConnectException ||
                                      it instanceof java.util.concurrent.TimeoutException } ||
                          (code == 0 && chain.any { it instanceof IOException })
    // Import recusado: pode ser outro ciclo que chegou antes. O próximo ciclo confere a fila de novo.
    boolean importRejected = prop(message, "NEXT") == "START_IMPORT" && (code in IMPORT_REJECTED_HTTP)

    int retries = (prop(message, "RETRIES") ?: "0") as int
    int maxRetries = (prop(message, "MAX_RETRIES") ?: "5") as int

    if ((isTransient || importRejected) && retries + 1 < maxRetries) {
        String what = importRejected ? "O cTMS recusou o import da request " + prop(message, "TR_ID") + "; a fila será conferida de novo"
                                     : "Erro transitório no estado " + prop(message, "STATE")
        setUpdate(message, [u_cpi_retries: (retries + 1) as String],
                  what + " (tentativa " + (retries + 1) + " de " + maxRetries + "): " + detail)
    } else {
        fail(message, "Erro no estado " + prop(message, "STATE") + ": " + detail)
    }
    return buildUpdateRequest(message)
}
```

### TRP_03_SnowToken.groovy

```groovy
/*
 * TRP_03_SnowToken.groovy
 * iFlow: IF_TRP_03_SnowToken
 *
 * OAuth 2.0 com JWT assinado por certificado (grant jwt-bearer), um dos estilos
 * de conexão do Hub de Arquitetura para o ServiceNow.
 *
 * Funções:
 *   buildTokenRequest  -> monta e assina o JWT e o corpo do POST de token
 *   parseTokenResponse -> devolve só {"access_token": "...", "expires_in": n}
 *
 * Material de segurança usado (nomes vêm de parâmetros externalizados):
 *   SNOW_CLIENT_CREDENTIAL : User Credentials (usuário = client_id, senha = client_secret)
 *   SNOW_JWT_KEY_ALIAS     : alias do par de chaves no Keystore do tenant (RSA)
 *
 * Nada deste script grava token, segredo ou chave em log.
 */
import com.sap.gateway.ip.core.customdev.util.Message
import com.sap.it.api.ITApiFactory
import com.sap.it.api.keystore.KeystoreService
import com.sap.it.api.securestore.SecureStoreService
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

import java.nio.charset.StandardCharsets
import java.security.PrivateKey
import java.security.Signature

/** Lê uma propriedade. O valor "-" conta como vazio (para parâmetros opcionais). */
String prop(Message m, String k) {
    String v = ((m.getProperty(k) ?: "") as String).trim()
    return v == "-" ? "" : v
}

String b64url(byte[] bytes) {
    Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

String enc(String s) { URLEncoder.encode(s ?: "", "UTF-8") }

def Message buildTokenRequest(Message message) {
    // 1. client_id e client_secret do Security Material
    def secureStore = ITApiFactory.getService(SecureStoreService.class, null)
    def cred = secureStore.getUserCredential(prop(message, "SNOW_CLIENT_CREDENTIAL"))
    if (cred == null) {
        throw new IllegalStateException("Credencial " + prop(message, "SNOW_CLIENT_CREDENTIAL") + " não encontrada no Security Material")
    }
    String clientId = cred.getUsername()
    String clientSecret = new String(cred.getPassword())

    // 2. Chave privada do Keystore
    def keystore = ITApiFactory.getService(KeystoreService.class, null)
    def key = keystore.getKey(prop(message, "SNOW_JWT_KEY_ALIAS"))
    if (!(key instanceof PrivateKey)) {
        throw new IllegalStateException("Alias " + prop(message, "SNOW_JWT_KEY_ALIAS") + " não é uma chave privada no Keystore")
    }

    // 3. JWT (RS256)
    long now = (long) (System.currentTimeMillis() / 1000L)
    long ttl = (prop(message, "SNOW_JWT_TTL_SECONDS") ?: "300") as long
    String aud = prop(message, "SNOW_JWT_AUD") ?: clientId
    String iss = prop(message, "SNOW_JWT_ISS") ?: clientId

    Map header = [alg: "RS256", typ: "JWT", kid: prop(message, "SNOW_JWT_KID")]
    Map claims = [
        iss: iss,
        aud: aud,
        sub: prop(message, "SNOW_JWT_SUB"),
        iat: now,
        nbf: now - 30,
        exp: now + ttl,
        jti: UUID.randomUUID().toString()
    ]

    String signingInput = b64url(JsonOutput.toJson(header).getBytes(StandardCharsets.UTF_8)) + "." +
                          b64url(JsonOutput.toJson(claims).getBytes(StandardCharsets.UTF_8))

    Signature signer = Signature.getInstance("SHA256withRSA")
    signer.initSign((PrivateKey) key)
    signer.update(signingInput.getBytes(StandardCharsets.US_ASCII))
    String jwt = signingInput + "." + b64url(signer.sign())

    // 4. Corpo do POST (application/x-www-form-urlencoded)
    //    SNOW_ASSERTION_PARAM = "assertion" (jwt-bearer do ServiceNow) ou "client_assertion",
    //    conforme o estilo escolhido no Hub de Arquitetura.
    String assertionParam = prop(message, "SNOW_ASSERTION_PARAM") ?: "assertion"
    String body = "grant_type=" + enc("urn:ietf:params:oauth:grant-type:jwt-bearer") +
                  "&client_id=" + enc(clientId) +
                  "&client_secret=" + enc(clientSecret) +
                  "&" + assertionParam + "=" + enc(jwt)
    if (prop(message, "SNOW_TOKEN_SCOPE")) {
        body += "&scope=" + enc(prop(message, "SNOW_TOKEN_SCOPE"))
    }

    message.setHeader("Content-Type", "application/x-www-form-urlencoded")
    message.setHeader("Accept", "application/json")
    message.setBody(body)
    return message
}

def Message parseTokenResponse(Message message) {
    String body = message.getBody(String)
    def json = body ? new JsonSlurper().parseText(body) : [:]
    if (!json.access_token) {
        String err = ((json.error ?: "") + " " + (json.error_description ?: "")).trim()
        throw new IllegalStateException("Resposta de token sem access_token" + (err ? ": " + err : ""))
    }
    message.setBody(JsonOutput.toJson([access_token: json.access_token, expires_in: json.expires_in]))
    message.setHeader("Content-Type", "application/json")
    return message
}
```

### TRP_04_SnowUpdate.groovy

```groovy
/*
 * TRP_04_SnowUpdate.groovy
 * iFlow: IF_TRP_04_SnowUpdate
 *
 * Entrada (ProcessDirect /trp/snow-update):
 *   header X-Trp-Snow-Token : access token do ServiceNow
 *   corpo                   : {"sys_id": "...", "fields": {"u_cpi_transport_status": "...", "work_notes": "..."}}
 *
 * Funções:
 *   prepareUpdate -> prepara o PATCH na Table API
 *   finishUpdate  -> depois do PATCH: limpa o corpo e o header Authorization
 */
import com.sap.gateway.ip.core.customdev.util.Message
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.Field

/** Marca do erro "o ServiceNow não gravou"; o IF_TRP_02 reconhece e não tenta gravar de novo. */
@Field static final String WRITE_MARKER = "[TRP-GRAVACAO]"
@Field static final List<String> JOURNAL_FIELDS = ["work_notes", "comments"]

def Message prepareUpdate(Message message) {
    String body = message.getBody(String)
    def req = body ? new JsonSlurper().parseText(body) : [:]
    if (!req.sys_id) {
        throw new IllegalStateException("sys_id ausente no pedido de atualização")
    }
    String token = message.getHeaders().get("X-Trp-Snow-Token") as String
    if (!token) {
        throw new IllegalStateException("Token do ServiceNow ausente")
    }

    message.setProperty("SYS_ID", req.sys_id as String)
    // guarda o que foi enviado para conferir, na resposta, se o ServiceNow gravou mesmo
    message.setProperty("UPD_SENT", JsonOutput.toJson(req.fields ?: [:]))
    message.setHeader("X-Trp-Snow-Token", "")
    message.setHeader("Authorization", "Bearer " + token)
    message.setHeader("Content-Type", "application/json")
    message.setHeader("Accept", "application/json")
    message.setBody(JsonOutput.toJson(req.fields ?: [:]))
    return message
}

/**
 * O ServiceNow responde 200 mesmo quando ignora um campo (coluna que não existe ou usuário sem
 * permissão de escrita). Por isso a resposta é conferida: campo enviado tem que voltar preenchido,
 * e o estado tem que voltar igual. Sem isso o chamado ficaria parado para sempre, sem erro nenhum.
 */
def Message finishUpdate(Message message) {
    message.setHeader("Authorization", "")
    String body = message.getBody(String)
    message.setBody("")

    def sent = new JsonSlurper().parseText(((message.getProperty("UPD_SENT") ?: "{}") as String))
    def result = null
    try {
        result = body ? new JsonSlurper().parseText(body)?.result : null
    } catch (Exception ignored) {
        // resposta que não é JSON: não há como conferir
    }
    if (!(result instanceof Map) || !(sent instanceof Map)) return message

    List<String> notSaved = []
    sent.each { String field, Object value ->
        if (field in JOURNAL_FIELDS) return                   // campos de diário voltam sempre vazios
        String wanted = (value == null ? "" : value as String).trim()
        if (!wanted) return
        Object raw = result[field]
        if (raw instanceof Map) raw = raw.value               // campo de referência
        String got = (raw == null ? "" : raw as String).trim()
        boolean ok = (field == "u_cpi_transport_status") ? (got == wanted) : (got != "")
        if (!ok) notSaved << (field + " (enviado '" + wanted.take(40) + "', ficou '" + got.take(40) + "')")
    }
    if (notSaved) {
        throw new IllegalStateException(WRITE_MARKER + " O ServiceNow respondeu 200 mas não gravou: " + notSaved.join(", ") +
            ". Confira se a coluna existe em sc_req_item e se o usuário de integração tem permissão de escrita (ACL) nela.")
    }
    return message
}
```

### TRP_99_ConnectionTest.groovy

```groovy
/*
 * TRP_99_ConnectionTest.groovy
 * iFlow: IF_TRP_99_ConnectionTest (temporário, só para testes)
 *
 * Testa o IF_TRP_03 (token do ServiceNow) e, se TEST_SYS_ID estiver preenchido,
 * o IF_TRP_04 (grava uma work note e o campo u_cpi_message num chamado de teste).
 *
 * Funções:
 *   afterToken  -> depois da chamada ao /trp/snow-token
 *   afterUpdate -> depois da chamada ao /trp/snow-update
 *
 * O resultado aparece no monitor de mensagens, em Custom Headers (TRP_TokenTest e
 * TRP_UpdateTest), sem precisar ligar o trace. O token nunca é gravado.
 */
import com.sap.gateway.ip.core.customdev.util.Message
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

String prop(Message m, String k) {
    String v = ((m.getProperty(k) ?: "") as String).trim()
    return v == "-" ? "" : v
}

/** Grava um Custom Header no log da mensagem (ignora se não estiver rodando no CPI). */
void logHeader(Message m, String name, String value) {
    try {
        def log = messageLogFactory.getMessageLog(m)
        if (log != null) log.addCustomHeaderProperty(name, value)
    } catch (MissingPropertyException ignored) {
        // fora do CPI (testes locais) não existe messageLogFactory
    }
}

def Message afterToken(Message message) {
    String body = message.getBody(String)
    def json = body ? new JsonSlurper().parseText(body) : [:]
    if (!json.access_token) {
        throw new IllegalStateException("IF_TRP_03 não devolveu access_token")
    }
    String token = json.access_token as String
    logHeader(message, "TRP_TokenTest", "OK - token com " + token.length() + " caracteres, expira em " + json.expires_in + " s")

    String sysId = prop(message, "TEST_SYS_ID")
    message.setProperty("DO_UPDATE", sysId ? "true" : "false")
    if (sysId) {
        message.setHeader("X-Trp-Snow-Token", token)
        message.setHeader("Content-Type", "application/json")
        message.setBody(JsonOutput.toJson([
            sys_id: sysId,
            // u_cpi_message vai junto para provar que as colunas u_cpi_* existem e aceitam gravação
            fields: [work_notes   : "[Transporte CPI] Teste de conexão do CPI. Pode ignorar.",
                     u_cpi_message: "Teste de conexão do CPI"]
        ]))
    } else {
        message.setBody("")
    }
    return message
}

def Message afterUpdate(Message message) {
    message.setHeader("X-Trp-Snow-Token", "")
    logHeader(message, "TRP_UpdateTest", "OK - work note e campo u_cpi_message gravados no chamado de teste")
    message.setBody("")
    return message
}
```
