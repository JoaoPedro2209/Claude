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
