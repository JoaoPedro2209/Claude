/*
 * TRP_04_SnowUpdate.groovy
 * iFlow: IF_TRP_04_SnowUpdate
 *
 * Entrada (ProcessDirect /trp/snow-update):
 *   header X-Trp-Snow-Token : access token do ServiceNow
 *   corpo                   : {"sys_id": "...", "fields": {"u_cpi_transport_status": "...", "work_notes": "..."}}
 *
 * Função:
 *   prepareUpdate -> prepara o PATCH na Table API
 */
import com.sap.gateway.ip.core.customdev.util.Message
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

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
    message.setHeader("X-Trp-Snow-Token", "")
    message.setHeader("Authorization", "Bearer " + token)
    message.setHeader("Content-Type", "application/json")
    message.setHeader("Accept", "application/json")
    message.setBody(JsonOutput.toJson(req.fields ?: [:]))
    return message
}
