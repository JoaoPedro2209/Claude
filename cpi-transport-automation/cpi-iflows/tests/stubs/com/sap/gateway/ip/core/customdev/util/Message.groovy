package com.sap.gateway.ip.core.customdev.util
/** Versão simulada da classe Message do CPI, só para os testes locais. */
class Message {
    private Object body = ""
    private Map<String, Object> headers = [:]
    private Map<String, Object> properties = [:]
    def <T> T getBody(Class<T> c) { body == null ? null : body.asType(c) }
    void setBody(Object b) { body = b }
    Map<String, Object> getHeaders() { headers }
    void setHeader(String k, Object v) { headers[k] = v }
    Object getProperty(String k) { properties[k] }
    void setProperty(String k, Object v) { properties[k] = v }
    Map<String, Object> getProperties() { properties }
}
