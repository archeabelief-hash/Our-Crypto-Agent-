package com.cryptoai.overlay

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import okhttp3.OkHttpClient
import okhttp3.Request
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openssl.PEMKeyPair
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.json.JSONObject
import java.io.StringReader
import java.security.Security
import java.security.interfaces.ECPrivateKey
import java.util.Date
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.max

class CoinbaseAccountClient(
    private val keyName: String,
    private val privateKeyPem: String
) {
    data class Fill(
        val side: String,
        val size: Double,
        val price: Double,
        val commission: Double,
        val time: String
    )

    data class Position(
        val token: String,
        val balance: Double,
        val avgEntry: Double,
        val costBasis: Double,
        val feesPaid: Double,
        val takerFeeRate: Double,
        val recent: List<Fill>,
        val status: String
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val privateKey: ECPrivateKey by lazy { parseEcPrivateKey(privateKeyPem) }

    fun loadPosition(product: String): Position {
        val base = product.substringBefore("-")
        val balance = fetchBalance(base)
        val fills = fetchFills(product)
        val feeRate = fetchTakerFeeRate()

        var qty = 0.0
        var cost = 0.0
        var fees = 0.0

        for (fill in fills.sortedBy { it.time }) {
            fees += fill.commission
            if (fill.side.equals("BUY", true)) {
                qty += fill.size
                cost += fill.price * fill.size + fill.commission
            } else if (fill.side.equals("SELL", true) && qty > 0.0) {
                val remove = minOf(fill.size, qty)
                val avg = if (qty > 0.0) cost / qty else 0.0
                qty -= remove
                cost = max(0.0, cost - avg * remove)
            }
        }

        val derivedAvg = if (qty > 0.0) cost / qty else 0.0
        val adjustedCost = if (balance > 0.0 && derivedAvg > 0.0) balance * derivedAvg else cost

        return Position(
            token = base,
            balance = balance,
            avgEntry = derivedAvg,
            costBasis = adjustedCost,
            feesPaid = fees,
            takerFeeRate = feeRate,
            recent = fills.sortedByDescending { it.time }.take(6),
            status = if (fills.isEmpty()) "Connected; no fills found for $product" else "CONNECTED"
        )
    }

    private fun fetchBalance(currency: String): Double {
        val path = "/api/v3/brokerage/accounts"
        val json = getJson(path, "?limit=250")
        val arr = json.optJSONArray("accounts") ?: return 0.0
        var total = 0.0
        for (i in 0 until arr.length()) {
            val a = arr.getJSONObject(i)
            if (!a.optString("currency").equals(currency, true)) continue
            total += a.optJSONObject("available_balance")?.optString("value")?.toDoubleOrNull() ?: 0.0
            total += a.optJSONObject("hold")?.optString("value")?.toDoubleOrNull() ?: 0.0
        }
        return total
    }

    private fun fetchFills(product: String): List<Fill> {
        val path = "/api/v3/brokerage/orders/historical/fills"
        val json = getJson(path, "?product_id=$product&limit=100")
        val arr = json.optJSONArray("fills") ?: return emptyList()
        val out = ArrayList<Fill>()
        for (i in 0 until arr.length()) {
            val f = arr.getJSONObject(i)
            out += Fill(
                side = f.optString("side"),
                size = f.optString("size").toDoubleOrNull() ?: f.optDouble("size", 0.0),
                price = f.optString("price").toDoubleOrNull() ?: f.optDouble("price", 0.0),
                commission = f.optString("commission").toDoubleOrNull() ?: 0.0,
                time = f.optString("trade_time")
            )
        }
        return out
    }

    private fun fetchTakerFeeRate(): Double {
        return try {
            val path = "/api/v3/brokerage/transaction_summary"
            val json = getJson(path, "?product_type=SPOT")
            json.optJSONObject("fee_tier")?.optString("taker_fee_rate")?.toDoubleOrNull() ?: 0.0
        } catch (_: Exception) {
            0.0
        }
    }

    private fun getJson(path: String, query: String = ""): JSONObject {
        val token = jwt("GET", path)
        val req = Request.Builder()
            .url("https://api.coinbase.com$path$query")
            .header("Authorization", "Bearer $token")
            .header("Cache-Control", "no-cache")
            .build()
        client.newCall(req).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IllegalStateException("Coinbase ${response.code}: ${body.take(180)}")
            return JSONObject(body)
        }
    }

    private fun jwt(method: String, path: String): String {
        val now = System.currentTimeMillis()
        val uri = "$method api.coinbase.com$path"
        val header = JWSHeader.Builder(JWSAlgorithm.ES256)
            .type(JOSEObjectType.JWT)
            .keyID(keyName)
            .customParam("nonce", UUID.randomUUID().toString().replace("-", ""))
            .build()
        val claims = JWTClaimsSet.Builder()
            .issuer("cdp")
            .subject(keyName)
            .audience("cdp_service")
            .notBeforeTime(Date(now))
            .expirationTime(Date(now + 120_000))
            .claim("uri", uri)
            .build()
        return SignedJWT(header, claims).apply { sign(ECDSASigner(privateKey)) }.serialize()
    }

    private fun parseEcPrivateKey(pemRaw: String): ECPrivateKey {
        if (Security.getProvider("BC") == null) Security.addProvider(BouncyCastleProvider())
        val pem = pemRaw.replace("\\n", "\n").trim()
        PEMParser(StringReader(pem)).use { parser ->
            val obj = parser.readObject() ?: throw IllegalArgumentException("Private key is empty")
            val converter = JcaPEMKeyConverter().setProvider("BC")
            val key = when (obj) {
                is PEMKeyPair -> converter.getKeyPair(obj).private
                is PrivateKeyInfo -> converter.getPrivateKey(obj)
                else -> throw IllegalArgumentException("Unsupported EC private-key format")
            }
            return key as? ECPrivateKey ?: throw IllegalArgumentException("Coinbase key must be an EC private key")
        }
    }
}
