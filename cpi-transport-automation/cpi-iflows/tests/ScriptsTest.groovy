/*
 * Testes locais dos scripts dos iFlows, com classes que simulam as APIs do CPI.
 * Como rodar: veja tests/README.md
 */
import com.sap.gateway.ip.core.customdev.util.Message
import com.sap.it.api.ITApiFactory
import com.sap.it.api.keystore.KeystoreService
import com.sap.it.api.securestore.*
import groovy.json.*
import java.security.*

def dir = args[0]
def load = { name -> new GroovyShell(this.class.classLoader).parse(new File(dir, name)) }
int ok = 0
def check = { boolean c, String what -> if (!c) throw new AssertionError("FALHOU: " + what); ok++ }
def msg = { Map props = [:], Map headers = [:], String body = "" ->
    def m = new Message()
    props.each { k, v -> m.setProperty(k, v) }
    headers.each { k, v -> m.setHeader(k, v) }
    m.setBody(body)
    m
}
def P = { Message m, String k -> m.getProperty(k) }
def H = { Message m, String k -> m.getHeaders()[k] }
def B = { Message m -> m.getBody(String) }
def J = { String s -> new JsonSlurper().parseText(s) }

// ================================================================ TRP_03
def kpg = KeyPairGenerator.getInstance("RSA"); kpg.initialize(2048); def kp = kpg.generateKeyPair()
ITApiFactory.registry[KeystoreService] = [getKey: { a -> a == "trp_snow_jwt" ? kp.private : null }] as KeystoreService
ITApiFactory.registry[SecureStoreService] = [getUserCredential: { a -> a == "TRP_SNOW_CLIENT" ? new UserCredential(username: "cid123", password: "s3cr&t=".toCharArray()) : null }] as SecureStoreService
def s3 = load("TRP_03_SnowToken.groovy")
def m = msg([SNOW_CLIENT_CREDENTIAL: "TRP_SNOW_CLIENT", SNOW_JWT_KEY_ALIAS: "trp_snow_jwt", SNOW_JWT_KID: "kid1", SNOW_JWT_SUB: "svc_cpi_transport",
             SNOW_ASSERTION_PARAM: "assertion", SNOW_JWT_TTL_SECONDS: "300", SNOW_JWT_ISS: "-", SNOW_JWT_AUD: "-", SNOW_TOKEN_SCOPE: "-"])
s3.buildTokenRequest(m)
def form = B(m).split("&").collectEntries { def p = it.split("=", 2); [(p[0]): URLDecoder.decode(p[1], "UTF-8")] }
check(form.grant_type == "urn:ietf:params:oauth:grant-type:jwt-bearer", "grant_type")
check(form.client_id == "cid123" && form.client_secret == "s3cr&t=", "client_id/secret codificados")
check(!form.containsKey("scope"), "scope '-' conta como vazio")
def parts = form.assertion.split("\\.")
def dec = { new String(Base64.urlDecoder.decode(it), "UTF-8") }
def hdr = J(dec(parts[0])); def cl = J(dec(parts[1]))
check(hdr.alg == "RS256" && hdr.kid == "kid1" && hdr.typ == "JWT", "cabeçalho do JWT")
check(cl.iss == "cid123" && cl.aud == "cid123" && cl.sub == "svc_cpi_transport" && cl.exp - cl.iat == 300 && cl.jti, "claims do JWT ('-' vira client_id)")
def v = Signature.getInstance("SHA256withRSA"); v.initVerify(kp.public); v.update((parts[0] + "." + parts[1]).getBytes("US-ASCII"))
check(v.verify(Base64.urlDecoder.decode(parts[2])), "assinatura RS256 confere com a chave pública")
check(H(m, "Content-Type") == "application/x-www-form-urlencoded", "content-type do pedido de token")
m = msg([SNOW_CLIENT_CREDENTIAL: "TRP_SNOW_CLIENT", SNOW_JWT_KEY_ALIAS: "trp_snow_jwt", SNOW_JWT_KID: "k", SNOW_JWT_SUB: "u",
         SNOW_ASSERTION_PARAM: "client_assertion", SNOW_JWT_AUD: "https://idp/token", SNOW_TOKEN_SCOPE: "useraccount"])
