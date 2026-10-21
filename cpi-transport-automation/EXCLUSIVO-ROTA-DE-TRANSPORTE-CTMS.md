# Rota CTMS — passo a passo no meu ambiente

Versão de 15 de outubro de 2026. Guia só da rota CTMS, aproveitando a rota de transporte que já funciona hoje. Escopo: DEV → QAS. Produção fica fora.

Nesta fase o desenvolvedor ainda clica em **Transport** no CPI, como já faz. A diferença é o que vem depois: em vez de alguém entrar no cTMS e no QAS, ele abre um chamado no ServiceNow e o Automation Pilot importa, faz o deploy e devolve o resultado ao chamado. Quando a API de export do Content Agent for confirmada, o clique também sai.

## Como vai funcionar

1. O dev salva a versão do iFlow em DEV e clica em **Transport** no pacote. A transport request cai na fila do nó QAS no cTMS, como hoje.
2. O dev abre o chamado "Transporte CPI DEV → QAS" no ServiceNow, informando o iFlow.
3. O flow do ServiceNow chama o Automation Pilot.
4. O Automation Pilot importa a fila do nó QAS, espera o import terminar, faz o deploy do iFlow em QAS e espera ele ficar `STARTED`.
5. O Automation Pilot grava o resultado no chamado, e o flow fecha o chamado ou marca falha.

## O que você já tem e não precisa recriar

| Item | Onde | Como conferir |
| --- | --- | --- |
| Destinations `ContentAssemblyService`, `CloudIntegration` e `TransportManagementService` (com `sourceSystemId`) | Subaccount DEV | O transporte manual de hoje funciona |
| Settings → Transport apontando para o cTMS | Integration Suite DEV | Check Configuration com sucesso |
| Content Agent plano application e destination `CloudIntegration` | Subaccount QAS | O import de hoje grava o conteúdo em QAS |
| Destination para o QAS, nós DEV e QAS, rota DEV → QAS | Subaccount do cTMS | A request aparece na fila de QAS depois do clique |

Se o transporte manual funciona hoje, tudo isso está certo. Não mexa nessas configurações.

## O que falta criar

| # | Item | Onde | Para quê |
| --- | --- | --- | --- |
| 1 | Service key do cTMS só para o Automation Pilot | Subaccount do cTMS (Brasil) | Importar a fila por API |
| 2 | Instância `pi-api-qas` (Process Integration Runtime, plano api) | Subaccount QAS (Brasil) | Fazer deploy e ler o status em QAS |
| 3 | ID numérico do nó QAS | Consulta na API do cTMS | Entrada do comando de import |
| 4 | Catálogo, inputs e service account | Automation Pilot (Europa) | Orquestrar |
| 5 | Item de catálogo, campos, credencial e flow | ServiceNow | Disparar e receber o resultado |

## Passo 1 — Service key do cTMS para o Automation Pilot

1. No subaccount do cTMS, abra **Instances and Subscriptions** e veja o plano da instância do Cloud Transport Management que já existe.
2. Se for `standard`: na mesma instância, **Create Service Key** → `key-tms-automation-pilot`. Não reaproveite a key da destination `TransportManagementService`; uma key separada pode ser trocada ou revogada sem derrubar o transporte manual.
3. Se for `export` ou `transport_operator`: crie uma instância nova do plano `standard` (`tms-api`) e gere a key nela. Esses planos não importam por API.
4. Guarde da key: `uri`, `uaa.url`, `uaa.clientid`, `uaa.clientsecret`. O token URL é `uaa.url` + `/oauth/token`.

## Passo 2 — Credencial de deploy em QAS

A instância por trás da destination `CloudIntegration` de QAS serve ao Content Agent para gravar o conteúdo. Não aumente as permissões dela. Crie uma separada:

1. No subaccount QAS: **Create** → `Process Integration Runtime`, plano `api`, nome `pi-api-qas`.
2. Grant type `client_credentials` e as roles abaixo.
3. **Create Service Key** → `key-pi-api-qas`. Guarde `url`, `tokenurl`, `clientid`, `clientsecret`.

```json
{
  "grant-types": ["client_credentials"],
  "roles": [
    "WorkspacePackagesRead",
    "WorkspacePackagesConfigure",
    "WorkspaceArtifactsDeploy",
    "MonitoringDataRead"
  ]
}
```

Na rota CTMS quem grava o iFlow em QAS é o Content Agent, por isso esta credencial não precisa de `WorkspacePackagesEdit`. Confirme os nomes das roles na tela de criação do seu tenant.

## Passo 3 — ID numérico do nó QAS

O comando de import pede o ID, não o nome do nó.

```bash
TOKEN=$(curl -s -u "$CLIENT_ID:$CLIENT_SECRET" \
  -d grant_type=client_credentials "$UAA_URL/oauth/token" | jq -r .access_token)
curl -s -H "Authorization: Bearer $TOKEN" "$TMS_URI/v2/nodes" | jq '.[] | {id, name}'
```

