package `in`.nulltheory.waypoint.net

import `in`.nulltheory.waypoint.BuildConfig
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * One OkHttp client for the whole app. Both Photon and Nominatim throttle or reject requests
 * without an identifying User-Agent, so it is set here once rather than per call site.
 */
object Http {

    const val USER_AGENT =
        "Waypoint/${BuildConfig.VERSION_NAME} (+https://github.com/nulltheory/waypoint)"

    /**
     * Interactive budget, used by search. Deliberately generous: the target is a dashcam on
     * whatever wifi the bench has, where a request that ultimately succeeds can take 20-30s,
     * and a 10s connect timeout turns a slow-but-working link into an outright failure.
     * Search is debounced and cancels superseded requests, so only the last query ever waits.
     */
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(35, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("User-Agent", USER_AGENT)
                        .build()
                )
            }
            .build()
    }

    /**
     * Routing budget. Fetching a route is a one-shot commitment the user has already made by
     * picking a destination, and the result is cached forever afterwards, so it is worth
     * waiting a long time for on a bad link rather than failing at 30s and making them
     * start over. Shares the connection pool and dispatcher with [client].
     */
    val patientClient: OkHttpClient by lazy {
        client.newBuilder()
            .callTimeout(90, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}

/**
 * Suspends on an OkHttp call and cancels the underlying request when the coroutine is
 * cancelled. Search fires on every keystroke, so abandoned requests must actually go away.
 */
suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { runCatching { cancel() } }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            // Search cancels on every keystroke, so a response can land after the coroutine
            // is already gone. Without this the body — and its connection — is never closed.
            cont.resume(response) { _ -> runCatching { response.close() } }
        }
    })
}