s3.buildTokenRequest(m)
form = B(m).split("&").collectEntries { def p = it.split("=", 2); [(p[0]): URLDecoder.decode(p[1], "UTF-8")] }
check(form.client_assertion && !form.assertion && form.scope == "useraccount", "parâmetro client_assertion e scope")
check(J(dec(form.client_assertion.split("\\.")[1])).aud == "https://idp/token", "aud configurável")
try { s3.buildTokenRequest(msg([SNOW_CLIENT_CREDENTIAL: "NAO_EXISTE"])); check(false, "credencial inexistente deveria falhar") } catch (IllegalStateException e) { check(e.message.contains("NAO_EXISTE"), "erro claro para credencial inexistente") }
try { s3.buildTokenRequest(msg([SNOW_CLIENT_CREDENTIAL: "TRP_SNOW_CLIENT", SNOW_JWT_KEY_ALIAS: "errado"])); check(false, "alias errado deveria falhar") } catch (IllegalStateException e) { check(e.message.contains("errado"), "erro claro para alias errado") }
m = msg([:], [:], '{"access_token":"TOK","expires_in":1799,"scope":"x"}'); s3.parseTokenResponse(m)
check(J(B(m)).access_token == "TOK" && J(B(m)).expires_in == 1799 && !J(B(m)).scope, "parseTokenResponse")
try { s3.parseTokenResponse(msg([:], [:], '{"error":"invalid_grant","error_description":"bad jwt"}')); check(false, "sem token deveria falhar") } catch (IllegalStateException e) { check(e.message.contains("invalid_grant"), "erro do ServiceNow na mensagem") }

// ================================================================ TRP_01
def s1 = load("TRP_01_Orchestrator.groovy")
m = msg([SNOW_CAT_ITEM_SYSID: "abc123", ACTIVE_STATES: "PENDING, DEPLOYING", SNOW_LIMIT: "20"], [:], '{"access_token":"TOK"}')
s1.prepareSearch(m)
check(P(m, "SNOW_TOKEN") == "TOK" && H(m, "Authorization") == "Bearer TOK" && H(m, "Accept") == "application/json", "prepareSearch: token e headers")
def q = P(m, "SNOW_QUERY")
check(!q.contains("^") && !q.contains(" ") && q.contains("sysparm_query=cat_item%3Dabc123%5Eu_cpi_transport_statusINPENDING%2CDEPLOYING%5EORDERBYsys_created_on"), "query codificada")
check(q.contains("sysparm_display_value=false") && q.contains("sysparm_limit=20") && q.contains("u_cpi_tr_id"), "demais parâmetros da query")
try { s1.prepareSearch(msg([:], [:], '{"access_token":"TOK"}')); check(false, "sem item de catálogo deveria falhar") } catch (IllegalStateException e) { check(true, "exige SNOW_CAT_ITEM_SYSID") }
m.setBody('{"result":[{"sys_id":"a1","number":"RITM001","u_cpi_transport_status":"PENDING","u_cpi_artifact_id":"IF_A"},{"sys_id":"a2","number":"RITM002","u_cpi_transport_status":"DEPLOYING"}]}')
s1.ticketsToLines(m)
check(P(m, "TICKET_COUNT") == "2" && B(m).split("\n").size() == 2 && H(m, "Authorization") == "", "ticketsToLines")
m.setBody(B(m).split("\n")[0]); s1.lineToRequest(m)
check(H(m, "X-Trp-Snow-Token") == "TOK" && H(m, "SAP_ApplicationID") == "RITM001" && J(B(m)).u_cpi_artifact_id == "IF_A", "lineToRequest")
def m0 = msg([:], [:], '{"result":[]}'); s1.ticketsToLines(m0); check(P(m0, "TICKET_COUNT") == "0", "nenhum chamado")

