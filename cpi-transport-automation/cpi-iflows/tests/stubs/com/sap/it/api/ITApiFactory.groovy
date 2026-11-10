package com.sap.it.api
class ITApiFactory {
    static Map<Class, Object> registry = [:]
    static <T> T getService(Class<T> c, Object ctx) { (T) registry[c] }
}