Use a key do Passo 1. Anote o `id` do nó de QAS.

## Passo 4 — Automation Pilot

### 4.1 Ajuste no catálogo antes de importar

O catálogo atual, na rota `CTMS`, começa pelo export automático, que ainda não está confirmado. Para o seu modelo (dev clica em Transport), o orquestrador precisa pular o export e fazer só import e deploy. Faça duas edições no `cpi-transport-catalog.json` antes de importar:

1. No comando `TransportToQas`, input `route`: em `allowedValues`, acrescente `"CTMS_IMPORT"`.
2. No mesmo comando, no passo de alias `Import`: na condição `when`, troque `"values": ["CTMS"]` por `"values": ["CTMS", "CTMS_IMPORT"]`.

Com isso, `route = CTMS_IMPORT` roda só `Import` → `Deploy` → `CloseTicket`. O passo `Export` continua só para `route = CTMS`.

### 4.2 Assinatura e importação

1. No subaccount OPS (Europa), confirme a assinatura do Automation Pilot e a sua role de desenvolvedor.
2. **My Catalogs → Import** e cole o JSON ajustado.
3. Os comandos importados ficam em rascunho. Só libere depois do Passo 6.

### 4.3 Inputs a preencher

| Input | Vem de | Preencher agora? |
| --- | --- | --- |
| `CtmsCredentials` | Key do Passo 1: `uri`, token URL, client ID, secret | Sim |
| `CpiQasCredentials` | `key-pi-api-qas` do Passo 2 | Sim |
| `ServiceNowCredentials` | Usuário de integração do Passo 5.2 | Sim |
| `CasCredentials` | Content Agent DEV | Não; só quando o export automático for confirmado |
| `CpiDevCredentials` | Plano api em DEV | Não; só serve à rota DIRECT |

Todas as keys do Brasil têm o código da região Brasil no domínio. Confira o subdomínio de cada URL contra o subaccount de origem antes de salvar; colar a key de um ambiente no input de outro é o erro mais comum.

### 4.4 Service account para o ServiceNow

1. No Automation Pilot, crie o service account `servicenow-trigger` com autenticação Basic.
2. Guarde usuário e senha para o Passo 5.4.
3. Anote o host do seu tenant (por exemplo `emea.autopilot.cloud.sap`).

### 4.5 Campo de status no ServiceNow

O comando `UpdateServiceNowTicket` grava em `u_cpi_transport_status` e `work_notes`. Se criar o campo com outro nome no Passo 5.3, ajuste o corpo desse comando.

## Passo 5 — ServiceNow

### 5.1 Item de catálogo

**Service Catalog → Maintain Items → New**: `Transporte CPI DEV → QAS`.

| Variável | Rótulo | Tipo | Obrigatória | Vai para |
| --- | --- | --- | --- | --- |
| `u_artifact_id` | ID técnico do iFlow | Single Line Text, regex `^[A-Za-z0-9_.-]+$` | Sim | `artifactId` (deploy) |
| `u_artifact_version` | Versão transportada | Single Line Text | Sim | `version` (registro no chamado) |
| `u_package_id` | ID técnico do pacote | Single Line Text | Sim | `packageId` (exigido pelo comando) |
| `u_transport_done` | Já cliquei em Transport no CPI | CheckBox | Sim, marcado | Bloqueia chamado aberto antes do clique |
| `u_target_env` | Ambiente de destino | Select Box read-only, valor `QAS` | Sim | Informativo |
| `u_justification` | Justificativa | Multi Line Text | Sim | Auditoria |
| `u_change_reference` | Mudança relacionada | Single Line Text | Não | Auditoria |

No help text, explique: primeiro clique em **Transport** no pacote do CPI DEV, depois abra o chamado.

### 5.2 Usuário de integração

`svc_automation_pilot`, **Web service access only**, com write em `sc_req_item` só nos campos `u_cpi_transport_status` e `work_notes`. Valide a ACL com o admin do ServiceNow.

### 5.3 Campos novos em `sc_req_item`

| Campo | Tipo | Valores |
| --- | --- | --- |
| `u_cpi_transport_status` | Choice | `PENDING`, `RUNNING`, `SUCCESS`, `FAILED` |
| `u_ap_execution_id` | String (100) | ID da execução |

### 5.4 Credencial e alias

1. **Connections & Credentials → Credentials → New → Basic Auth**: `AutomationPilot_Trigger`, com o service account do Passo 4.4.
2. **Connection & Credential Aliases → New**: `AutomationPilot`.
3. No alias, **HTTP(s) Connection** com essa credencial e a URL `https://<host do seu tenant>`.

Nenhuma senha vai no corpo do flow.

### 5.5 Action "Disparar Automation Pilot"