// ================================================================ TRP_02
def s2 = load("TRP_02_AdvanceTicket.groovy")
def cfg = [EXPORT_MODE: "API", CAS_DEV_URL: "https://cas", CAS_EXPORT_PATH: "/x/export", CAS_EXPORT_BODY_TEMPLATE: '{"pkg":"#PACKAGE_ID#","desc":"#TICKET#"}',
  CAS_OPID_JSONPATH: "id", CAS_STATUS_PATH: "/x/export/#OPERATION_ID#", CAS_STATUS_JSONPATH: "status", CAS_DONE_VALUES: "COMPLETED,SUCCEEDED",
  CAS_FAILED_VALUES: "FAILED,ERROR", CAS_TRID_JSONPATH: "transportRequest.id",
  CTMS_URI: "https://tms", CTMS_QAS_NODE_ID: "42", CTMS_NAMED_USER: "cpi", CTMS_IMPORT_PATH: "/v2/nodes/#NODE_ID#/transportRequests/import",
  CTMS_ACTIONID_JSONPATH: "actionId", CTMS_ACTION_PATH: "/v2/actions/#ACTION_ID#", CTMS_ACTION_STATUS_JSONPATH: "status",
  CTMS_ACTION_OK_VALUES: "-", CTMS_ACTION_FAILED_VALUES: "-", CTMS_IMPORTABLE_VALUES: "-",
  CTMS_QUEUE_PATH: "/v2/nodes/#NODE_ID#/transportRequests", TIMEOUT_EXPORT_MIN: "15", TIMEOUT_WAITING_TR_MIN: "60",
  TIMEOUT_IMPORT_MIN: "15", TIMEOUT_DEPLOY_MIN: "10", MAX_RETRIES: "5"]
def ticket = { Map t -> JsonOutput.toJson([sys_id: "a1", number: "RITM001", u_cpi_artifact_id: "IF_A", u_cpi_artifact_version: "1.0.3", u_cpi_package_id: "PKG_A"] + t) }
def fmt = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss"); fmt.timeZone = TimeZone.getTimeZone("UTC")
def ago = { int min -> fmt.format(new Date(System.currentTimeMillis() - min * 60000L)) }
def fields = { Message mm -> J(P(mm, "UPD_FIELDS")) }
def start = { Map t, Map over = [:] -> def mm = msg(cfg + over, ["X-Trp-Snow-Token": "TOK"], ticket(t)); s2.prepare(mm); mm }

// PENDING com export por API
m = start([u_cpi_transport_status: "PENDING"])
check(P(m, "ROUTE") == "EXPORT" && P(m, "UPDATE") == "false", "PENDING + API -> rota EXPORT")
check(H(m, "X-Trp-Snow-Token") == "" && P(m, "SNOW_TOKEN") == "TOK" && H(m, "SAP_ApplicationID") == "RITM001", "token vira propriedade")
s2.buildExportRequest(m); check(B(m) == '{"pkg":"PKG_A","desc":"RITM001"}', "corpo do export")
m.setBody('{"id":"op-9"}'); s2.handleExportResponse(m)
check(fields(m).u_cpi_transport_status == "EXPORTING" && fields(m).u_cpi_operation_id == "op-9" && P(m, "UPDATE") == "true", "-> EXPORTING")
m = start([u_cpi_transport_status: "PENDING"]); m.setBody('{}'); s2.handleExportResponse(m); check(fields(m).u_cpi_transport_status == "FAILED", "export sem ID -> FAILED")

// PENDING na contingência
m = start([u_cpi_transport_status: "PENDING"], [EXPORT_MODE: "MANUAL_TR"])
check(P(m, "ROUTE") == "DONE" && fields(m).u_cpi_transport_status == "WAITING_TR" && P(m, "UPD_NOTE").contains("RITM001"), "PENDING + MANUAL_TR -> WAITING_TR")

