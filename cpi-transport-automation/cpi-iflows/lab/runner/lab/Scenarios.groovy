package lab

import com.sap.it.api.ITApiFactory
import com.sap.it.api.keystore.KeystoreService
import com.sap.it.api.securestore.SecureStoreService
import com.sap.it.api.securestore.UserCredential
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

import java.security.KeyFactory
import java.security.PrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.text.SimpleDateFormat

/** Cliente da porta de controle dos simuladores. */
class Ctl {
    static Object post(String path, Object body) {
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:18090" + path).openConnection()
        c.setRequestMethod("POST")
        c.setDoOutput(true)
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.withWriter("UTF-8") { it << JsonOutput.toJson(body) }
        new JsonSlurper().parseText(c.inputStream.getText("UTF-8"))
    }
    static Map state() { post("/_lab/state", [:]) as Map }
}

/**
 * Os cenários. Cada um zera os simuladores, prepara os chamados e dispara os iFlows
 * como o Timer do CPI faria, conferindo o chamado, o cTMS, o CPI QAS e o monitor.
 */
class Scenarios {
    static final String CAT = "c0ffee00c0ffee00c0ffee00c0ffee00"

    Tenant tenant = new Tenant()
    IfTrp01 f01
    IfTrp02 f02
    IfTrp03 f03
    IfTrp04 f04
    IfTrp99 f99
    String certPem
    PrivateKey key
    PrivateKey otherKey
    int seq = 0

    String scenario = ""
    int checks = 0
    List<String> failures = []
    Set<String> failedScenarios = [] as Set

    Scenarios(String scriptsDir, String keysDir) {
        certPem = new File(keysDir, "trp_snow_jwt.crt").text
        key = loadKey(new File(keysDir, "trp_snow_jwt.pk8"))
        otherKey = loadKey(new File(keysDir, "outra.pk8"))
        f01 = new IfTrp01(scriptsDir); f02 = new IfTrp02(scriptsDir); f03 = new IfTrp03(scriptsDir)
        f04 = new IfTrp04(scriptsDir); f99 = new IfTrp99(scriptsDir)
        [f04, f03, f99, f02, f01].each { tenant.deploy(it, [:]) }
        Tenant t = tenant
        ITApiFactory.registry[KeystoreService] = { String alias -> t.keystore[alias] } as KeystoreService
        ITApiFactory.registry[SecureStoreService] = { String alias ->
            Map c = t.credentials[alias]
            (c != null && c.type == "user") ? new UserCredential(username: c.user, password: (c.password as String).toCharArray()) : null
        } as SecureStoreService
    }

