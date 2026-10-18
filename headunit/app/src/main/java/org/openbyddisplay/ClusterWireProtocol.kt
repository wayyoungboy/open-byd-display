package org.openbyddisplay

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Independent services have no account, VIP, business license, or vendor token. */
object ClusterWireProtocol {
    fun encode(body: JSONObject): String {
        require(!body.has("token") && !body.has("client_time")) { "自研接口不接收商业授权字段" }
        return body.toString()
    }

    internal fun readLine(input: InputStream, maxBytes: Int = 65536): String? {
        val bytes = ByteArrayOutputStream()
        while (bytes.size() <= maxBytes) {
            val value = input.read()
            if (value < 0) return null
            if (value == 10) return bytes.toString("UTF-8").removeSuffix("\r")
            bytes.write(value)
        }
        error("仪表回复超出长度限制")
    }
}