// Validação
m = start([u_cpi_transport_status: "PENDING", u_cpi_artifact_id: "IF A!"])
check(P(m, "ROUTE") == "DONE" && fields(m).u_cpi_transport_status == "FAILED" && fields(m).u_cpi_message.contains("iFlow"), "campos inválidos -> FAILED")
m = start([u_cpi_transport_status: "PENDING", u_cpi_artifact_version: "Draft"]); check(fields(m).u_cpi_message.contains("Versão"), "versão Draft recusada")
m = start([u_cpi_transport_status: "PENDING", u_cpi_artifact_version: "active"]); check(P(m, "ROUTE") == "EXPORT", "versão active aceita")

// EXPORTING
m = start([u_cpi_transport_status: "EXPORTING", u_cpi_operation_id: "op-9", u_cpi_stage_since: ago(3)])
check(P(m, "ROUTE") == "CHECK_EXPORT" && P(m, "CAS_STATUS_URL") == "https://cas/x/export/op-9", "EXPORTING -> CHECK_EXPORT")
m.setBody('{"status":"Running"}'); s2.evaluateExportStatus(m); check(P(m, "NEXT") == "" && P(m, "UPDATE") == "false", "export em andamento")
m.setBody('{"status":"Completed","transportRequest":{"id":777}}'); s2.evaluateExportStatus(m); check(P(m, "NEXT") == "START_IMPORT" && P(m, "TR_ID") == "777", "export concluído")
s2.buildImportRequest(m)
check(P(m, "CTMS_IMPORT_URL") == "https://tms/v2/nodes/42/transportRequests/import" && J(B(m)).transportRequests == [777] && J(B(m)).namedUser == "cpi", "corpo do import por ID")
m.setBody('{"actionId":555}'); s2.handleImportResponse(m)
check(fields(m).u_cpi_transport_status == "IMPORTING" && fields(m).u_cpi_tr_id == "777" && fields(m).u_cpi_action_id == "555" && P(m, "NEXT") == "", "-> IMPORTING")
m = start([u_cpi_transport_status: "EXPORTING", u_cpi_stage_since: ago(3)]); m.setBody('{"status":"FAILED"}'); s2.evaluateExportStatus(m); check(fields(m).u_cpi_transport_status == "FAILED", "export falhou")
m = start([u_cpi_transport_status: "EXPORTING", u_cpi_stage_since: ago(3)]); m.setBody('{"status":"completed"}'); s2.evaluateExportStatus(m); check(fields(m).u_cpi_transport_status == "FAILED" && P(m, "NEXT") == "", "export sem ID da request")

// Prazo
m = start([u_cpi_transport_status: "EXPORTING", u_cpi_stage_since: ago(20)])
check(P(m, "ROUTE") == "DONE" && fields(m).u_cpi_transport_status == "FAILED" && fields(m).u_cpi_message.contains("Prazo"), "prazo vencido -> FAILED")
m = start([u_cpi_transport_status: "WAITING_TR", u_cpi_stage_since: ago(30)]); check(P(m, "ROUTE") == "CHECK_QUEUE", "WAITING_TR dentro do prazo de 60 min")

// WAITING_TR (contingência)
def queue = { String body -> def mm = start([u_cpi_transport_status: "WAITING_TR", u_cpi_stage_since: ago(5)]); mm.setBody(body); s2.findTrByTicket(mm); mm }
m = queue('{"transportRequests":[{"id":11,"status":"initial","description":"outro"},{"id":12,"status":"initial","description":"RITM001 deploy"}]}')
check(P(m, "TR_ID") == "12" && P(m, "NEXT") == "START_IMPORT", "acha a request pela descrição")
m = queue('{"transportRequests":[{"id":10,"status":"succeeded","description":"RITM001 antigo"},{"id":12,"status":"in","description":"RITM001"}]}')
check(P(m, "TR_ID") == "12", "ignora request já importada com o mesmo chamado")
m = queue('{"transportRequests":[{"id":11,"description":"outro"}]}'); check(P(m, "NEXT") == "" && P(m, "UPDATE") == "false", "sem request: espera")
m = queue('{"transportRequests":[{"id":1,"status":"in","description":"RITM001"},{"id":2,"status":"in","description":"RITM001 de novo"}]}')
check(fields(m).u_cpi_transport_status == "FAILED", "duas requests com o mesmo chamado -> FAILED")
m = queue('[{"id":"abc","description":"RITM001"}]'); s2.buildImportRequest(m); check(J(B(m)).transportRequests == ["abc"], "ID não numérico e resposta em lista")

