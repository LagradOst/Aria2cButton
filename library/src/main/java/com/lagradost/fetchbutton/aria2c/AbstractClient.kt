package com.lagradost.fetchbutton.aria2c

import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.MutableLiveData
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit


abstract class AbstractClient(
    protected val profile: Profile,
    prebuiltClient: OkHttpClient? = null
) {
    @Volatile
    private var requestId: Long = 0

    data class Profile(
        val serverSsl: Boolean,
        val serverAddr: String,
        val serverPort: Int,
        val serverEndpoint: String,
        val timeout: Int,
        val token: String?,
        val statusUpdateRateMs: Long,
    )


    data class DownloadData(
        val status: DownloadStatus,
        val gid: String,
    )

    object DownloadListener {
        //private val currentDownloadData: HashMap<String, DownloadStatus> = hashMapOf()
        private val downloadDataUpdateCount = MutableLiveData<Int>()
        //private val currentDownloadDataMutex = Mutex()

        val currentDownloadStatus: HashMap<String, JsonTell> = hashMapOf()

        // this points to the parent gid
        //val follow: HashMap<String, String> = hashMapOf()

        private fun getStatus(gid: String): JsonTell? {
            return currentDownloadStatus[gid]
        }

        fun observe(scope: LifecycleOwner, collector: (Int) -> Unit) {
            CoroutineScope(Dispatchers.Main).launch {
                downloadDataUpdateCount.observe(scope) {
                    collector(it)
                }
            }
        }

        //fun getDownloadStatus(gid: String): DownloadStatus? {
        //    return currentDownloadData[gid]
        //}

        fun addCount(new: Int) {
            downloadDataUpdateCount.postValue(new)
        }

        fun getInfo(gid: String): ArrayList<JsonTell> {
            val out = ArrayList<JsonTell>()
            val current = getStatus(gid) ?: return arrayListOf()
            out.add(current)
            for (followers in current.followedBy) {
                out.addAll(getInfo(followers))
            }
            return out
        }

        // returns if gid was added
        /*suspend fun insertGid(gid: String): Boolean {
            gidMutex.withLock {
                if (gids.contains(gid)) {
                    return false
                } else {
                    gids += gid
                    return true
                }
            }
        }*/

        //suspend fun setDownloadStatus(gid: String, status: DownloadStatus) {
        //    currentDownloadDataMutex.lock {
        //        currentDownloadData[gid] = status
        //    }
        //    // return insertGid(gid)
        //}
    }

    protected val scope = CoroutineScope(Job() + Dispatchers.IO)


    //https://aria2.github.io/manual/en/html/aria2c.html
    protected fun getDownloadStatus(str: String?): DownloadStatus? {
        return when (str) {
            "aria2.onDownloadStart" -> DownloadStatus.Started
            "aria2.onDownloadPause" -> DownloadStatus.Paused
            "aria2.onDownloadStop" -> DownloadStatus.Stopped
            "aria2.onDownloadComplete" -> DownloadStatus.Completed
            "aria2.onDownloadError" -> DownloadStatus.Error
            "aria2.onBtDownloadComplete" -> DownloadStatus.CompletedAndStartSeed
            else -> null
        }
    }

    enum class Method(val methodName: String) {
        MULTI_CALL("system.multicall"),
        SHUT_DOWN("aria2.shutdown"),
        TELL_STATUS("aria2.tellStatus"),
        TELL_ACTIVE("aria2.tellActive"),
        TELL_WAITING("aria2.tellWaiting"),
        TELL_STOPPED("aria2.tellStopped"),
        UNPAUSE("aria2.unpause"),
        REMOVE("aria2.remove"),
        FORCE_PAUSE("aria2.forcePause"),
        FORCE_REMOVE("aria2.forceRemove"),
        REMOVE_RESULT("aria2.removeDownloadResult"),
        GET_VERSION("aria2.getVersion"),
        PAUSE_ALL("aria2.pauseAll"),
        GET_SESSION_INFO("aria2.getSessionInfo"),
        SAVE_SESSION("aria2.saveSession"),
        UNPAUSE_ALL("aria2.unpauseAll"),
        FORCE_PAUSE_ALL("aria2.forcePauseAll"),
        PURGE_DOWNLOAD_RESULTS("aria2.purgeDownloadResult"),
        PAUSE("aria2.pause"),
        LIST_METHODS("system.listMethods"),
        GET_GLOBAL_STATS("aria2.getGlobalStat"),
        GET_GLOBAL_OPTIONS("aria2.getGlobalOption"),
        CHANGE_GLOBAL_OPTIONS("aria2.changeGlobalOption"),
        ADD_URI("aria2.addUri"),
        ADD_TORRENT("aria2.addTorrent"),
        ADD_METALINK("aria2.addMetalink"),
        GET_SERVERS("aria2.getServers"),
        GET_PEERS("aria2.getPeers"),
        GET_DOWNLOAD_OPTIONS("aria2.getOption"),
        GET_FILES("aria2.getFiles"),
        CHANGE_POSITION("aria2.changePosition"),
        CHANGE_DOWNLOAD_OPTIONS("aria2.changeOption");
    }

    data class AriaRequest(
        val method: Method,
        val params: Array<Any>,
        var id: Long
    ) {
        override fun equals(other: Any?): Boolean {
            return other is AriaRequest && other.id == id
        }

        override fun hashCode(): Int {
            var result = method.hashCode()
            result = 31 * result + params.contentHashCode()
            result = 31 * result + id.hashCode()
            return result
        }
    }

    data class JsonStatus(
        @JsonProperty("gid") val gid: String,
    )

    private val tellKeys = JSONArray().apply {
        listOf(
            "totalLength",
            "gid",
            "status",
            "pieceLength",
            "numPieces",
            "dir",
            //"bittorrent",
            "completedLength",
            "downloadSpeed",
            "uploadSpeed",
            "connections",
            "uploadLength",
            "numSeeders",
            "files",
            "errorCode",
            "errorMessage",
            "followedBy",
            "following",
            "belongsTo"
        ).forEach {
            put(it)
        }
    }

    data class JsonTell(
        @JsonProperty("totalLength") val totalLength: Long,
        @JsonProperty("completedLength") val completedLength: Long,
        @JsonProperty("gid") val gid: String,
        @JsonProperty("following") val following: String?,
        @JsonProperty("status") val status: String?,
        @JsonProperty("followedBy") val followedBy: ArrayList<String> = arrayListOf(),
    )

    @Volatile
    var isStatusUpdateRunning = false
    private val updateMutex = Mutex()
    private var updateCount = 0

    fun run(block: suspend AbstractClient.() -> Unit) = scope.launch {
        block.invoke(this@AbstractClient)
    }

    suspend fun forceUpdate() {
        updateMutex.withLock {
            batchRequestStatus().forEach { resultList ->
                resultList.getOrNull()?.results?.forEach { json ->
                    DownloadListener.currentDownloadStatus[json.gid] = json
                }
            }
            DownloadListener.addCount(++updateCount)
        }
    }

    fun initStatusUpdateLoop() = scope.launch {
        isStatusUpdateRunning = true
        val ms = profile.statusUpdateRateMs
        while (isStatusUpdateRunning) {
            forceUpdate()
            delay(ms)
        }
    }

    data class JsonTellParent(
        //val id : String,
        @JsonProperty("result") val results: List<JsonTell>
    )

    // returns in the order active, waiting and stopped
    private suspend fun batchRequestStatus():
            List<Result<JsonTellParent>> = coroutineScope {
        //send(
        //    createMultiRequest(
        //        createTellActiveRequest(),
        //        createTellWaitingRequest(),
        //        createTellStoppedRequest()
        //    )
        //)

        return@coroutineScope listOf(
            createTellActiveRequest(),
            createTellWaitingRequest(),
            createTellStoppedRequest()
        ).map { async { send<JsonTellParent>(it) } }.map { it.await() }
    }

    private fun createUriRequest(data: UriRequest) = createUriRequest(data.uris, data.headers)

    private fun createUriRequest(
        uri: String,
        headers: Map<String, String> = emptyMap()
    ): AriaRequest = createUriRequest(listOf(uri), headers)

    private fun createUriRequest(
        uris: List<String>,
        headers: Map<String, String> = emptyMap()
    ): AriaRequest =
        createRequest(
            Method.ADD_URI,
            JSONArray().apply { uris.forEach { uri -> put(uri) } }, // uris
            JSONObject().apply {
                if (headers.isNotEmpty()) {
                    val array = JSONArray()
                    for ((key, value) in headers) {
                        array.put("$key:$value")
                    }
                    put("header", array)
                }
            }, // options
            Int.MAX_VALUE // position, max to push to the last position within the queue
        )


    //private fun createMultiRequest(vararg args: AriaRequest): AriaRequest =
    //    createMultiRequestList(args.toList())

    /*private fun createMultiRequestList(requests: List<AriaRequest>): AriaRequest {
        val array = requests.map { req ->
            val request = JSONObject()
            request.put("method", req.method.methodName)
            val params = JSONArray()
            for (obj in req.params) params.put(obj)
            request.put("params", params)
            request
        }

        return AriaRequest(
            id = ++requestId,
            method = Method.MULTI_CALL,
            params = arrayOf(JSONArray().apply {
                array.forEach {
                    put(it)
                }
            })
        )
    }*/

    private fun createShutdownRequest(): AriaRequest = createRequest(Method.SHUT_DOWN)

    private fun createTellStatusRequest(gid: String): AriaRequest =
        createRequest(Method.TELL_STATUS, gid, tellKeys)

    private fun createTellActiveRequest(): AriaRequest =
        createRequest(Method.TELL_ACTIVE, tellKeys)

    private fun createTellWaitingRequest(): AriaRequest =
        createRequest(Method.TELL_WAITING, 0, Int.MAX_VALUE, tellKeys)

    private fun createTellStoppedRequest(): AriaRequest =
        createRequest(Method.TELL_STOPPED, 0, Int.MAX_VALUE, tellKeys)

    private fun createPauseRequest(gid: String): AriaRequest =
        createRequest(Method.FORCE_PAUSE, gid)

    private fun createUnPauseRequest(gid: String): AriaRequest = createRequest(Method.UNPAUSE, gid)
    private fun createRemoveRequest(gid: String): AriaRequest = createRequest(Method.REMOVE, gid)


    private fun createRequest(method: Method, vararg args: Any): AriaRequest =
        AriaRequest(id = ++requestId, method = method, params = arrayOf(*args))

    fun pause(gid: String, all: Boolean = true) = scope.launch {
        pauseAsync(gid, all)
    }

    suspend fun pauseAsync(gid: String, all: Boolean) {
        if (all) {
            DownloadListener.getInfo(gid)
                .forEach { item -> sendRaw(createPauseRequest(item.gid)) }
        } else {
            sendRaw(createPauseRequest(gid))
        }
    }

    fun unpause(gid: String, all: Boolean = true) = scope.launch {
        unpauseAsync(gid, all)
    }

    suspend fun unpauseAsync(gid: String, all: Boolean = true) {
        if (all) {
            DownloadListener.getInfo(gid)
                .forEach { item -> sendRaw(createUnPauseRequest(item.gid)) }
        } else {
            sendRaw(createUnPauseRequest(gid))
        }
    }

    fun remove(gid: String, all: Boolean = true) = scope.launch {
        removeAsync(gid, all)
    }

    suspend fun removeAsync(gid: String, all: Boolean = true) {
        if (all) {
            DownloadListener.getInfo(gid)
                .forEach { item -> sendRaw(createRemoveRequest(item.gid)) }
        } else {
            sendRaw(createRemoveRequest(gid))
        }
    }

    private fun buildRequest(req: AriaRequest): JSONObject {
        val request = JSONObject()
        request.put("jsonrpc", "2.0")
        request.put("id", req.id.toString())
        request.put("method", req.method.methodName)

        val params: JSONArray = JSONArray().apply {
            profile.token?.let { token ->
                put("token:$token")
            }
        }
        for (obj in req.params) params.put(obj)
        request.put("params", params)
        //println("SENT: $request")
        return request
    }

    enum class SendError {
        Timeout,
        Closed,
    }

    data class Aria2SendError(private val error: SendError) : Exception() {
        override fun toString(): String {
            return "${this.javaClass.simpleName} ($error)"
        }
    }

    protected val client = prebuiltClient ?: buildClient(profile)

    private fun buildClient(profile: Profile): OkHttpClient {
        val timeout = profile.timeout
        val builder = OkHttpClient.Builder()
        builder.connectTimeout(timeout.toLong(), TimeUnit.SECONDS)
            .readTimeout(timeout.toLong(), TimeUnit.SECONDS)
            .writeTimeout(timeout.toLong(), TimeUnit.SECONDS)
            .hostnameVerifier { _, _ -> true }

        return builder.build()
    }

    abstract fun connect()


    fun download(request: UriRequest, callback: (String?) -> Unit) {
        scope.launch {
            val result = sendUri(request)
            println("DOWNLODA >>> $result")
            if (result.isFailure) {
                result.exceptionOrNull()?.printStackTrace()
            }
            callback.invoke(result.getOrNull())
        }
    }

    suspend fun sendUri(request: UriRequest): Result<String> {
        val req = createUriRequest(request)
        return send<JsonIdResponse>(req).map { resp ->
            resp.resultGid
        }
    }


    suspend fun sendTellStatus(gid: String): Result<JsonStatus> {
        val req = createTellStatusRequest(gid)
        return send(req)
    }

    protected val mapper = JsonMapper.builder().addModule(KotlinModule())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false).build()!!

    protected inline fun <reified T> parseJson(value: String): T {
        return mapper.readValue(value)
    }

    protected inline fun <reified T> tryParseJson(value: String?, log: Boolean = false): T? {
        return try {
            parseJson(value ?: return null)
        } catch (e: Exception) {
            if (log)
                e.printStackTrace()
            null
        }
    }

    private suspend fun sendRaw(req: AriaRequest): Result<String> {
        return send(req.id.toString(), buildRequest(req))
    }

    private suspend inline fun <reified T> send(req: AriaRequest): Result<T> {
        return sendRaw(req).mapCatching { json ->
            parseJson(json)
        }
    }

    protected abstract suspend fun send(id: String, req: JSONObject): Result<String>
}