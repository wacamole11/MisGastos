package com.gastos.app.data

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object FirestoreRepository {
    private val db by lazy { FirebaseFirestore.getInstance() }
    private val auth by lazy { FirebaseAuth.getInstance() }

    private fun uid(): String = auth.currentUser?.uid ?: "general"

    private fun obtenerGastosRef(): CollectionReference {
        return db.collection("usuarios").document(uid()).collection("gastos")
    }

    private fun obtenerCuentasRef(): CollectionReference {
        return db.collection("usuarios").document(uid()).collection("cuentas")
    }

    private fun obtenerPagosFijosRef(): CollectionReference {
        return db.collection("usuarios").document(uid()).collection("pagos_fijos")
    }

    fun estaAutenticado(): Boolean = auth.currentUser != null

    // === GASTOS ===
    fun escucharGastos(): Flow<List<Gasto>> = callbackFlow {
        val ref = obtenerGastosRef()
        val listener = ref
            .orderBy("fecha", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                val lista = snapshot?.documents?.mapNotNull { doc ->
                    try {
                        Gasto(
                            id = doc.id,
                            monto = doc.getDouble("monto") ?: 0.0,
                            categoria = doc.getString("categoria") ?: "Otros",
                            nota = doc.getString("nota") ?: "",
                            fecha = doc.getLong("fecha") ?: System.currentTimeMillis(),
                            esIngreso = doc.getBoolean("esIngreso") ?: false,
                            sincronizado = true,
                            cuentaId = doc.getString("cuentaId") ?: "cuenta_bcp_yape",
                            esTransferencia = doc.getBoolean("esTransferencia") ?: false,
                            cuentaDestinoId = doc.getString("cuentaDestinoId"),
                            montoPorCobrar = doc.getDouble("montoPorCobrar") ?: 0.0,
                            deudor = doc.getString("deudor") ?: "",
                            cobrado = doc.getBoolean("cobrado") ?: false,
                            codigoOperacion = doc.getString("codigoOperacion") ?: ""
                        )
                    } catch (e: Exception) {
                        null
                    }
                } ?: emptyList()
                trySend(lista)
            }
        awaitClose { listener.remove() }
    }

    suspend fun obtenerGastosRemotos(): List<Gasto> {
        val snapshot = obtenerGastosRef()
            .orderBy("fecha", Query.Direction.DESCENDING)
            .get()
            .await()
        return snapshot.documents.mapNotNull { doc ->
            try {
                Gasto(
                    id = doc.id,
                    monto = doc.getDouble("monto") ?: 0.0,
                    categoria = doc.getString("categoria") ?: "Otros",
                    nota = doc.getString("nota") ?: "",
                    fecha = doc.getLong("fecha") ?: System.currentTimeMillis(),
                    esIngreso = doc.getBoolean("esIngreso") ?: false,
                    sincronizado = true,
                    cuentaId = doc.getString("cuentaId") ?: "cuenta_bcp_yape",
                    esTransferencia = doc.getBoolean("esTransferencia") ?: false,
                    cuentaDestinoId = doc.getString("cuentaDestinoId"),
                    montoPorCobrar = doc.getDouble("montoPorCobrar") ?: 0.0,
                    deudor = doc.getString("deudor") ?: "",
                    cobrado = doc.getBoolean("cobrado") ?: false,
                    codigoOperacion = doc.getString("codigoOperacion") ?: ""
                )
            } catch (e: Exception) {
                null
            }
        }
    }

    suspend fun guardar(gasto: Gasto) {
        val datos = hashMapOf(
            "monto" to gasto.monto,
            "categoria" to gasto.categoria,
            "nota" to gasto.nota,
            "fecha" to gasto.fecha,
            "esIngreso" to gasto.esIngreso,
            "cuentaId" to gasto.cuentaId,
            "esTransferencia" to gasto.esTransferencia,
            "cuentaDestinoId" to gasto.cuentaDestinoId,
            "montoPorCobrar" to gasto.montoPorCobrar,
            "deudor" to gasto.deudor,
            "cobrado" to gasto.cobrado,
            "codigoOperacion" to gasto.codigoOperacion,
            "usuarioId" to uid()
        )
        obtenerGastosRef().document(gasto.id).set(datos).await()
    }

    suspend fun eliminar(id: String) {
        obtenerGastosRef().document(id).delete().await()
    }

    // === CUENTAS ===
    fun escucharCuentas(): Flow<List<Cuenta>> = callbackFlow {
        val ref = obtenerCuentasRef()
        val listener = ref.addSnapshotListener { snapshot, error ->
            if (error != null) {
                close(error)
                return@addSnapshotListener
            }
            val lista = snapshot?.documents?.mapNotNull { doc ->
                try {
                    Cuenta(
                        id = doc.id,
                        nombre = doc.getString("nombre") ?: "Cuenta",
                        tipo = doc.getString("tipo") ?: Cuenta.TIPO_DISPONIBLE,
                        saldo = doc.getDouble("saldo") ?: 0.0,
                        colorHex = doc.getLong("colorHex") ?: 0xFF2196F3,
                        icono = doc.getString("icono") ?: "wallet"
                    )
                } catch (e: Exception) {
                    null
                }
            } ?: emptyList()
            trySend(lista)
        }
        awaitClose { listener.remove() }
    }

    suspend fun guardarPerfilUsuario(
        nombre: String,
        apellido: String,
        sexo: String,
        edad: Int?,
        profesion: String,
        estadoCivil: String
    ) {
        val datos = hashMapOf(
            "nombre" to nombre,
            "apellido" to apellido,
            "sexo" to sexo,
            "edad" to (edad ?: 0),
            "profesion" to profesion,
            "estadoCivil" to estadoCivil,
            "actualizadoEl" to System.currentTimeMillis()
        )
        db.collection("usuarios").document(uid()).set(datos, com.google.firebase.firestore.SetOptions.merge()).await()
    }

    suspend fun obtenerPerfilUsuario(): Map<String, Any>? {
        return try {
            val doc = db.collection("usuarios").document(uid()).get().await()
            if (doc.exists()) doc.data else null
        } catch (_: Exception) {
            null
        }
    }

    suspend fun guardarCuenta(cuenta: Cuenta) {
        val datos = hashMapOf(
            "nombre" to cuenta.nombre,
            "tipo" to cuenta.tipo,
            "saldo" to cuenta.saldo,
            "colorHex" to cuenta.colorHex,
            "icono" to cuenta.icono,
            "usuarioId" to uid()
        )
        obtenerCuentasRef().document(cuenta.id).set(datos).await()
    }

    suspend fun eliminarCuenta(id: String) {
        obtenerCuentasRef().document(id).delete().await()
    }

    suspend fun tieneCuentasConfiguradas(): Boolean {
        return try {
            val snapshot = obtenerCuentasRef().get().await()
            !snapshot.isEmpty
        } catch (_: Exception) {
            false
        }
    }

    // === PAGOS FIJOS ===
    fun escucharPagosFijos(): Flow<List<PagoFijo>> = callbackFlow {
        val ref = obtenerPagosFijosRef()
        val listener = ref.addSnapshotListener { snapshot, error ->
            if (error != null) {
                close(error)
                return@addSnapshotListener
            }
            val lista = snapshot?.documents?.mapNotNull { doc ->
                try {
                    PagoFijo(
                        id = doc.id,
                        nombre = doc.getString("nombre") ?: "",
                        monto = doc.getDouble("monto") ?: 0.0,
                        diaPago = (doc.getLong("diaPago") ?: 1L).toInt(),
                        categoria = doc.getString("categoria") ?: "Hogar",
                        cuentaId = doc.getString("cuentaId") ?: "cuenta_bcp_yape",
                        ultimoMesPagado = doc.getString("ultimoMesPagado") ?: ""
                    )
                } catch (e: Exception) {
                    null
                }
            } ?: emptyList()
            trySend(lista)
        }
        awaitClose { listener.remove() }
    }

    suspend fun guardarPagoFijo(pago: PagoFijo) {
        val datos = hashMapOf(
            "nombre" to pago.nombre,
            "monto" to pago.monto,
            "diaPago" to pago.diaPago,
            "categoria" to pago.categoria,
            "cuentaId" to pago.cuentaId,
            "ultimoMesPagado" to pago.ultimoMesPagado,
            "usuarioId" to uid()
        )
        obtenerPagosFijosRef().document(pago.id).set(datos).await()
    }

    suspend fun eliminarPagoFijo(id: String) {
        obtenerPagosFijosRef().document(id).delete().await()
    }

    // === CONTROL DE ACTUALIZACIONES (APP CONFIG & GITHUB RELEASES) ===
    suspend fun verificarActualizacion(
        versionCodeLocal: Long,
        versionNameLocal: String = "1.0"
    ): InfoActualizacion {
        // 1. Intentar Firebase Firestore si el usuario configuró app_config/version
        val infoFirebase: InfoActualizacion? = try {
            val doc = db.collection("app_config").document("version").get().await()
            if (doc != null && doc.exists()) {
                val codeRemoto = doc.getLong("versionCode") ?: 0L
                val nameRemoto = doc.getString("versionName") ?: "1.0"
                val url = doc.getString("apkUrl") ?: doc.getString("urlApk") ?: ""
                val novedades = doc.getString("novedades") ?: ""
                val obligatoria = doc.getBoolean("esObligatoria") ?: false

                InfoActualizacion(
                    hayActualizacion = codeRemoto > versionCodeLocal && url.isNotBlank(),
                    versionRemota = nameRemoto,
                    versionCodeRemoto = codeRemoto,
                    apkUrl = url,
                    novedades = novedades,
                    esObligatoria = obligatoria,
                    documentoExiste = true,
                    origen = "Firebase"
                )
            } else null
        } catch (_: Exception) {
            null
        }

        // Si Firebase reporta que hay actualización disponible, usarla directamente
        if (infoFirebase != null && infoFirebase.hayActualizacion) {
            return infoFirebase
        }

        // 2. Si Firebase no tiene actualización, consultar Releases de GitHub (wacamole11/MisGastos)
        val infoGitHub = verificarGitHubReleases(versionCodeLocal, versionNameLocal)
        if (infoGitHub != null && infoGitHub.hayActualizacion) {
            return infoGitHub
        }

        // 3. Si ninguno tiene actualización, devolver la info disponible (Firebase primero, luego GitHub)
        return infoFirebase ?: infoGitHub ?: InfoActualizacion(
            hayActualizacion = false,
            documentoExiste = false
        )
    }

    private suspend fun verificarGitHubReleases(versionCodeLocal: Long, versionNameLocal: String): InfoActualizacion? =
        withContext(Dispatchers.IO) {
            try {
                val url = URL("https://api.github.com/repos/wacamole11/MisGastos/releases/latest")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 8000
                    setRequestProperty("Accept", "application/vnd.github.v3+json")
                    setRequestProperty("User-Agent", "MisGastos-Android-App")
                }
                if (conn.responseCode == 200) {
                    val bodyStr = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(bodyStr)
                    val rawTag = json.optString("tag_name", "").trim()
                    val cleanVersion = rawTag.removePrefix("v").removePrefix("V").trim()
                    val releaseNotes = json.optString("body", "").trim()
                    val assets = json.optJSONArray("assets")
                    var downloadUrl = ""

                    if (assets != null) {
                        for (i in 0 until assets.length()) {
                            val asset = assets.getJSONObject(i)
                            val assetName = asset.optString("name", "")
                            if (assetName.endsWith(".apk", ignoreCase = true)) {
                                downloadUrl = asset.optString("browser_download_url", "")
                                break
                            }
                        }
                    }

                    val esMayor = esVersionMayor(cleanVersion, versionNameLocal)
                    InfoActualizacion(
                        hayActualizacion = esMayor && downloadUrl.isNotBlank(),
                        versionRemota = cleanVersion.ifBlank { rawTag },
                        versionCodeRemoto = 0L,
                        apkUrl = downloadUrl,
                        novedades = releaseNotes,
                        esObligatoria = false,
                        documentoExiste = true,
                        origen = "GitHub"
                    )
                } else {
                    null
                }
            } catch (_: Exception) {
                null
            }
        }

    private fun esVersionMayor(remota: String, local: String): Boolean {
        try {
            val partesRemota = remota.replace("[^0-9.]".toRegex(), "").split(".").filter { it.isNotBlank() }.map { it.toIntOrNull() ?: 0 }
            val partesLocal = local.replace("[^0-9.]".toRegex(), "").split(".").filter { it.isNotBlank() }.map { it.toIntOrNull() ?: 0 }
            val maxLen = maxOf(partesRemota.size, partesLocal.size)
            for (i in 0 until maxLen) {
                val r = partesRemota.getOrElse(i) { 0 }
                val l = partesLocal.getOrElse(i) { 0 }
                if (r > l) return true
                if (r < l) return false
            }
        } catch (_: Exception) {}
        return false
    }
}

data class InfoActualizacion(
    val hayActualizacion: Boolean = false,
    val versionRemota: String = "",
    val versionCodeRemoto: Long = 0L,
    val apkUrl: String = "",
    val novedades: String = "",
    val esObligatoria: Boolean = false,
    val documentoExiste: Boolean = true,
    val error: String? = null,
    val origen: String = "Firebase"
)