// IMPORTING
m = start([u_cpi_transport_status: "IMPORTING", u_cpi_tr_id: "777", u_cpi_action_id: "555", u_cpi_stage_since: ago(2)])
check(P(m, "ROUTE") == "CHECK_IMPORT" && P(m, "CTMS_ACTION_URL") == "https://tms/v2/actions/555", "IMPORTING -> CHECK_IMPORT")
m.setBody('{"status":"running"}'); s2.evaluateImport(m); check(P(m, "NEXT") == "" && P(m, "UPDATE") == "false", "import rodando")
m.setBody('{"status":"succeeded"}'); s2.evaluateImport(m); check(P(m, "NEXT") == "START_DEPLOY", "import ok (palavra)")
m.setBody('{"status":"su"}'); s2.evaluateImport(m); check(P(m, "NEXT") == "START_DEPLOY", "import ok (código curto)")
s2.handleDeployResponse(m); check(fields(m).u_cpi_transport_status == "DEPLOYING" && P(m, "NEXT") == "", "-> DEPLOYING")
m = start([u_cpi_transport_status: "IMPORTING", u_cpi_tr_id: "777", u_cpi_action_id: "555", u_cpi_stage_since: ago(2)])
m.setBody('{"status":"error"}'); s2.evaluateImport(m); check(fields(m).u_cpi_transport_status == "FAILED" && fields(m).u_cpi_message.contains("555"), "import com erro")

// DEPLOYING
def dep = { int code, String body, int sinceMin = 1, Map t = [:] ->
    def mm = start([u_cpi_transport_status: "DEPLOYING", u_cpi_tr_id: "777", u_cpi_stage_since: ago(sinceMin)] + t)
    mm.setHeader("CamelHttpResponseCode", code); mm.setBody(body); s2.evaluateDeploy(mm); mm }
long nowMs = System.currentTimeMillis()
def rt = { String status, String version, long deployedMs -> '{"d":{"Status":"' + status + '","Version":"' + version + '","DeployedOn":"/Date(' + deployedMs + ')/"}}' }
check(P(m = start([u_cpi_transport_status: "DEPLOYING", u_cpi_stage_since: ago(1)]), "ROUTE") == "CHECK_DEPLOY", "DEPLOYING -> CHECK_DEPLOY")
m = dep(404, "not found"); check(P(m, "UPDATE") == "false", "404: ainda não está no runtime")
m = dep(200, rt("STARTING", "1.0.3", nowMs)); check(P(m, "UPDATE") == "false", "STARTING: espera")
m = dep(200, rt("STARTED", "1.0.3", nowMs)); check(fields(m).u_cpi_transport_status == "SUCCESS" && fields(m).u_cpi_message == "STARTED 1.0.3", "STARTED novo na versão pedida -> SUCCESS")
m = dep(200, rt("STARTED", "1.0.3", nowMs - 3600000L)); check(P(m, "UPDATE") == "false", "STARTED de um deploy antigo: espera")
m = dep(200, rt("STARTED", "1.0.4", nowMs)); check(fields(m).u_cpi_transport_status == "FAILED" && fields(m).u_cpi_message.contains("1.0.4"), "versão diferente da pedida -> FAILED com explicação")
m = dep(200, rt("STARTED", "2.1.0", nowMs), 1, [u_cpi_artifact_version: "active"]); check(fields(m).u_cpi_transport_status == "SUCCESS", "versão active aceita qualquer versão nova")
m = dep(200, rt("STARTED", "2.1.0", nowMs - 3600000L), 1, [u_cpi_artifact_version: "active"]); check(P(m, "UPDATE") == "false", "active não aceita deploy antigo")
m = dep(200, '{"d":{"Status":"STARTED","Version":"1.0.3"}}'); check(fields(m).u_cpi_transport_status == "SUCCESS", "sem DeployedOn: decide pela versão")
def iso = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS"); iso.timeZone = TimeZone.getTimeZone("UTC")
m = dep(200, '{"d":{"Status":"STARTED","Version":"1.0.3","DeployedOn":"' + iso.format(new Date(nowMs)) + '"}}'); check(fields(m).u_cpi_transport_status == "SUCCESS", "DeployedOn em formato ISO")
m = dep(200, rt("ERROR", "1.0.3", nowMs)); check(fields(m).u_cpi_transport_status == "FAILED", "runtime ERROR -> FAILED")
try { dep(503, ""); check(false, "503 deveria lançar") } catch (IllegalStateException e) { check(e.message.startsWith("TRANSIENT"), "503 na consulta é transitório") }
try { dep(401, ""); check(false, "401 deveria lançar") } catch (IllegalStateException e) { check(!e.message.startsWith("TRANSIENT"), "401 na consulta é definitivo") }

