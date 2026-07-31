package fi.nikosavola.clockifywear.data.api

import java.util.concurrent.TimeUnit
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

const val CLOCKIFY_BASE_URL = "https://api.clockify.me/api/v1/"

private const val API_KEY_HEADER = "X-Api-Key"
private const val CONNECT_TIMEOUT_SECONDS = 10L
private const val READ_WRITE_TIMEOUT_SECONDS = 30L
private const val HTTP_TOO_MANY_REQUESTS = 429
private const val DEFAULT_RETRY_AFTER_SECONDS = 1L

// Bounds a hostile or broken Retry-After header so one flaky response can't stall the watch
// radio indefinitely.
private const val MAX_RETRY_AFTER_SECONDS = 10L

private val jsonMediaType = "application/json".toMediaType()

/**
 * @param apiKey read on every request, not captured once, because the key lives in user-editable
 *   settings and can change (or be cleared) while the client is alive.
 * @param baseUrl overridable so tests can point the client at a MockWebServer instance.
 */
fun createClockifyApi(apiKey: () -> String?, baseUrl: String = CLOCKIFY_BASE_URL): ClockifyApi {
  val okHttpClient =
    OkHttpClient.Builder()
      .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
      .readTimeout(READ_WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
      .writeTimeout(READ_WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
      .addInterceptor(ApiKeyInterceptor(apiKey))
      .addInterceptor(RetryOn429Interceptor())
      .build()

  val retrofit =
    Retrofit.Builder()
      .baseUrl(baseUrl)
      .client(okHttpClient)
      .addConverterFactory(clockifyJson.asConverterFactory(jsonMediaType))
      .build()

  return retrofit.create(ClockifyApi::class.java)
}

private class ApiKeyInterceptor(private val apiKey: () -> String?) : Interceptor {
  override fun intercept(chain: Interceptor.Chain): Response {
    val key = apiKey() ?: return chain.proceed(chain.request())
    val request = chain.request().newBuilder().addHeader(API_KEY_HEADER, key).build()
    return chain.proceed(request)
  }
}

// A second 429 is treated as a real error rather than retried again: one retry absorbs a brief
// burst, a repeat means the limit genuinely isn't clearing.
private class RetryOn429Interceptor : Interceptor {
  override fun intercept(chain: Interceptor.Chain): Response {
    val request = chain.request()
    val firstResponse = chain.proceed(request)
    if (firstResponse.code != HTTP_TOO_MANY_REQUESTS) return firstResponse

    val delaySeconds = retryDelaySeconds(firstResponse.header("Retry-After"))
    firstResponse.close()
    Thread.sleep(TimeUnit.SECONDS.toMillis(delaySeconds))
    return chain.proceed(request)
  }

  private fun retryDelaySeconds(retryAfterHeader: String?): Long {
    val requested = retryAfterHeader?.toLongOrNull() ?: DEFAULT_RETRY_AFTER_SECONDS
    return requested.coerceIn(0L, MAX_RETRY_AFTER_SECONDS)
  }
}
