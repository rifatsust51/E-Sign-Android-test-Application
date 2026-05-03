package com.rvl.myapplication

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

object NetworkUtils {

    private const val TAG          = "MFA_DEBUG"
    private const val PFX_PASSWORD = "password"

    fun buildTrustedOkHttpClient(
        context: Context,
        connectTimeoutSecs: Long = 30,
        readTimeoutSecs: Long    = 30,
        writeTimeoutSecs: Long   = 30
    ): OkHttpClient {
        val combinedTrustStore = buildCombinedTrustStore(context)
        val tmf = TrustManagerFactory.getInstance(
            TrustManagerFactory.getDefaultAlgorithm()
        ).apply { init(combinedTrustStore) }
        val x509Tm = tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(x509Tm), SecureRandom())
        }
        return OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, x509Tm)
            .connectTimeout(connectTimeoutSecs, TimeUnit.SECONDS)
            .readTimeout(readTimeoutSecs, TimeUnit.SECONDS)
            .writeTimeout(writeTimeoutSecs, TimeUnit.SECONDS)
            .build()
    }

    fun buildMtlsOkHttpClient(
        context: Context,
        connectTimeoutSecs: Long = 30,
        readTimeoutSecs: Long    = 120,
        writeTimeoutSecs: Long   = 60
    ): OkHttpClient {
        val pfxResId = context.resources.getIdentifier(
            "tspgw_client", "raw", context.packageName
        )
        require(pfxResId != 0) { "tspgw_client.pfx not found in res/raw/" }

        val clientKeyStore = context.resources.openRawResource(pfxResId).use { stream ->
            KeyStore.getInstance("PKCS12").apply { load(stream, PFX_PASSWORD.toCharArray()) }
        }
        val kmf = KeyManagerFactory.getInstance(
            KeyManagerFactory.getDefaultAlgorithm()
        ).apply { init(clientKeyStore, PFX_PASSWORD.toCharArray()) }

        val combinedTrustStore = buildCombinedTrustStore(context)
        val tmf = TrustManagerFactory.getInstance(
            TrustManagerFactory.getDefaultAlgorithm()
        ).apply { init(combinedTrustStore) }
        val x509Tm = tmf.trustManagers.filterIsInstance<X509TrustManager>().first()

        val sslContext = SSLContext.getInstance("TLS").apply {
            init(kmf.keyManagers, arrayOf(x509Tm), SecureRandom())
        }
        return OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, x509Tm)
            .connectTimeout(connectTimeoutSecs, TimeUnit.SECONDS)
            .readTimeout(readTimeoutSecs, TimeUnit.SECONDS)
            .writeTimeout(writeTimeoutSecs, TimeUnit.SECONDS)
            .build()
    }

    fun buildCombinedTrustStore(context: Context): KeyStore {
        val systemTmf = TrustManagerFactory.getInstance(
            TrustManagerFactory.getDefaultAlgorithm()
        ).apply { init(null as KeyStore?) }
        val systemTm = systemTmf.trustManagers.filterIsInstance<X509TrustManager>().first()

        val combinedStore = KeyStore.getInstance(KeyStore.getDefaultType())
            .apply { load(null, null) }
        systemTm.acceptedIssuers.forEachIndexed { i, cert ->
            combinedStore.setCertificateEntry("system_ca_$i", cert)
        }
        val caResId = context.resources.getIdentifier("server_ca", "raw", context.packageName)
        if (caResId != 0) {
            context.resources.openRawResource(caResId).use { stream ->
                CertificateFactory.getInstance("X.509").generateCertificates(stream)
                    .forEachIndexed { i, cert ->
                        combinedStore.setCertificateEntry("private_ca_$i", cert)
                        Log.d(TAG, "Added private CA[$i]: " +
                                (cert as X509Certificate).subjectX500Principal.name)
                    }
            }
        }
        return combinedStore
    }
}