    static PrivateKey loadKey(File f) {
        KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(f.bytes))
    }

    // ------------------------------------------------------------ apoio

    static String utc(long millis) {
        def f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss"); f.setTimeZone(TimeZone.getTimeZone("UTC")); f.format(new Date(millis))
    }

    /** Zera os simuladores e deixa o tenant com a configuração do guia (tela Configure de cada iFlow). */
    void setup(Map set = [:]) {
        Ctl.post("/_lab/reset", [cert_pem: certPem, set: set])
        tenant.mpls.clear()
        tenant.clearTokenCache()
        tenant.keystore = [trp_snow_jwt: key]
        tenant.credentials = [
            TRP_SNOW_CLIENT: [type: "user", user: "lab-client-id", password: "lab-client-secret"],
            TRP_CTMS       : [type: "oauth2cc", tokenUrl: "http://127.0.0.1:18081/oauth/token", clientId: "sb-ctms-lab", clientSecret: "ctms-secret"],
            TRP_CPI_QAS    : [type: "oauth2cc", tokenUrl: "http://127.0.0.1:18081/oauth/token", clientId: "sb-cpiqas-lab", clientSecret: "cpiqas-secret"],
            TRP_CAS_DEV    : [type: "oauth2cc", tokenUrl: "http://127.0.0.1:18081/oauth/token", clientId: "sb-cas-lab", clientSecret: "cas-secret"]
        ]
        f04.params = [SNOW_HOST: "127.0.0.1:18080", SNOW_TABLE: "sc_req_item"]
        f03.params = [SNOW_TOKEN_URL: "https://127.0.0.1:18080/oauth_token.do", SNOW_CLIENT_CREDENTIAL: "TRP_SNOW_CLIENT",
                      SNOW_JWT_KEY_ALIAS: "trp_snow_jwt", SNOW_JWT_KID: "trp-cpi-2026", SNOW_JWT_SUB: "svc_cpi_transport",
                      SNOW_ASSERTION_PARAM: "assertion", SNOW_JWT_AUD: "-", SNOW_JWT_ISS: "-", SNOW_TOKEN_SCOPE: "-"]
        f99.params = [TEST_SYS_ID: "-"]
        f02.params = [EXPORT_MODE: "MANUAL_TR", CTMS_URI: "https://127.0.0.1:18082", CTMS_QAS_NODE_ID: "2",
                      CTMS_NAMED_USER: "cpi-orquestracao", MAX_RETRIES: "5", TIMEOUT_WAITING_TR_MIN: "60",
                      TIMEOUT_IMPORT_MIN: "15", TIMEOUT_DEPLOY_MIN: "10", CPI_QAS_URL: "https://127.0.0.1:18084",
                      CRED_CTMS: "TRP_CTMS", CRED_CPI_QAS: "TRP_CPI_QAS", CRED_CAS_DEV: "TRP_CAS_DEV"]
        f02.config.keySet().retainAll(["EXPORT_MODE", "CTMS_URI", "CTMS_QAS_NODE_ID", "CTMS_NAMED_USER", "MAX_RETRIES",
                                       "TIMEOUT_WAITING_TR_MIN", "TIMEOUT_IMPORT_MIN", "TIMEOUT_DEPLOY_MIN"])
        f01.params = [TRP_ENABLED: "true", SNOW_HOST: "127.0.0.1:18080", SNOW_TABLE: "sc_req_item",
                      SNOW_CAT_ITEM_SYSID: CAT, SNOW_LIMIT: "20"]
    }

    void check(String what, boolean ok, Object detail = "") {
        checks++
        if (!ok) {
            failedScenarios << scenario
            failures << (scenario + ": " + what + (detail ? " | " + detail : ""))
        }
    }

    /** Abre um chamado do item de catálogo do transporte (o Flow do item já gravou PENDING). */
    String ticket(Map fields = [:]) {
        seq++
        Map rec = [number: "RITM00" + (10000 + seq), cat_item: CAT, state: "1", short_description: "Transporte de iFlow",
                   u_cpi_transport_status: "PENDING", u_cpi_artifact_id: "IF_Pedidos_SAP", u_cpi_artifact_version: "1.0.3",
                   u_cpi_package_id: "Z_PEDIDOS", u_cpi_retries: "0",
                   sys_created_on: utc(System.currentTimeMillis() - 3600000L + seq * 1000L)]
        rec.putAll(fields)
        Ctl.post("/_lab/snow/record", rec).sys_id
    }
    Map rec(String id) { Ctl.state().snow.records[id] as Map }
    String status(String id) { rec(id).u_cpi_transport_status }
    List<String> notes(String id) { (Ctl.state().snow.journal[id] ?: []) as List<String> }
    List<Map> reqs(String system = null) { (Ctl.state().requests as List<Map>).findAll { system == null || it.system == system } }
    void fault(String system, String method, String pathRegex, int httpStatus, int times = 1, String body = null) {
        Map f = [system: system, method: method, path_regex: pathRegex, status: httpStatus, times: times]
        if (body != null) f.body = body
        Ctl.post("/_lab/fault", f)
    }

    /** Um disparo do Timer do IF_TRP_01. Devolve o que apareceria no monitor neste ciclo. */
    List<Mpl> cycle() {
        int from = tenant.mpls.size()
        f01.fire()
        new ArrayList<Mpl>(tenant.mpls.subList(from, tenant.mpls.size()))
    }

    /** O dev clica em Transport no pacote do CPI DEV (a request cai na fila do nó de QAS). */
    int transport(String description, Map content = [:], String trStatus = "initial") {
        Map c = [artifact: "IF_Pedidos_SAP", package: "Z_PEDIDOS", version: "1.0.3"]
        c.putAll(content)
        Ctl.post("/_lab/ctms/transport", [node: 2, description: description, content: c, status: trStatus]).id as int
    }

    /** Roda ciclos até o chamado terminar. Devolve a sequência de estados vista. */
    List<String> drive(String id, int maxCycles = 12) {
        List<String> trail = [status(id)]
        for (int i = 0; i < maxCycles && !(trail[-1] in ["SUCCESS", "FAILED"]); i++) {
            cycle()
            String s = status(id)
            if (s != trail[-1]) trail << s
        }
        trail
    }

    void rewindStage(String id, int minutes) {
        Ctl.post("/_lab/snow/record", [sys_id: id, u_cpi_stage_since: utc(System.currentTimeMillis() - minutes * 60000L)])
    }

    /** Conferências que valem para qualquer cenário: nada de segredo fora do lugar, simulador íntegro. */
    void commonChecks() {
        Map st = Ctl.state()
        List<Map> all = st.requests as List<Map>
        check("o simulador não teve erro interno", !all.any { it.status == 599 })
        check("o header X-Trp-Snow-Token nunca saiu do CPI",
              !all.any { Map r -> (r.headers as Map).keySet().any { (it as String).equalsIgnoreCase("X-Trp-Snow-Token") } })
        check("o token do ServiceNow nunca foi enviado a outro sistema",
              !all.any { Map r -> r.system != "snow" && (JsonOutput.toJson(r.headers) + r.body + r.raw_query).contains("snow-") })
        String stored = JsonOutput.toJson(st.snow.records) + JsonOutput.toJson(st.snow.journal)
        check("nenhum token ou segredo gravado no chamado", !(stored =~ /snow-[0-9a-f]{20}|xs-[0-9a-f]{20}|-secret/).find())
        String monitor = tenant.mpls.collect { it.customHeaders.toString() + (it.error ?: "") }.join(" ")
        check("nenhum token ou segredo no monitor do CPI", !(monitor =~ /snow-[0-9a-f]{20}|xs-[0-9a-f]{20}|lab-client-secret|eyJhbGciOi/).find(), monitor.take(300))
    }

    // ------------------------------------------------------------ Passo 8: teste de conexão

    void t01_conexaoSoToken() {
        setup()
        f99.fire()
        Mpl m = tenant.mpls.find { it.iflow == f99.name }
        check("IF_TRP_99 Completed", m.status == "COMPLETED", m)
        check("Custom Header TRP_TokenTest = OK", (m.customHeaders.TRP_TokenTest ?: [""])[0].startsWith("OK - token com"), m.customHeaders)
        check("IF_TRP_03 Completed", tenant.mpls.find { it.iflow == f03.name }?.status == "COMPLETED")
        check("nenhuma gravação no ServiceNow", !reqs("snow").any { it.method in ["PATCH", "PUT"] })
        Map tokenReq = reqs("snow").find { it.path == "/oauth_token.do" }
        check("pedido de token em form-urlencoded", (tokenReq.headers as Map).find { (it.key as String).equalsIgnoreCase("Content-Type") }.value.toString().startsWith("application/x-www-form-urlencoded"))
    }

    void t02_conexaoTokenEGravacao() {
        setup()
        String id = ticket(u_cpi_transport_status: "", short_description: "Chamado de teste")
        Map before = rec(id)
        f99.params.TEST_SYS_ID = id
        f99.fire()
        Mpl m = tenant.mpls.find { it.iflow == f99.name }
        check("IF_TRP_99 Completed", m.status == "COMPLETED", m)
        check("Custom Header TRP_UpdateTest = OK", (m.customHeaders.TRP_UpdateTest ?: [""])[0].startsWith("OK"), m.customHeaders)
        check("work note gravada", notes(id).any { it.contains("Teste de conexão do CPI") }, notes(id))
        Map after = rec(id)
        List<String> changed = after.keySet().findAll { after[it] != before[it] && it != "u_cpi_message" } as List<String>
        check("nenhum campo de negócio do chamado mudou", changed.isEmpty(), changed)
    }

    void t03_kidErrado() {
        setup()
        f03.params.SNOW_JWT_KID = "kid-que-nao-existe"
        f99.fire()
        Mpl m3 = tenant.mpls.find { it.iflow == f03.name }
        check("IF_TRP_03 Failed com HTTP 401", m3.status == "FAILED" && m3.error.contains("401"), m3)
        check("IF_TRP_99 Failed", tenant.mpls.find { it.iflow == f99.name }.status == "FAILED")
    }

    void t04_chaveNaoCasaComCertificado() {
        setup()
        tenant.keystore = [trp_snow_jwt: otherKey]
        f99.fire()
        Mpl m3 = tenant.mpls.find { it.iflow == f03.name }
        check("IF_TRP_03 Failed com HTTP 401", m3.status == "FAILED" && m3.error.contains("401"), m3)
    }

    void t05_usuarioDoSubNaoExiste() {
        setup()
        f03.params.SNOW_JWT_SUB = "usuario_inexistente"
        f99.fire()
        check("IF_TRP_03 Failed", tenant.mpls.find { it.iflow == f03.name }.status == "FAILED")
    }

    void t06_aliasOuCredencialAusente() {
        setup()
        tenant.keystore = [:]
        f99.fire()
        Mpl m3 = tenant.mpls.find { it.iflow == f03.name }
        check("erro cita o alias do Keystore", m3.status == "FAILED" && m3.error.contains("trp_snow_jwt"), m3)
        setup()
        tenant.credentials.remove("TRP_SNOW_CLIENT")
        f99.fire()
        m3 = tenant.mpls.find { it.iflow == f03.name }
        check("erro cita a credencial", m3.status == "FAILED" && m3.error.contains("TRP_SNOW_CLIENT"), m3)
    }

    void t07_colunaSemPermissaoDeEscrita() {
        // O usuário de integração não pode gravar nas colunas u_cpi_* (ACL): o ServiceNow responde 200 e ignora.
        setup(["snow.write_denied": ["u_cpi_transport_status", "u_cpi_message"]])
        String id = ticket(u_cpi_transport_status: "")
        f99.params.TEST_SYS_ID = id
        f99.fire()
        Mpl m = tenant.mpls.find { it.iflow == f99.name }
        check("o teste de conexão acusa que a coluna não foi gravada", m.status == "FAILED" && (m.error ?: "").contains("não gravou"), m)
    }

    // ------------------------------------------------------------ Passo 11: ponta a ponta

    void t10_desligado() {
        setup()
        f01.params.TRP_ENABLED = "false"
        String id = ticket()
        List<Mpl> mpls = cycle()
        check("só o IF_TRP_01 executou", mpls*.iflow == [f01.name], mpls)
        check("nenhuma chamada saiu do CPI", reqs().isEmpty(), reqs()*.path)
        check("chamado continua PENDING", status(id) == "PENDING")
    }

    void t11_ligadoSemChamados() {
        setup()
        ticket(cat_item: "0123456789abcdef0123456789abcdef")                 // chamado de outro item de catálogo
        ticket(u_cpi_transport_status: "SUCCESS")                            // chamado nosso já encerrado
        List<Mpl> mpls = cycle()
        check("IF_TRP_01 e IF_TRP_03 Completed", mpls.every { it.status == "COMPLETED" } && mpls*.iflow.sort() == [f01.name, f03.name].sort(), mpls)
        check("IF_TRP_02 não executou", !mpls.any { it.iflow == f02.name })
        check("só o token e a busca chegaram ao ServiceNow", reqs("snow")*.method == ["POST", "GET"], reqs("snow")*.path)
        Map get = reqs("snow").find { it.method == "GET" }
        check("a busca chegou com o filtro inteiro", (get.params as Map).sysparm_query == "cat_item=" + CAT +
              "^u_cpi_transport_statusINPENDING,EXPORTING,WAITING_TR,IMPORTING,DEPLOYING^ORDERBYsys_created_on", (get.params as Map).sysparm_query)
        check("datas pedidas em UTC (display_value=false)", (get.params as Map).sysparm_display_value == "false")
    }

    void t12_camposInvalidos() {
        setup()
        String id = ticket(u_cpi_artifact_version: "abc")
        cycle()
        Map r = rec(id)
        check("FAILED", r.u_cpi_transport_status == "FAILED", r.u_cpi_transport_status)
        check("mensagem explica", (r.u_cpi_message as String).startsWith("Campos inválidos"), r.u_cpi_message)
        check("o Flow encaminhou ao suporte", r.assignment_group == "suporte-integracao")
        check("nada foi chamado no cTMS nem no CPI QAS", reqs("ctms").isEmpty() && reqs("cpiqas").isEmpty())
    }

    void t13_caminhoFeliz() {
        setup()
        String id = ticket()
        String number = rec(id).number
        List<Mpl> c1 = cycle()
        check("1º ciclo: WAITING_TR", status(id) == "WAITING_TR", status(id))
        check("a work note pede o clique com o nº do chamado", notes(id)[0].contains("Clique em Transport") && notes(id)[0].contains(number), notes(id))
        check("Application ID = nº do chamado", c1.find { it.iflow == f02.name }?.applicationId == number, c1)
        cycle()
        check("sem o clique, continua esperando e não repete a work note", status(id) == "WAITING_TR" && notes(id).size() == 1, notes(id))
        int tr = transport("Ajuste no mapeamento - " + number)
        List<String> trail = drive(id)
        Map r = rec(id)
        Map st = Ctl.state()
        check("estados na ordem do guia", trail == ["WAITING_TR", "IMPORTING", "DEPLOYING", "SUCCESS"], trail)
        check("u_cpi_tr_id = request importada", r.u_cpi_tr_id == (tr as String), r.u_cpi_tr_id)
        check("u_cpi_action_id preenchido", (r.u_cpi_action_id as String) ==~ /\d+/, r.u_cpi_action_id)
        check("uma work note por mudança de estado", notes(id).size() == 4, notes(id))
        check("uma gravação no chamado por mudança de estado", reqs("snow").count { it.method == "PATCH" } == 4)
        check("a request saiu da fila como importada", (st.ctms.queues["2"] as List<Map>).find { it.id == tr }.status == "succeeded")
        check("import feito com o usuário nomeado", (st.ctms.actions.values() as List<Map>)[0].triggeredBy == "cpi-orquestracao")
        Map rt = st.cpiqas.runtime.IF_Pedidos_SAP as Map
        check("iFlow STARTED em QAS na versão pedida", rt.Status == "STARTED" && rt.Version == "1.0.3", rt)
        check("o Flow fechou o chamado", r.state == "3", r.state)
        check("todas as execuções Completed", tenant.mpls.every { it.status == "COMPLETED" }, tenant.mpls.findAll { it.status != "COMPLETED" })
        check("um único deploy disparado", reqs("cpiqas").count { it.method == "POST" } == 1)
        cycle()
        check("depois de SUCCESS o chamado não é mais processado", !cycle().any { it.iflow == f02.name })
    }

    void t14_semClique() {
        setup()
        f02.params.TIMEOUT_WAITING_TR_MIN = "4"
        String id = ticket()
        cycle()
        rewindStage(id, 3)
        cycle()
        check("dentro do prazo continua WAITING_TR", status(id) == "WAITING_TR", status(id))
        rewindStage(id, 6)
        cycle()
        Map r = rec(id)
        check("prazo vencido: FAILED", r.u_cpi_transport_status == "FAILED" && (r.u_cpi_message as String).contains("Prazo do estado WAITING_TR vencido"), r.u_cpi_message)
    }

    void t15_versaoDiferente() {
        setup()
        String id = ticket(u_cpi_artifact_version: "9.9.9")
        cycle()
        transport(rec(id).number as String, [version: "1.0.3"])
        List<String> trail = drive(id)
        Map r = rec(id)
        check("FAILED", trail[-1] == "FAILED", trail)
        check("mensagem cita as duas versões", (r.u_cpi_message as String).contains("1.0.3") && (r.u_cpi_message as String).contains("9.9.9"), r.u_cpi_message)
    }

    void t16_iflowNaoSobe() {
        setup()
        String id = ticket()
        cycle()
        transport(rec(id).number as String, [broken: true])
        List<String> trail = drive(id)
        check("FAILED com ERROR em QAS", trail[-1] == "FAILED" && (rec(id).u_cpi_message as String).contains("ficou em ERROR"), rec(id).u_cpi_message)
    }

    void t17_requestDeOutroDev() {
        setup()
        String id = ticket()
        String number = rec(id).number
        cycle()
        int alheia = transport("Correção urgente do time B", [artifact: "IF_Outro", package: "Z_OUTRO", version: "2.0.0"])
        int parecida = transport("Ref. " + number + "9 (outro chamado)", [artifact: "IF_Parecido", package: "Z_OUTRO", version: "1.0.0"])
        int minha = transport("[" + number + "] ajuste", [:])
        List<String> trail = drive(id)
        Map st = Ctl.state()
        List<Map> queue = st.ctms.queues["2"] as List<Map>
        check("SUCCESS", trail[-1] == "SUCCESS", trail + [rec(id).u_cpi_message])
        check("a request do chamado foi importada", queue.find { it.id == minha }.status == "succeeded")
        check("a request do outro dev continua na fila", queue.find { it.id == alheia }.status == "initial")
        check("request com número parecido não foi confundida", queue.find { it.id == parecida }.status == "initial")
        check("o iFlow do outro dev não foi tocado em QAS", !(st.cpiqas.designtime as Map).containsKey("IF_Outro"))
    }

    void t18_duasRequestsDoMesmoChamado() {
        setup()
        String id = ticket()
        cycle()
        transport(rec(id).number as String)
        transport((rec(id).number as String) + " de novo")
        cycle()
        check("FAILED explicando a duplicidade", status(id) == "FAILED" && (rec(id).u_cpi_message as String).contains("mais de uma"), rec(id).u_cpi_message)
        check("nenhum import disparado", !reqs("ctms").any { it.method == "POST" })
    }

    void t19_erroPassageiro() {
        setup()
        String id = ticket()
        cycle()
        transport(rec(id).number as String)
        fault("ctms", "GET", "/transportRequests\$", 503, 2)
        cycle(); cycle()
        Map r = rec(id)
        check("duas tentativas contadas, estado mantido", r.u_cpi_transport_status == "WAITING_TR" && r.u_cpi_retries == "2", r)
        check("execuções ficam Completed (erro tratado)", tenant.mpls.findAll { it.iflow == f02.name }.every { it.status == "COMPLETED" })
        check("erro visível no monitor (TRP_Error)", tenant.mpls.any { it.customHeaders.TRP_Error }, tenant.mpls*.customHeaders)
        List<String> trail = drive(id)
        check("depois segue até SUCCESS", trail[-1] == "SUCCESS", trail)
        check("tentativas zeradas na mudança de estado", rec(id).u_cpi_retries == "0", rec(id).u_cpi_retries)
    }

    void t20_erroPassageiroEsgota() {
        setup()
        String id = ticket()
        cycle()
        transport(rec(id).number as String)
        fault("ctms", "GET", "/transportRequests\$", 503, 99)
        List<String> trail = drive(id, 8)
        Map r = rec(id)
        check("vira FAILED na 5ª tentativa", trail[-1] == "FAILED" && reqs("ctms").count { it.status == 503 } == 5, [trail, reqs("ctms").count { it.status == 503 }])
        check("mensagem traz o HTTP 503", (r.u_cpi_message as String).contains("HTTP 503"), r.u_cpi_message)
    }

    void t21_importComErro() {
        setup(["ctms.import_result": "error"])
        String id = ticket()
        cycle()
        int tr = transport(rec(id).number as String)
        List<String> trail = drive(id)
        check("FAILED citando a request e a ação", trail[-1] == "FAILED" && (rec(id).u_cpi_message as String).contains("Import da request " + tr), rec(id).u_cpi_message)
        check("nenhum deploy disparado", !reqs("cpiqas").any { it.method == "POST" })
    }

    void t22_credencialDoCtmsErrada() {
        setup()
        tenant.credentials.TRP_CTMS.clientSecret = "segredo-errado"
        String id = ticket()
        cycle(); cycle()
        check("FAILED com o nome da credencial", status(id) == "FAILED" && (rec(id).u_cpi_message as String).contains("TRP_CTMS"), rec(id).u_cpi_message)
    }

    void t23_semRoleDeDeploy() {
        setup(["xsuaa.clients.sb-cpiqas-lab.scopes": ["WorkspacePackagesRead", "MonitoringDataRead"]])
        String id = ticket()
        cycle()
        transport(rec(id).number as String)
        List<String> trail = drive(id)
        check("FAILED com HTTP 403", trail[-1] == "FAILED" && (rec(id).u_cpi_message as String).contains("HTTP 403"), rec(id).u_cpi_message)
    }

    void t24_deployExigeCsrf() {
        setup(["cpiqas.require_csrf": true])
        String id = ticket()
        cycle()
        transport(rec(id).number as String)
        List<String> trail = drive(id)
        check("FAILED deixando claro que é CSRF", trail[-1] == "FAILED" && (rec(id).u_cpi_message as String).contains("CSRF"), rec(id).u_cpi_message)
    }

    void t25_servicenowForaDoArNaGravacao() {
        // O import é disparado, mas a gravação do novo estado falha. O chamado não pode ficar perdido.
        setup()
        String id = ticket()
        cycle()
        int tr = transport(rec(id).number as String)
        fault("snow", "PATCH", "/api/now/table/", 503, 2)     // falha a gravação e a nova tentativa do tratamento de erro
        List<Mpl> c = cycle()
        check("execução do chamado fica Failed no monitor", c.find { it.iflow == f02.name }?.status == "FAILED", c)
        check("o chamado não mudou", status(id) == "WAITING_TR", status(id))
        check("o import já tinha sido disparado", reqs("ctms").count { it.method == "POST" } == 1)
        List<String> trail = drive(id)
        check("os ciclos seguintes recuperam e chegam a SUCCESS", trail[-1] == "SUCCESS", trail + [rec(id).u_cpi_message])
        check("a request não foi importada duas vezes", reqs("ctms").count { it.method == "POST" && it.status == 200 } == 1)
        check("u_cpi_tr_id ficou gravado", rec(id).u_cpi_tr_id == (tr as String), rec(id).u_cpi_tr_id)
    }

    void t26_buscaFalha() {
        setup()
        String id = ticket()
        fault("snow", "GET", "/api/now/table/", 503, 1)
        List<Mpl> c = cycle()
        check("IF_TRP_01 Failed (alerta no monitor)", c.find { it.iflow == f01.name }.status == "FAILED", c)
        check("nenhum chamado mudou", status(id) == "PENDING")
        cycle()
        check("o ciclo seguinte segue normal", status(id) == "WAITING_TR", status(id))
    }

    void t27_tokenFalha() {
        setup()
        String id = ticket()
        fault("snow", "POST", "/oauth_token.do", 503, 1)
        List<Mpl> c = cycle()
        check("IF_TRP_01 e IF_TRP_03 Failed", c.every { it.status == "FAILED" } && c.size() == 2, c)
        check("nada mais foi chamado", reqs().size() == 1, reqs()*.path)
        check("chamado intacto", status(id) == "PENDING")
    }

    void t28_variosChamadosUmComProblema() {
        setup()
        String a = ticket(u_cpi_artifact_id: "IF_A")
        String b = ticket(u_cpi_artifact_id: "IF_B", u_cpi_transport_status: "IMPORTING",
                          u_cpi_stage_since: utc(System.currentTimeMillis()), u_cpi_tr_id: "1", u_cpi_action_id: "999999")
        String c = ticket(u_cpi_artifact_id: "IF_C")
        cycle()
        check("A avançou", status(a) == "WAITING_TR", status(a))
        check("B (ação inexistente no cTMS) virou FAILED com HTTP 404", status(b) == "FAILED" && (rec(b).u_cpi_message as String).contains("HTTP 404"), rec(b).u_cpi_message)
        check("C avançou mesmo com o erro de B", status(c) == "WAITING_TR", status(c))
        transport(rec(a).number as String, [artifact: "IF_A"])
        transport(rec(c).number as String, [artifact: "IF_C"])
        check("A e C chegam a SUCCESS", drive(a)[-1] == "SUCCESS" && drive(c)[-1] == "SUCCESS", [status(a), status(c)])
    }

    void t29_colunasNaoCriadas() {
        // O time do ServiceNow ainda não criou u_cpi_transport_status: o filtro é ignorado e a tabela inteira volta.
        setup()
        String alheio = ticket(cat_item: "0123456789abcdef0123456789abcdef", u_cpi_transport_status: "")
        String antigo = ticket(u_cpi_transport_status: "")      // chamado do nosso item, sem estado (coluna não existe)
        Map st = Ctl.state()
        List<String> cols = (st.snow.columns as List<String>) - "u_cpi_transport_status"
        Ctl.post("/_lab/set", ["snow.columns": cols])
        List<Mpl> c = cycle()
        Mpl m = c.find { it.iflow == f01.name }
        check("IF_TRP_01 Failed com aviso claro", m.status == "FAILED" && (m.error ?: "").contains("fora do filtro"), m)
        check("nenhum chamado foi tocado", notes(alheio).isEmpty() && notes(antigo).isEmpty() && !reqs("snow").any { it.method == "PATCH" })
    }

    void t30_estadoNaoGrava() {
        // ACL impede gravar u_cpi_transport_status. Sem conferência, o chamado ficaria em PENDING para sempre.
        setup(["snow.write_denied": ["u_cpi_transport_status"]])
        String id = ticket()
        List<Mpl> c = cycle()
        Mpl m = c.find { it.iflow == f02.name }
        check("execução Failed dizendo que o ServiceNow não gravou", m.status == "FAILED" && (m.error ?: "").contains("não gravou"), m)
        cycle()
        check("no máximo uma work note por ciclo", notes(id).size() <= 2, notes(id).size())
    }

    void t31_redeployDaMesmaVersao() {
        // QAS já tem a mesma versão em STARTED (deploy de ontem). O sucesso só vale para o deploy deste chamado.
        setup(["cpiqas.deploy_404_polls": 2])
        Ctl.post("/_lab/set", ["cpiqas.runtime": [IF_Pedidos_SAP: [Version: "1.0.3", Status: "STARTED",
                                                                   DeployedOn: System.currentTimeMillis() - 86400000L]]])
        String id = ticket()
        cycle()
        transport(rec(id).number as String)
        for (int i = 0; i < 8 && status(id) != "DEPLOYING"; i++) cycle()
        check("chegou a DEPLOYING", status(id) == "DEPLOYING", [status(id), rec(id).u_cpi_message])
        cycle()
        check("runtime ainda mostra o deploy antigo: continua DEPLOYING", status(id) == "DEPLOYING", status(id))
        check("SUCCESS só com o deploy novo", drive(id)[-1] == "SUCCESS", rec(id).u_cpi_message)
    }

    void t32_statusDoCtmsEmCodigo() {
        setup(["ctms.status_style": "code"])
        String id = ticket()
        cycle()
        transport(rec(id).number as String)
        check("SUCCESS com status IN/RU/SU", drive(id)[-1] == "SUCCESS", rec(id).u_cpi_message)
    }

    void t33_iflowAuxiliarNaoImplantado() {
        setup()
        String id = ticket()
        IFlow removed = tenant.byAddress.remove("/trp/snow-update")
        try {
            List<Mpl> c = cycle()
            check("IF_TRP_02 Failed citando o Address", c.find { it.iflow == f02.name }.error.contains("/trp/snow-update"), c)
        } finally {
            tenant.byAddress["/trp/snow-update"] = removed
        }
        check("chamado intacto", status(id) == "PENDING")
    }

    void t34_ciclosSobrepostos() {
        // Dois disparos do Timer ao mesmo tempo sobre o mesmo chamado, no momento do import.
        setup()
        String id = ticket()
        cycle()
        int tr = transport(rec(id).number as String)
        List<Thread> ts = (1..2).collect { Thread.start { f01.fire() } }
        ts*.join()
        check("o chamado não vira FAILED por causa da corrida", status(id) != "FAILED", rec(id).u_cpi_message)
        check("chega a SUCCESS", drive(id)[-1] == "SUCCESS", rec(id).u_cpi_message)
        check("a request foi importada uma única vez", reqs("ctms").count { it.method == "POST" && it.status == 200 } == 1)
    }

    void t35_tokenDoServiceNowExpiraNoMeio() {
        setup()
        String id = ticket()
        fault("snow", "PATCH", "/api/now/table/", 401, 2, '{"error":{"message":"User Not Authenticated","detail":"Required to provide Auth information"},"status":"failure"}')
        List<Mpl> c = cycle()
        check("execução Failed no monitor, sem laço", c.find { it.iflow == f02.name }.status == "FAILED" && reqs("snow").count { it.method == "PATCH" } == 2, c)
        cycle()
        check("o ciclo seguinte pega token novo e segue", status(id) == "WAITING_TR", status(id))
    }

    void t36_limiteDeChamadosPorCiclo() {
        setup()
        f01.params.SNOW_LIMIT = "3"
        List<String> ids = (1..5).collect { ticket() }
        cycle()
        check("só os 3 mais antigos neste ciclo", ids.collect { status(it) } == ["WAITING_TR"] * 3 + ["PENDING"] * 2, ids.collect { status(it) })
        ids.take(3).each { transport(rec(it).number as String) }
        check("quando os primeiros terminam, os seguintes entram", drive(ids[0])[-1] == "SUCCESS" && drive(ids[4], 4).contains("WAITING_TR"),
              ids.collect { status(it) })
    }

    // ------------------------------------------------------------ Etapa B (API do Content Agent: HIPOTÉTICA)

    void t40_etapaB_exportPorApi() {
        setup()
        Ctl.post("/_lab/set", ["cas.designtime_dev": [IF_Pedidos_SAP: [version: "1.0.3"]]])
        f02.params.putAll([EXPORT_MODE: "API", TIMEOUT_EXPORT_MIN: "15", CAS_DEV_URL: "https://127.0.0.1:18085",
                           CAS_EXPORT_PATH: "/lab/export", CAS_STATUS_PATH: "/lab/export/#OPERATION_ID#",
                           CAS_EXPORT_BODY_TEMPLATE: '{"packageId":"#PACKAGE_ID#","artifactId":"#ARTIFACT_ID#","description":"#TICKET#"}',
                           CAS_OPID_JSONPATH: "id", CAS_STATUS_JSONPATH: "status", CAS_TRID_JSONPATH: "transportRequestId",
                           CAS_DONE_VALUES: "-", CAS_FAILED_VALUES: "-"])
        ["TIMEOUT_EXPORT_MIN", "CAS_DEV_URL", "CAS_EXPORT_PATH", "CAS_STATUS_PATH", "CAS_EXPORT_BODY_TEMPLATE", "CAS_OPID_JSONPATH",
         "CAS_STATUS_JSONPATH", "CAS_TRID_JSONPATH", "CAS_DONE_VALUES", "CAS_FAILED_VALUES"].each { f02.config[it] = "{{" + it + "}}" }
        String id = ticket()
        List<String> trail = drive(id)
        check("sem clique: PENDING -> EXPORTING -> IMPORTING -> DEPLOYING -> SUCCESS",
              trail == ["PENDING", "EXPORTING", "IMPORTING", "DEPLOYING", "SUCCESS"], trail + [rec(id).u_cpi_message])
    }

    // ------------------------------------------------------------ execução

    Map<String, Closure> all() {
        Map<String, Closure> m = [:]
        this.metaClass.methods.findAll { it.name ==~ /t\d\d_.*/ }.sort { it.name }.each { mm -> m[mm.name] = { -> this."${mm.name}"() } }
        m
    }

    void run(String only = null) {
        all().each { String name, Closure c ->
            if (only && !name.contains(only)) return
            scenario = name
            try {
                c.call()
                commonChecks()
            } catch (Throwable e) {
                failedScenarios << name
                StackTraceElement at = e.stackTrace.find { it.className.startsWith("lab.") && it.lineNumber > 0 }
                failures << (name + ": EXCEÇÃO " + e + (at ? " (" + at.fileName + ":" + at.lineNumber + ")" : ""))
            }
        }
    }

    static void main(String[] args) {
        String scripts = args[0], keys = args[1]
        String mode = args.length > 2 ? args[2] : "todas"
        String only = args.length > 3 ? args[3] : null
        Scenarios s = new Scenarios(scripts, keys)
        List<Modes> combos = mode == "padrao" ? [new Modes()] : (mode == "extremos" ? Modes.extremes() : Modes.all())
        int total = 0
        Map<String, Integer> failsByScenario = [:].withDefault { 0 }
        Map<String, String> sample = [:]
        int combosOk = 0
        combos.each { Modes m ->
            s.tenant.modes = m
            s.checks = 0; s.failures = []; s.failedScenarios = [] as Set
            s.run(only)
            total += s.checks
            if (s.failures.isEmpty()) combosOk++
            if (combos.size() > 1) { print(s.failures.isEmpty() ? "." : "F"); System.out.flush() }
            s.failures.each { String f ->
                String sc = f.substring(0, f.indexOf(":"))
                if (!sample.containsKey(f.take(110))) sample[f.take(110)] = "[" + m + "] " + f
            }
            s.failedScenarios.each { failsByScenario[it]++ }
            if (combos.size() == 1) s.failures.each { println "  FALHOU " + it.take(420) }
        }
        int nScen = s.all().keySet().findAll { !only || it.contains(only) }.size()
        println ""
        println "Cenários: " + nScen + " | combinações de comportamento do tenant: " + combos.size() + " | conferências: " + total
        println "Combinações em que TODOS os cenários passaram: " + combosOk + " de " + combos.size()
        if (failsByScenario) {
            println ""
            println "Cenários com falha (em quantas combinações):"
            failsByScenario.sort().each { k, v -> println "  " + k + ": " + v + " de " + combos.size() }
            if (combos.size() > 1) {
                println ""
                println "Exemplos:"
                sample.values().take(25).each { println "  " + it.take(420) }
            }
        }
        s.tenant.shutdown()
        System.exit(failsByScenario ? 1 : 0)
    }
}
