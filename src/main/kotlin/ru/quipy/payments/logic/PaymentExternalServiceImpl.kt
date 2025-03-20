package ru.quipy.payments.logic

import RequestDurationTracker
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import kotlinx.coroutines.Dispatchers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.slf4j.LoggerFactory
import ru.quipy.common.utils.RateAndConcurrencyLimiter
import ru.quipy.common.utils.RetryableError
import ru.quipy.common.utils.Retryer
import ru.quipy.core.EventSourcingService
import ru.quipy.payments.api.PaymentAggregate
import java.lang.Long.max
import java.net.SocketTimeoutException
import java.time.Duration
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.math.min

// Advice: always treat time as a Duration
class PaymentExternalSystemAdapterImpl(
    private val properties: PaymentAccountProperties,
    private val paymentESService: EventSourcingService<UUID, PaymentAggregate, PaymentAggregateState>
) : PaymentExternalSystemAdapter {

    companion object {
        val logger = LoggerFactory.getLogger(PaymentExternalSystemAdapter::class.java)

        val emptyBody = RequestBody.create(null, ByteArray(0))
        val mapper = ObjectMapper().registerKotlinModule()
    }

    private val serviceName = properties.serviceName
    private val accountName = properties.accountName
    private val requestAverageProcessingTime = properties.averageProcessingTime
    private val rateLimitPerSec = properties.rateLimitPerSec
    private val parallelRequests = properties.parallelRequests
    private val maxRetries = 10;
    private val executionTimeoutPercentile = 80.0
    private val executionTimeoutFactor = 1.3
    private val minPaymentTimeout = 300L

    private val limiter = RateAndConcurrencyLimiter(
        rateLimitPerSec, 1000, parallelRequests.toLong())
    private val retryer = Retryer(maxRetries)
    private val requestDurationTracker = RequestDurationTracker(10, 100)

    private val client = OkHttpClient.Builder().build()

    override fun performPaymentAsync(paymentId: UUID, amount: Int, paymentStartedAt: Long, deadline: Long) {
        val timeoutMs = getPaymentTimeout()
        println("setting timeout ${timeoutMs}ms")
        var optimizedDeadline = min(deadline, System.currentTimeMillis() + timeoutMs)
        retryer.executeWithDeadlineAsync(
            Dispatchers.Default,
            optimizedDeadline,
            paymentId)
        {
            limiter.execute() {
                requestDurationTracker.track() {
                    performPayment(paymentId, amount, paymentStartedAt, timeoutMs)
                }
            }
        }
    }

    private fun getPaymentTimeout(): Long {
        val timeout = requestDurationTracker.getPercentileOrDefault(
            executionTimeoutPercentile,
            requestAverageProcessingTime.toMillis().toDouble())
        return max((timeout * executionTimeoutFactor).toLong(), minPaymentTimeout)
    }

    private fun performPayment(paymentId: UUID, amount: Int, paymentStartedAt: Long, timeoutMs: Long) {
        logger.warn("[$accountName] Submitting payment request for payment $paymentId")

        val transactionId = UUID.randomUUID()
//        logger.info("[$accountName] Submit for $paymentId , txId: $transactionId")

        // Вне зависимости от исхода оплаты важно отметить что она была отправлена.
        // Это требуется сделать ВО ВСЕХ СЛУЧАЯХ, поскольку эта информация используется сервисом тестирования.
        paymentESService.update(paymentId) {
            it.logSubmission(success = true, transactionId, now(), Duration.ofMillis(now() - paymentStartedAt))
        }

        val request = Request.Builder().run {
            url("http://localhost:1234/external/process?serviceName=${serviceName}&accountName=${accountName}&transactionId=$transactionId&paymentId=$paymentId&amount=$amount")
            post(emptyBody)
        }.build()

        try {
            val newClient = client.newBuilder().callTimeout(timeoutMs, TimeUnit.MILLISECONDS).build()
            newClient.newCall(request).execute().use { response ->
                val body = try {
                    mapper.readValue(response.body?.string(), ExternalSysResponse::class.java)
                } catch (e: Exception) {
                    logger.error("[$accountName] [ERROR] Payment processed for txId: $transactionId, payment: $paymentId, result code: ${response.code}, reason: ${response.body?.string()}")
                    ExternalSysResponse(transactionId.toString(), paymentId.toString(),false, e.message)
                }

                if (!body.result) {
                    logger.warn("[$accountName] Payment processed for txId: $transactionId, payment: $paymentId, succeeded: ${body.result}, message: ${body.message}, response code: ${response.code}")
                }
//                logger.warn("[$accountName] Payment processed for txId: $transactionId, payment: $paymentId, succeeded: ${body.result}, message: ${body.message}")

                // Здесь мы обновляем состояние оплаты в зависимости от результата в базе данных оплат.
                // Это требуется сделать ВО ВСЕХ ИСХОДАХ (успешная оплата / неуспешная / ошибочная ситуация)
                paymentESService.update(paymentId) {
                    it.logProcessing(body.result, now(), transactionId, reason = body.message)
                }

                if (!body.result && !(response.code >= 400 && response.code <= 499)) {
                    println("throwing retryable error")
                    throw RetryableError("can be retried")
                }
            }
        } catch (e: Exception) {
            when (e) {
                is RetryableError -> { throw e }
                is SocketTimeoutException -> {
                    logger.error("[$accountName] Payment timeout for txId: $transactionId, payment: $paymentId", e)
                    paymentESService.update(paymentId) {
                        it.logProcessing(false, now(), transactionId, reason = "Request timeout.")
                    }
                }
                else -> {
                    logger.error("[$accountName] Payment failed for txId: $transactionId, payment: $paymentId", e)

                    paymentESService.update(paymentId) {
                        it.logProcessing(false, now(), transactionId, reason = e.message)
                    }
                }
            }
        }
    }

    override fun price() = properties.price

    override fun isEnabled() = properties.enabled

    override fun name() = properties.accountName

}

public fun now() = System.currentTimeMillis()