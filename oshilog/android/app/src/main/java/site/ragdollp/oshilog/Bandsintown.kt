package site.ragdollp.oshilog

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Bandsintown API 連携（アーティストの今後の公演を取得）。
 *
 * ⚠️ app_id は登録制です。未登録の app_id だと API 側で拒否されます。
 *    自分の app_id を取得して APP_ID を差し替えてください
 *    （artists.bandsintown.com で登録 / 商用は api@bandsintown.com に申請）。
 */
data class BitEvent(val date: String, val title: String, val venue: String, val city: String, val country: String)

sealed class BitResult {
    data class Ok(val events: List<BitEvent>) : BitResult()
    object Unauthorized : BitResult()          // app_id 未登録など
    data class Error(val msg: String) : BitResult()
}

object Bandsintown {

    // ユーザー提供のID。※ Bandsintown の公開REST APIは現在この値でも拒否される場合があり
    //   （API用 app_id はアカウントIDとは別の可能性 / 公開エンドポイントの制限）、
    //   正式な API app_id が判明したらここを差し替える。
    private const val APP_ID = "1788430"
    private const val BASE = "https://rest.bandsintown.com/artists/"

    suspend fun fetchEvents(artist: String): BitResult = withContext(Dispatchers.IO) {
        try {
            val name = URLEncoder.encode(artist, "UTF-8").replace("+", "%20")
            val url = URL(BASE + name + "/events?app_id=" + APP_ID + "&date=upcoming")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 12000
                readTimeout = 12000
                setRequestProperty("Accept", "application/json")
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            conn.disconnect()

            if (code == 403 || body.contains("not authorized", true)) return@withContext BitResult.Unauthorized
            if (code !in 200..299) return@withContext BitResult.Error("HTTP $code")
            val trimmed = body.trim()
            if (!trimmed.startsWith("[")) {
                // エラーJSON（{"errorMessage":...} など）
                return@withContext if (trimmed.contains("not authorized", true)) BitResult.Unauthorized
                else BitResult.Ok(emptyList())
            }

            val arr = JSONArray(trimmed)
            val list = ArrayList<BitEvent>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val dt = o.optString("datetime", "")
                val date = if (dt.length >= 10) dt.substring(0, 10) else ""
                val v = o.optJSONObject("venue")
                val venue = v?.optString("name", "") ?: ""
                val city = v?.optString("city", "") ?: ""
                val country = v?.optString("country", "") ?: ""
                var title = o.optString("title", "")
                if (title.isBlank()) title = artist
                if (date.isNotEmpty()) list.add(BitEvent(date, title, venue, city, country))
            }
            BitResult.Ok(list)
        } catch (e: Exception) {
            BitResult.Error(e.message ?: "通信エラー")
        }
    }
}