REST step (Integration Hub): alias `AutomationPilot`, `POST /api/v1/executions`, headers `Content-Type` e `Accept` `application/json`.

```json
{
  "commandId": "cpitransport-<TENANT_ID>:TransportToQas:1",
  "inputValues": {
    "artifactId": "{{artifact_id}}",
    "version": "{{version}}",
    "packageId": "{{package_id}}",
    "route": "CTMS_IMPORT",
    "qasNodeId": "<ID_DO_NO_QAS>",
    "ticketNumber": "{{ticket_number}}",
    "ticketSysId": "{{ticket_sys_id}}",
    "ticketTable": "sc_req_item"
  },
  "tags": {"feature:logs": "", "servicenow:ticket": "{{ticket_number}}"}
}
```

Script step depois da REST step, para pegar o ID da execução:

```javascript
(function execute(inputs, outputs) {
  outputs.execution_id = JSON.parse(inputs.response_body).id;
})(inputs, outputs);
```

Crie também a action "Consultar execução": `GET /api/v1/executions/{{execution_id}}`, output `execution_status` = `status`.

### 5.6 Flow

Trigger **Service Catalog**, ligado ao item do 5.1. Run As System User.

1. Get Catalog Variables.
2. Update Record: `u_cpi_transport_status` = `RUNNING`.
3. Disparar Automation Pilot; `ticket_number` = Number, `ticket_sys_id` = Sys ID.
4. Se o código HTTP não for 200 nem 201: `FAILED`, work notes com o código, fim.
5. Update Record: `u_ap_execution_id`.
6. Repita até 60 vezes, enquanto `execution_status` não for `FINISHED`, `FAILED`, `ABORTED` ou `SUSPENDED`: espere 60 s e consulte a execução.
7. `FINISHED` → Closed Complete.
8. Outro status → `FAILED`, work notes com o ID da execução e atribuição ao suporte de integração.
9. **Activate**. Em rascunho o flow não dispara.

### 5.7 O que volta do Automation Pilot

```http
PATCH https://<instancia>.service-now.com/api/now/table/sc_req_item/<sys_id>
Content-Type: application/json

{
  "u_cpi_transport_status": "SUCCESS",
  "work_notes": "Automation Pilot: iFlow <id> em QAS com status STARTED (rota CTMS_IMPORT)."
}
```

## Passo 6 — Testes

- [ ] Token da key do Passo 1 e `GET {uri}/v2/nodes` devolve os nós
- [ ] Token da `key-pi-api-qas` e `GET {url}/api/v1/IntegrationPackages` em QAS devolve 200
- [ ] PATCH do 5.7 com o usuário de integração atualiza um chamado de teste
- [ ] Clique em Transport num iFlow de teste e rode `ImportQueue` isolado: a request sai da fila e o iFlow aparece em QAS
- [ ] Rode `ImportQueue` com a fila vazia e anote o que o cTMS devolve
- [ ] `DeployIntegrationFlow` isolado → `STARTED`
- [ ] `TransportToQas` com `route = CTMS_IMPORT` → execução `FINISHED` e chamado com `SUCCESS`
- [ ] Ponta a ponta pelo portal do ServiceNow, com um iFlow de teste

## Rotina do desenvolvedor

1. Salvar a versão do iFlow em DEV.
2. Clicar em **Transport** no pacote.
3. Abrir o chamado "Transporte CPI DEV → QAS" com o ID técnico, a versão e o pacote, e marcar "Já cliquei em Transport".
4. Acompanhar o status no chamado. Não importar nada à mão no cTMS.

## Pontos de atenção

- **O import leva a fila inteira de QAS.** O comando `ImportQueue` usa importar tudo. Se houver outra request esperando (de outro dev ou pacote), ela entra junto. Combine com o time que a fila de QAS só é importada pelo chamado. Se isso não for viável, o próximo ajuste é importar só a request do chamado.
- **Chamado antes do clique dá falso sucesso.** Se a fila estiver vazia, o import não traz nada, o deploy sobe a versão que já estava em QAS e o chamado pode fechar como sucesso. O checkbox do 5.1 reduz o risco; o teste de fila vazia do Passo 6 mostra como o cTMS reage, e dá para fazer o comando falhar nesse caso.
- **Tempos cross-region.** O Automation Pilot está na Europa e os serviços no Brasil; o catálogo já usa timeouts e polling com folga (até cerca de 15 min no import e 10 min no deploy).
- **Compliance.** Na rota CTMS o conteúdo dos iFlows fica no Brasil (DEV → cTMS → QAS). O Automation Pilot na Europa guarda as credenciais e metadados das execuções. Valide isso com Segurança e Compliance.
- **Próximo passo.** Confirmada a API de export do Content Agent, o chamado passa a usar `route = CTMS`, você preenche `CasCredentials` e o clique em Transport deixa de existir.
