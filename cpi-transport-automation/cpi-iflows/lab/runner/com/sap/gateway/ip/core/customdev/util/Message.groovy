package com.sap.gateway.ip.core.customdev.util

import org.apache.camel.Exchange

/**
 * Versão de laboratório da classe Message do CPI, apoiada num Exchange de verdade do
 * Apache Camel (o motor do Cloud Integration). Corpo, headers e propriedades são os do
 * Exchange: conversão de tipos e headers sem diferença de maiúsculas funcionam como no runtime.
 */
class Message {
    final Exchange exchange

    Message(Exchange exchange) { this.exchange = exchange }

    def <T> T getBody(Class<T> c) { exchange.getIn().getBody(c) }
    Object getBody() { exchange.getIn().getBody() }
    void setBody(Object b) { exchange.getIn().setBody(b) }

    Map<String, Object> getHeaders() { exchange.getIn().getHeaders() }
    def <T> T getHeader(String k, Class<T> c) { exchange.getIn().getHeader(k, c) }
    void setHeader(String k, Object v) { exchange.getIn().setHeader(k, v) }
    void setHeaders(Map<String, Object> h) { exchange.getIn().setHeaders(h) }

    Object getProperty(String k) { exchange.getProperty(k) }
    void setProperty(String k, Object v) { exchange.setProperty(k, v) }
    Map<String, Object> getProperties() { exchange.getProperties() }
    void setProperties(Map<String, Object> p) { p.each { k, v -> exchange.setProperty(k, v) } }
}