// Padrões quando os parâmetros opcionais não existem
def minimal = [EXPORT_MODE: "MANUAL_TR", CTMS_URI: "https://tms", CTMS_QAS_NODE_ID: "42"]
m = msg(minimal, ["X-Trp-Snow-Token": "TOK"], ticket([u_cpi_transport_status: "IMPORTING", u_cpi_tr_id: "777", u_cpi_action_id: "555", u_cpi_stage_since: ago(2)])); s2.prepare(m)
check(P(m, "CTMS_ACTION_URL") == "https://tms/v2/actions/555" && P(m, "CTMS_QUEUE_URL") == "https://tms/v2/nodes/42/transportRequests", "caminhos padrão do cTMS")
m.setBody('{"status":"succeeded"}'); s2.evaluateImport(m); check(P(m, "NEXT") == "START_DEPLOY", "status padrão do cTMS")
m.setProperty("TR_ID", "777"); s2.buildImportRequest(m)
check(P(m, "CTMS_IMPORT_URL") == "https://tms/v2/nodes/42/transportRequests/import" && J(B(m)).namedUser == "cpi-orquestracao", "import com padrões")
m.setBody('{"actionId":9}'); s2.handleImportResponse(m); check(fields(m).u_cpi_action_id == "9", "actionId no caminho padrão")
try { s2.prepare(msg([EXPORT_MODE: "MANUAL_TR"], ["X-Trp-Snow-Token": "TOK"], ticket([u_cpi_transport_status: "PENDING"]))); check(false, "sem CTMS_URI deveria lançar") } catch (IllegalStateException e) { check(e.message.contains("CTMS_URI"), "configuração mínima cobrada") }
m = msg([:], ["X-Trp-Snow-Token": "TOK"], ticket([u_cpi_transport_status: "FAILED"])); s2.prepare(m); check(P(m, "ROUTE") == "DONE", "estado final não cobra configuração")

// Estado final e pedido de atualização
m = start([u_cpi_transport_status: "SUCCESS"]); check(P(m, "ROUTE") == "DONE" && P(m, "UPDATE") == "false", "estado final: nada a fazer")
m = dep(200, rt("STARTED", "1.0.3", nowMs)); s2.buildUpdateRequest(m)
def upd = J(B(m))
check(upd.sys_id == "a1" && upd.fields.work_notes.startsWith("[Transporte CPI]") && upd.fields.u_cpi_retries == "0" && H(m, "X-Trp-Snow-Token") == "TOK", "pedido de atualização")

