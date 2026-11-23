package com.gastos.app.data

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

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

    // === CONTROL DE ACTUALIZACIONES (APP CONFIG) ===
    suspend fun verificarActualizacion(versionCodeLocal: Long): InfoActualizacion {
        return try {
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
                    esObligatoria = obligatoria
                )
            } else {
                InfoActualizacion(hayActualizacion = false)
            }
        } catch (_: Exception) {
            InfoActualizacion(hayActualizacion = false)
        }
    }
}

data class InfoActualizacion(
    val hayActualizacion: Boolean = false,
    val versionRemota: String = "",
    val versionCodeRemoto: Long = 0L,
    val apkUrl: String = "",
    val novedades: String = "",
    val esObligatoria: Boolean = false
)
