package com.ffps.fserv

import org.json.JSONArray
import org.json.JSONObject

/** Короткий конструктор JSONObject: obj("a" to 1, "b" to null). */
fun obj(vararg p: Pair<String, Any?>): JSONObject {
    val o = JSONObject()
    for ((k, v) in p) o.put(k, v ?: JSONObject.NULL)
    return o
}

fun arr(items: Iterable<Any?>): JSONArray {
    val a = JSONArray()
    for (i in items) a.put(i ?: JSONObject.NULL)
    return a
}