// Erros
class FakeHttpEx extends RuntimeException { int sc; String rb; FakeHttpEx(int s, String b) { super("http " + s); sc = s; rb = b }; int getStatusCode() { sc }; String getResponseBody() { rb } }
def err = { Map t, Throwable ex -> def mm = start(t); mm.setProperty("CamelExceptionCaught", ex); s2.handleError(mm); J(B(mm)) }
def r = err([u_cpi_transport_status: "IMPORTING", u_cpi_retries: "1", u_cpi_stage_since: ago(2)], new FakeHttpEx(503, "busy"))
check(r.sys_id == "a1" && r.fields.u_cpi_retries == "2" && !r.fields.u_cpi_transport_status && r.fields.work_notes.contains("transitório"), "503 -> soma tentativa")
r = err([u_cpi_transport_status: "IMPORTING", u_cpi_retries: "4", u_cpi_stage_since: ago(2)], new FakeHttpEx(503, "busy"))
check(r.fields.u_cpi_transport_status == "FAILED", "tentativas esgotadas -> FAILED")
r = err([u_cpi_transport_status: "PENDING"], new FakeHttpEx(400, "bad pkg")); check(r.fields.u_cpi_transport_status == "FAILED" && r.fields.u_cpi_message.contains("400"), "400 -> FAILED")
r = err([u_cpi_transport_status: "DEPLOYING", u_cpi_stage_since: ago(1)], new RuntimeException("x", new java.net.SocketTimeoutException("t"))); check(r.fields.u_cpi_retries == "1", "timeout de rede é transitório")
def me = msg(cfg, [:], ""); me.setProperty("CamelExceptionCaught", new IllegalStateException("Token do ServiceNow ausente"))
try { s2.handleError(me); check(false, "sem chamado deveria lançar") } catch (IllegalStateException e) { check(e.message.contains("antes de identificar"), "erro antes de ler o chamado não tenta atualizar") }
try { s2.prepare(msg(cfg, [:], ticket([u_cpi_transport_status: "PENDING"]))); check(false, "sem token deveria lançar") } catch (IllegalStateException e) { check(e.message.contains("X-Trp-Snow-Token"), "token ausente") }

// ================================================================ TRP_04
def s4 = load("TRP_04_SnowUpdate.groovy")
m = msg([:], ["X-Trp-Snow-Token": "TOK"], '{"sys_id":"a1","fields":{"u_cpi_transport_status":"SUCCESS"}}'); s4.prepareUpdate(m)
check(P(m, "SYS_ID") == "a1" && H(m, "Authorization") == "Bearer TOK" && H(m, "X-Trp-Snow-Token") == "" && B(m) == '{"u_cpi_transport_status":"SUCCESS"}', "prepareUpdate")
m.setBody('{"result":{"sys_id":"a1"}}'); s4.finishUpdate(m); check(B(m) == "" && H(m, "Authorization") == "", "finishUpdate limpa corpo e Authorization")
try { s4.prepareUpdate(msg([:], ["X-Trp-Snow-Token": "TOK"], '{"fields":{}}')); check(false, "sem sys_id deveria lançar") } catch (IllegalStateException e) { check(true, "exige sys_id") }

// ================================================================ TRP_99
def s9 = load("TRP_99_ConnectionTest.groovy")
m = msg([TEST_SYS_ID: "-"], [:], '{"access_token":"TOK","expires_in":1799}'); s9.afterToken(m)
check(P(m, "DO_UPDATE") == "false" && B(m) == "", "teste só do token")
m = msg([TEST_SYS_ID: "a9"], [:], '{"access_token":"TOK","expires_in":1799}'); s9.afterToken(m)
check(P(m, "DO_UPDATE") == "true" && J(B(m)).sys_id == "a9" && H(m, "X-Trp-Snow-Token") == "TOK", "teste com atualização")
s9.afterUpdate(m); check(H(m, "X-Trp-Snow-Token") == "" && B(m) == "", "afterUpdate limpa o token")

println "OK: " + ok + " verificacoes passaram"
