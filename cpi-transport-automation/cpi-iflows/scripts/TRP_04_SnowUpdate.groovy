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
