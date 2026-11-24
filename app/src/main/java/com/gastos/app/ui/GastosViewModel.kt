package com.gastos.app.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gastos.app.data.AppDatabase
import com.gastos.app.data.Cuenta
import com.gastos.app.data.FirestoreRepository
import com.gastos.app.data.Gasto
import com.gastos.app.data.PagoFijo
import com.gastos.app.data.InfoActualizacion
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.UserProfileChangeRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class MovimientoDetectado(
    val monto: Double,
    val categoria: String,
    val nota: String,
    val esIngreso: Boolean,
    val esTransferencia: Boolean = false,
    val cuentaNombre: String = "BCP / Yape",
    val cuentaDestinoNombre: String? = null,
    val fecha: Long = System.currentTimeMillis(),
    val montoPorCobrar: Double = 0.0,
    val deudor: String = "",
    val codigoOperacion: String = ""
)

data class SimuladorResultado(
    val veredicto: String, // "VERDE", "AMARILLO", "ROJO"
    val titulo: String,
    val explicacion: String,
    val impactoDiario: String
)

data class DiagnosticoAnomalias(
    val titulo: String,
    val resumen: String,
    val gastosHormigaMonto: Double,
    val recomendaciones: List<String>
)

data class BotonRapido(
    val etiqueta: String,
    val monto: Double,
    val categoria: String,
    val icono: String
)

data class MensajeChat(
    val texto: String,
    val esUsuario: Boolean,
    val fecha: Long = System.currentTimeMillis()
)

sealed class EstadoActualizacion {
    object Inactivo : EstadoActualizacion()
    object Comprobando : EstadoActualizacion()
    data class AlDia(val info: InfoActualizacion) : EstadoActualizacion()
    data class Disponible(val info: InfoActualizacion) : EstadoActualizacion()
    data class Descargando(val progreso: Float, val info: InfoActualizacion) : EstadoActualizacion()
    data class ListoParaInstalar(val archivo: File, val info: InfoActualizacion) : EstadoActualizacion()
    data class Error(val mensaje: String) : EstadoActualizacion()
}

data class IngresoProgramado(
    val id: String = java.util.UUID.randomUUID().toString(),
    val nombre: String,
    val montoEsperado: Double,
    val frecuenciaTipo: Int = 0, // 0: Mensual, 1: Quincenal, 2: Semanal
    val diaCobro: Int = 1, // Mensual: día mes (1-31). Quincenal: 1er día (ej. 15). Semanal: día semana (1=Lun..7=Dom).
    val diaCobro2: Int = 30, // Quincenal: 2do día (ej. 30 o fin de mes)
    val categoria: String = "Sueldo",
    val cuentaId: String = "cuenta_bcp_yape",
    val ultimoMesCobrado: String = "", // "yyyy-MM" para retrocompatibilidad
    val montoCobradoReal: Double = 0.0,
    val notaCobro: String = "",
    val cobrosJson: String = "{}" // JSON map: "yyyy-MM-dd" -> { "monto": 350.0, "nota": "...", "cuentaId": "..." }
)

data class DeudaPrestamo(
    val id: String = java.util.UUID.randomUUID().toString(),
    val persona: String, // Nombre de la persona o contacto (ej. "Carlos Gómez")
    val tipo: Int = TIPO_POR_COBRAR, // 0: Nos deben (por cobrar), 1: Debemos (por pagar)
    val montoBase: Double, // Monto base prestado o recibido
    val fechaRegistro: Long = System.currentTimeMillis(), // Fecha en que dimos o recibimos el dinero
    val tipoInteres: Int = INTERES_NINGUNO, // 0: Ninguno, 1: Porcentaje %, 2: Monto Fijo S/
    val valorInteres: Double = 0.0, // Ej: 10.0 para 10%, o 50.0 para S/ 50
    val fechaLimitePago: Long = 0L, // Posible fecha de pago / vencimiento (0 si no especificada)
    val cuentaId: String = "cuenta_bcp_yape", // Cuenta para debitar o abonar
    val estado: Int = ESTADO_PENDIENTE, // 0: Pendiente, 1: Completado (cobrado o pagado)
    val fechaCobradoPagado: Long = 0L, // Fecha en que se cobró o pagó
    val nota: String = "" // Nota opcional
) {
    companion object {
        const val TIPO_POR_COBRAR = 0 // Nos deben (presté dinero)
        const val TIPO_POR_PAGAR = 1  // Debemos (me prestaron dinero)

        const val INTERES_NINGUNO = 0
        const val INTERES_PORCENTAJE = 1
        const val INTERES_MONTO_FIJO = 2

        const val ESTADO_PENDIENTE = 0
        const val ESTADO_COMPLETADO = 1
    }

    val montoInteresCalculado: Double
        get() = when (tipoInteres) {
            INTERES_PORCENTAJE -> montoBase * (valorInteres / 100.0)
            INTERES_MONTO_FIJO -> valorInteres
            else -> 0.0
        }

    val montoTotalFinal: Double
        get() = montoBase + montoInteresCalculado
}

class GastosViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("config", Context.MODE_PRIVATE)
    private val dao = AppDatabase.obtener(application).gastoDao()
    private var firestoreJob: Job? = null

    // API Key de Gemini (se lee de forma segura desde local.properties / BuildConfig)
    private val geminiApiKey = com.gastos.app.BuildConfig.GEMINI_API_KEY

    val googleSignInOptions: GoogleSignInOptions by lazy {
        val webClientId = "945315821407-ko4q3ns1j0s6e4koj9r034orhuut33c4.apps.googleusercontent.com"
        GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(webClientId)
            .requestEmail()
            .requestProfile()
            .build()
    }

    // Estados de Usuario & Seguridad
    private val _conectado = MutableStateFlow(prefs.getBoolean("conectado", false))
    val conectado = _conectado.asStateFlow()

    private val _onboardingCompleted = MutableStateFlow(prefs.getBoolean("onboarding_completed", false))
    val onboardingCompleted = _onboardingCompleted.asStateFlow()

    private val _diaCorteMes = MutableStateFlow(prefs.getInt("dia_corte_mes", 1))
    val diaCorteMes = _diaCorteMes.asStateFlow()

    private val _usuarioNombre = MutableStateFlow(prefs.getString("usuarioNombre", null))
    val usuarioNombre = _usuarioNombre.asStateFlow()

    private val _usuarioEmail = MutableStateFlow(prefs.getString("usuarioEmail", null))
    val usuarioEmail = _usuarioEmail.asStateFlow()

    private val _usuarioFoto = MutableStateFlow(prefs.getString("usuarioFoto", null))
    val usuarioFoto = _usuarioFoto.asStateFlow()

    private val _usuarioPerfil = MutableStateFlow<Map<String, Any>?>(null)
    val usuarioPerfil = _usuarioPerfil.asStateFlow()

    private val _biometriaHabilitada = MutableStateFlow(prefs.getBoolean("biometria_habilitada", false))
    val biometriaHabilitada = _biometriaHabilitada.asStateFlow()

    private val _bloqueadoPorBiometria = MutableStateFlow(prefs.getBoolean("biometria_habilitada", false))
    val bloqueadoPorBiometria = _bloqueadoPorBiometria.asStateFlow()

    // Estados de UI y Mensajes
    private val _mensaje = MutableStateFlow<String?>(null)
    val mensaje = _mensaje.asStateFlow()

    private val _estadoActualizacion = MutableStateFlow<EstadoActualizacion>(EstadoActualizacion.Inactivo)
    val estadoActualizacion: StateFlow<EstadoActualizacion> = _estadoActualizacion.asStateFlow()

    private val _ocupado = MutableStateFlow(false)
    val ocupado = _ocupado.asStateFlow()

    private val _presupuesto = MutableStateFlow(prefs.getFloat("presupuesto", 0f).toDouble())
    val presupuesto = _presupuesto.asStateFlow()

    private val _metaAhorroMes = MutableStateFlow(prefs.getFloat("meta_ahorro_mes", 0f).toDouble())
    val metaAhorroMes = _metaAhorroMes.asStateFlow()

    private val _reglaAhorroAutoPct = MutableStateFlow(prefs.getInt("regla_ahorro_pct", 10))
    val reglaAhorroAutoPct = _reglaAhorroAutoPct.asStateFlow()

    // Topes por Categoría
    private val _topesCategorias = MutableStateFlow<Map<String, Double>>(
        mapOf(
            "Comida" to prefs.getFloat("tope_Comida", 600f).toDouble(),
            "Ocio" to prefs.getFloat("tope_Ocio", 250f).toDouble(),
            "Transporte" to prefs.getFloat("tope_Transporte", 150f).toDouble(),
            "Ropa" to prefs.getFloat("tope_Ropa", 200f).toDouble()
        )
    )
    val topesCategorias = _topesCategorias.asStateFlow()

    // Botones rápidos de "1 Toque"
    val botonesRapidos = listOf(
        BotonRapido("Pasaje", 2.50, "Transporte", "directions_bus"),
        BotonRapido("Menú", 14.00, "Comida", "restaurant"),
        BotonRapido("Café", 5.00, "Comida", "local_cafe"),
        BotonRapido("Pan", 4.00, "Comida", "bakery_dining"),
        BotonRapido("Taxi", 10.00, "Transporte", "local_taxi")
    )

    // Estados de Flujos de IA & Previsualización
    private val _movimientosDetectados = MutableStateFlow<List<MovimientoDetectado>?>(null)
    val movimientosDetectados = _movimientosDetectados.asStateFlow()

    private val _simuladorResultado = MutableStateFlow<SimuladorResultado?>(null)
    val simuladorResultado = _simuladorResultado.asStateFlow()

    private val _sugerenciaAhorroIngreso = MutableStateFlow<Pair<Double, Double>?>(null)
    val sugerenciaAhorroIngreso = _sugerenciaAhorroIngreso.asStateFlow()

    private val _copilotoMensajes = MutableStateFlow<List<MensajeChat>>(listOf(
        MensajeChat("¡Hola! Soy tu Copiloto Financiero IA. Pregúntame si te alcanza para una compra, en qué se te está yendo el dinero o cómo optimizar tu ahorro.", false)
    ))
    val copilotoMensajes = _copilotoMensajes.asStateFlow()

    // === FLUJOS DE DATOS ROOM ===
    val gastos = dao.todos().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val cuentas = dao.todasCuentas().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val pagosFijos = dao.todosPagosFijos().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val porCobrar = dao.porCobrar().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _diagnosticoAnomalias = MutableStateFlow<DiagnosticoAnomalias?>(null)

    val diagnosticoAnomalias: StateFlow<DiagnosticoAnomalias?> = combine(gastos, topesCategorias, _diagnosticoAnomalias) { lista, topes, customDiag ->
        if (customDiag != null) return@combine customDiag
        val soloGastos = lista.filter { !it.esTransferencia && !it.esIngreso }
        if (soloGastos.isEmpty()) return@combine null

        val hormiga = soloGastos.filter { it.montoRealUsuario in 0.1..25.0 }
        val montoHormiga = hormiga.sumOf { it.montoRealUsuario }

        val recomendaciones = mutableListOf<String>()
        if (montoHormiga > 50.0) {
            recomendaciones.add("Detectamos S/ ${"%.2f".format(montoHormiga)} en pequeños gastos (hormiga). Reducirlos un 30% ahorraría S/ ${"%.2f".format(montoHormiga * 0.3)} al mes.")
        }

        val porCat = soloGastos.groupBy { it.categoria }
        porCat.forEach { (cat, catGastos) ->
            val totalCat = catGastos.sumOf { it.montoRealUsuario }
            val tope = topes[cat] ?: 500.0
            if (totalCat > tope) {
                recomendaciones.add("Superaste el presupuesto tope en $cat: gastaste S/ ${"%.2f".format(totalCat)} (tope: S/ ${"%.2f".format(tope)}).")
            }
        }

        if (recomendaciones.isEmpty()) {
            DiagnosticoAnomalias(
                titulo = "Gastos Bajo Control",
                resumen = "Tus gastos están dentro de los rangos planificados.",
                gastosHormigaMonto = montoHormiga,
                recomendaciones = listOf("¡Excelente! Tus pagos fijos y metas de ahorro están al día.")
            )
        } else {
            DiagnosticoAnomalias(
                titulo = "Diagnóstico Financiero",
                resumen = "Identificamos ${recomendaciones.size} oportunidad(es) de mejora en tus hábitos de gasto.",
                gastosHormigaMonto = montoHormiga,
                recomendaciones = recomendaciones
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // Saldo Total por Tipo de Cuenta
    val saldoDisponibleTotal = cuentas.map { list ->
        list.filter { it.tipo == Cuenta.TIPO_DISPONIBLE }.sumOf { it.saldo }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)

    val saldoAhorroTotal = cuentas.map { list ->
        list.filter { it.tipo == Cuenta.TIPO_AHORRO }.sumOf { it.saldo }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)

    val saldoInversionTotal = cuentas.map { list ->
        list.filter { it.tipo == Cuenta.TIPO_INVERSION }.sumOf { it.saldo }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)

    // Pagos fijos pendientes del mes actual
    val pagosFijosPendientesMes = pagosFijos.map { lista ->
        val mesActual = SimpleDateFormat("yyyy-MM", Locale.US).format(Date())
        lista.filter { it.ultimoMesPagado != mesActual }.sumOf { it.monto }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)

    // === CÁLCULO DE "DISPONIBLE HOY" / SALDO LIBRE POR DÍA ===
    val saldoLibrePorDia = combine(
        saldoDisponibleTotal,
        pagosFijosPendientesMes,
        metaAhorroMes
    ) { disponible, fijosPendientes, ahorro ->
        val cal = Calendar.getInstance()
        val diasEnMes = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        val diaActual = cal.get(Calendar.DAY_OF_MONTH)
        val diasRestantes = (diasEnMes - diaActual + 1).coerceAtLeast(1)

        val libreTotal = (disponible - fijosPendientes - ahorro).coerceAtLeast(0.0)
        libreTotal / diasRestantes
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)

    init {
        viewModelScope.launch {
            if (dao.todasCuentasLista().isEmpty()) {
                dao.insertarCuentas(Cuenta.cuentasPorDefecto())
            }
        }

        viewModelScope.launch {
            combine(gastos, cuentas, pagosFijos) { _, _, _ -> }.collect {
                try {
                    com.gastos.app.widget.AccesoRapidoWidgetProvider.actualizarWidgets(application)
                } catch (_: Exception) {}
            }
        }

        val currentUser = FirebaseAuth.getInstance().currentUser
        val lastAccount = GoogleSignIn.getLastSignedInAccount(application)
        if (currentUser != null || lastAccount != null) {
            val nombre = currentUser?.displayName ?: lastAccount?.displayName ?: lastAccount?.givenName ?: "Usuario"
            val email = currentUser?.email ?: lastAccount?.email
            val foto = currentUser?.photoUrl?.toString() ?: lastAccount?.photoUrl?.toString()
            _usuarioNombre.value = nombre
            _usuarioEmail.value = email
            _usuarioFoto.value = foto
            _conectado.value = true

            if (currentUser == null) {
                viewModelScope.launch {
                    try {
                        FirebaseAuth.getInstance().signInAnonymously().await()
                        iniciarSincronizacionFirestore()
                    } catch (_: Exception) {}
                }
            } else {
                iniciarSincronizacionFirestore()
            }
            cargarPerfilUsuario()
        }
    }

    fun cargarPerfilUsuario() {
        viewModelScope.launch {
            try {
                val perfil = FirestoreRepository.obtenerPerfilUsuario()
                if (perfil != null) {
                    _usuarioPerfil.value = perfil
                }
            } catch (_: Exception) {}
        }
    }

    // Tema: "sistema", "claro", "oscuro"
    private val _modoTema = MutableStateFlow(prefs.getString("modo_tema", "sistema") ?: "sistema")
    val modoTema = _modoTema.asStateFlow()

    fun cambiarModoTema(modo: String) {
        _modoTema.value = modo
        prefs.edit().putString("modo_tema", modo).apply()
    }

    // === BIOMETRÍA ===
    fun desbloquearBiometria() {
        _bloqueadoPorBiometria.value = false
    }

    fun alternarBiometria(activar: Boolean) {
        _biometriaHabilitada.value = activar
        prefs.edit().putBoolean("biometria_habilitada", activar).apply()
    }

    // === AUTENTICACIÓN GOOGLE / CORREO / TELÉFONO / FIREBASE ===
    fun iniciarSesionCorreo(correo: String, contrasena: String, onExito: () -> Unit, onError: (String) -> Unit) {
        viewModelScope.launch {
            try {
                val authResult = FirebaseAuth.getInstance().signInWithEmailAndPassword(correo.trim(), contrasena).await()
                val user = authResult.user
                val nombre = user?.displayName ?: correo.substringBefore("@")
                val email = user?.email ?: correo.trim()

                _usuarioNombre.value = nombre
                _usuarioEmail.value = email
                _conectado.value = true

                prefs.edit()
                    .putBoolean("conectado", true)
                    .putString("usuarioNombre", nombre)
                    .putString("usuarioEmail", email)
                    .apply()

                // Limpiar base local antes de cargar los datos de este usuario
                dao.limpiarTodos()
                dao.limpiarCuentas()
                dao.limpiarPagosFijos()

                // Si el usuario ya tiene cuentas en Firestore, saltar el onboarding directamente al inicio
                val yaTieneCuentas = FirestoreRepository.tieneCuentasConfiguradas()
                if (yaTieneCuentas) {
                    _onboardingCompleted.value = true
                    prefs.edit().putBoolean("onboarding_completed", true).apply()
                } else {
                    _onboardingCompleted.value = false
                    prefs.edit().putBoolean("onboarding_completed", false).apply()
                }

                iniciarSincronizacionFirestore()
                onExito()
            } catch (e: Exception) {
                onError(e.localizedMessage ?: "Error al iniciar sesión")
            }
        }
    }

    fun registrarseCorreo(
        correo: String,
        contrasena: String,
        nombre: String,
        apellido: String = "",
        sexo: String = "",
        edad: Int? = null,
        profesion: String = "",
        estadoCivil: String = "",
        onExito: () -> Unit,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch {
            try {
                val authResult = FirebaseAuth.getInstance().createUserWithEmailAndPassword(correo.trim(), contrasena).await()
                val user = authResult.user
                val nombreCompleto = listOf(nombre.trim(), apellido.trim()).filter { it.isNotBlank() }.joinToString(" ")

                if (nombreCompleto.isNotBlank() && user != null) {
                    val profileUpdates = com.google.firebase.auth.UserProfileChangeRequest.Builder()
                        .setDisplayName(nombreCompleto)
                        .build()
                    user.updateProfile(profileUpdates).await()
                }

                val nom = if (nombreCompleto.isNotBlank()) nombreCompleto else correo.substringBefore("@")
                _usuarioNombre.value = nom
                _usuarioEmail.value = correo.trim()
                _conectado.value = true

                prefs.edit()
                    .putBoolean("conectado", true)
                    .putString("usuarioNombre", nom)
                    .putString("usuarioEmail", correo.trim())
                    .apply()

                // Limpiar base local antes de cargar los datos de este nuevo usuario
                dao.limpiarTodos()
                dao.limpiarCuentas()
                dao.limpiarPagosFijos()

                // Guardar perfil en Firestore
                try {
                    FirestoreRepository.guardarPerfilUsuario(
                        nombre = nombre.trim(),
                        apellido = apellido.trim(),
                        sexo = sexo,
                        edad = edad,
                        profesion = profesion.trim(),
                        estadoCivil = estadoCivil.trim()
                    )
                } catch (e: Exception) {
                    android.util.Log.e("FIRESTORE_SYNC", "Error guardando perfil: ${e.message}")
                }

                // Crear las 4 cuentas base en local y Firestore
                val cuentasBase = Cuenta.cuentasPorDefecto()
                dao.insertarCuentas(cuentasBase)
                for (c in cuentasBase) {
                    try {
                        FirestoreRepository.guardarCuenta(c)
                    } catch (e: Exception) {
                        android.util.Log.e("FIRESTORE_SYNC", "Error creando cuenta: ${e.message}")
                    }
                }

                _onboardingCompleted.value = false
                prefs.edit().putBoolean("onboarding_completed", false).apply()

                iniciarSincronizacionFirestore()
                onExito()
            } catch (e: Exception) {
                onError(e.localizedMessage ?: "Error al registrarse")
            }
        }
    }

    fun recuperarContrasena(correo: String, onExito: () -> Unit, onError: (String) -> Unit) {
        viewModelScope.launch {
            try {
                FirebaseAuth.getInstance().sendPasswordResetEmail(correo.trim()).await()
                onExito()
            } catch (e: Exception) {
                onError(e.localizedMessage ?: "Error al enviar correo de recuperación")
            }
        }
    }

    fun guardarSesionTelefono(
        user: com.google.firebase.auth.FirebaseUser,
        nombre: String = "",
        apellido: String = "",
        sexo: String = "",
        edad: Int? = null,
        profesion: String = "",
        estadoCivil: String = "",
        onExito: () -> Unit
    ) {
        val telefono = user.phoneNumber ?: "Usuario"
        val nombreFinal = if (nombre.isNotBlank()) nombre.trim() else telefono
        _usuarioNombre.value = nombreFinal
        _usuarioEmail.value = telefono
        _conectado.value = true

        prefs.edit()
            .putBoolean("conectado", true)
            .putString("usuarioNombre", nombreFinal)
            .putString("usuarioEmail", telefono)
            .apply()

        viewModelScope.launch {
            if (nombre.isNotBlank() || apellido.isNotBlank()) {
                val profileUpdates = UserProfileChangeRequest.Builder()
                    .setDisplayName(if (apellido.isNotBlank()) "$nombreFinal ${apellido.trim()}" else nombreFinal)
                    .build()
                try {
                    user.updateProfile(profileUpdates).await()
                } catch (e: Exception) {
                    android.util.Log.e("AUTH", "Error updating phone profile: ${e.message}")
                }
            }

            try {
                FirestoreRepository.guardarPerfilUsuario(
                    nombre = nombreFinal,
                    apellido = apellido.trim(),
                    sexo = sexo,
                    edad = edad,
                    profesion = profesion.trim(),
                    estadoCivil = estadoCivil.trim()
                )
            } catch (e: Exception) {
                android.util.Log.e("FIRESTORE_SYNC", "Error guardando perfil telefono: ${e.message}")
            }

            val yaTieneCuentas = FirestoreRepository.tieneCuentasConfiguradas()
            if (yaTieneCuentas) {
                _onboardingCompleted.value = true
                prefs.edit().putBoolean("onboarding_completed", true).apply()
            } else {
                val cuentasBase = Cuenta.cuentasPorDefecto()
                dao.insertarCuentas(cuentasBase)
                for (c in cuentasBase) {
                    try {
                        FirestoreRepository.guardarCuenta(c)
                    } catch (e: Exception) {
                        android.util.Log.e("FIRESTORE_SYNC", "Error creando cuenta: ${e.message}")
                    }
                }
                _onboardingCompleted.value = false
                prefs.edit().putBoolean("onboarding_completed", false).apply()
            }

            iniciarSincronizacionFirestore()
            onExito()
        }
    }

    // === AUTENTICACIÓN GOOGLE / FIREBASE ===
    fun guardarSesionGoogle(
        account: GoogleSignInAccount,
        onExito: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        val nombre = account.displayName ?: account.givenName ?: "Usuario"
        val email = account.email ?: ""
        val foto = account.photoUrl?.toString()

        _usuarioNombre.value = nombre
        _usuarioEmail.value = email
        _usuarioFoto.value = foto

        prefs.edit()
            .putBoolean("conectado", true)
            .putString("usuarioNombre", nombre)
            .putString("usuarioEmail", email)
            .putString("usuarioFoto", foto)
            .apply()

        viewModelScope.launch {
            try {
                val idToken = account.idToken
                if (!idToken.isNullOrBlank()) {
                    // Autenticación real con Google en Firebase
                    val credential = GoogleAuthProvider.getCredential(idToken, null)
                    FirebaseAuth.getInstance().signInWithCredential(credential).await()
                    android.util.Log.d("AUTH", "Firebase Auth OK — uid: ${FirebaseAuth.getInstance().currentUser?.uid}")
                } else {
                    // Sin idToken: intentar anónimo como fallback
                    android.util.Log.w("AUTH", "idToken nulo — usando auth anónima")
                    if (FirebaseAuth.getInstance().currentUser == null) {
                        try {
                            FirebaseAuth.getInstance().signInAnonymously().await()
                        } catch (anonEx: Exception) {
                            onError("Activa 'Google' o 'Anónimo' en Firebase Console → Authentication → Sign-in method")
                            return@launch
                        }
                    }
                }
                _conectado.value = true
                // Limpiar base local antes de cargar los datos de este usuario
                dao.limpiarTodos()
                dao.limpiarCuentas()
                dao.limpiarPagosFijos()

                // Si el usuario ya tiene cuentas en Firestore, saltar el onboarding directamente al inicio
                val yaTieneCuentas = FirestoreRepository.tieneCuentasConfiguradas()
                if (yaTieneCuentas) {
                    _onboardingCompleted.value = true
                    prefs.edit().putBoolean("onboarding_completed", true).apply()
                } else {
                    _onboardingCompleted.value = false
                    prefs.edit().putBoolean("onboarding_completed", false).apply()
                }

                iniciarSincronizacionFirestore()
                onExito()
            } catch (e: Exception) {
                android.util.Log.e("AUTH", "Error Firebase Auth: ${e.message}")
                onError("Error al autenticar: ${e.localizedMessage ?: e.message}")
            }
        }
    }

    // === ONBOARDING ===
    fun completarOnboardingConDatos(
        saldoEfectivo: Double,
        saldoBcp: Double,
        saldoAhorro: Double,
        saldoInversion: Double,
        diaCorte: Int,
        metaAhorro: Double
    ) {
        viewModelScope.launch {
            dao.actualizarSaldo("cuenta_efectivo", saldoEfectivo)
            dao.actualizarSaldo("cuenta_bcp_yape", saldoBcp)
            dao.actualizarSaldo("cuenta_ahorro", saldoAhorro)
            dao.actualizarSaldo("cuenta_inversion", saldoInversion)

            guardarDiaCorteMes(diaCorte)
            guardarMetaAhorroMes(metaAhorro)

            // Guardar cuentas iniciales en Firestore bajo el nuevo usuario
            val cuentasActualizadas = dao.todasCuentasLista()
            for (cta in cuentasActualizadas) {
                try {
                    FirestoreRepository.guardarCuenta(cta)
                } catch (e: Exception) {
                    android.util.Log.e("FIRESTORE_SYNC", "Error subiendo cuenta inicial: ${e.message}")
                }
            }

            _onboardingCompleted.value = true
            prefs.edit().putBoolean("onboarding_completed", true).apply()
        }
    }

    // === GASTO RÁPIDO DE 1 TOQUE ===
    fun registrarGastoRapido(boton: BotonRapido) {
        viewModelScope.launch {
            val cuentaDisponible = dao.todasCuentasLista().find { it.tipo == Cuenta.TIPO_DISPONIBLE }?.id ?: "cuenta_bcp_yape"
            agregar(
                monto = boton.monto,
                categoria = boton.categoria,
                nota = "Rápido: ${boton.etiqueta}",
                fecha = System.currentTimeMillis(),
                esIngreso = false,
                cuentaId = cuentaDisponible
            )
            _mensaje.value = "✓ Guardado ${boton.etiqueta}: S/ ${boton.monto}"
        }
    }

    // === TOPES POR CATEGORÍA ===
    fun guardarTopeCategoria(categoria: String, tope: Double) {
        val map = _topesCategorias.value.toMutableMap()
        map[categoria] = tope
        _topesCategorias.value = map
        prefs.edit().putFloat("tope_$categoria", tope.toFloat()).apply()
    }

    // === COPILOTO IA CHAT ===
    fun enviarMensajeCopiloto(textoUsuario: String) {
        _copilotoMensajes.value = _copilotoMensajes.value + MensajeChat(textoUsuario, true)
        viewModelScope.launch {
            _ocupado.value = true
            try {
                val cuentasList = dao.todasCuentasLista()
                val fijosList = dao.todosPagosFijosLista()
                val mesActual = SimpleDateFormat("yyyy-MM", Locale.US).format(Date())

                val cal = Calendar.getInstance()
                val diaActual = cal.get(Calendar.DAY_OF_MONTH)
                val diasMax = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
                val diasRestantes = (diasMax - diaActual + 1).coerceAtLeast(1)

                val disponible = saldoDisponibleTotal.value
                val fijosPend = pagosFijosPendientesMes.value
                val metaAhorro = metaAhorroMes.value
                val libreDiario = saldoLibrePorDia.value
                val ahorroTotal = saldoAhorroTotal.value
                val inversionTotal = saldoInversionTotal.value

                val todosGastos = dao.todosLista().filter { !it.esTransferencia && !it.esIngreso }
                val totalGastadoMes = todosGastos.sumOf { it.montoRealUsuario }

                val query = textoUsuario.lowercase().trim()

                // Función analista financiero local precisa y directa
                fun generarRespuestaAnalista(): String {
                    // 1. Preguntas sobre cuánto dinero hay en una cuenta específica
                    for (c in cuentasList) {
                        val nomLower = c.nombre.lowercase()
                        if (query.contains(nomLower) || (nomLower.contains("bcp") && query.contains("bcp")) ||
                            (nomLower.contains("yape") && query.contains("yape")) ||
                            (nomLower.contains("plin") && query.contains("plin")) ||
                            (nomLower.contains("efectivo") && query.contains("efectivo")) ||
                            (nomLower.contains("interbank") && query.contains("interbank")) ||
                            (nomLower.contains("bbva") && query.contains("bbva")) ||
                            (nomLower.contains("warda") && query.contains("warda")) ||
                            (nomLower.contains("ahorro") && query.contains("ahorro") && !query.contains("meta"))
                        ) {
                            return "En **${c.nombre}** tienes actualmente **S/ ${"%.2f".format(c.saldo)}**."
                        }
                    }

                    // 2. Preguntas de consulta general de saldos
                    if (query.contains("saldo") || query.contains("cuanto tengo") || query.contains("mi dinero") || query.contains("mis cuentas") || query.contains("liquidez")) {
                        val desglose = cuentasList.joinToString(", ") { "${it.nombre}: S/ ${"%.2f".format(it.saldo)}" }
                        return "Tienes **S/ ${"%.2f".format(disponible)}** disponible para gastos (efectivo y bancos).\nDesglose: $desglose."
                    }

                    // 3. Preguntas sobre si le alcanza para comprar algo
                    val montoRegex = Regex("""(\d+(?:\.\d+)?)""")
                    val montosEncontrados = montoRegex.findAll(query).mapNotNull { it.groupValues[1].toDoubleOrNull() }.toList()
                    val montoConsultado = montosEncontrados.firstOrNull()

                    if (query.contains("alcanza") || query.contains("puedo comprar") || query.contains("puedo gastar") || query.contains("compro")) {
                        if (montoConsultado != null && montoConsultado > 0) {
                            return if (montoConsultado <= libreDiario) {
                                "✅ **Sí te alcanza sin problemas**. Cuesta S/ ${"%.2f".format(montoConsultado)} y tu saldo libre de hoy es S/ ${"%.2f".format(libreDiario)} (te sobrarían S/ ${"%.2f".format(libreDiario - montoConsultado)} hoy sin afectar tus recibos ni tu ahorro)."
                            } else if (montoConsultado <= (disponible - fijosPend)) {
                                val nuevoLibre = ((disponible - fijosPend - metaAhorro - montoConsultado).coerceAtLeast(0.0)) / diasRestantes
                                "⚠️ **Te alcanza, pero te ajustará el mes**. Si gastas S/ ${"%.2f".format(montoConsultado)}, tu presupuesto diario para los próximos $diasRestantes días bajará de S/ ${"%.2f".format(libreDiario)} a S/ ${"%.2f".format(nuevoLibre)}/día."
                            } else {
                                "❌ **No te lo recomiendo**. Gastar S/ ${"%.2f".format(montoConsultado)} comprometería tus pagos fijos pendientes (S/ ${"%.2f".format(fijosPend)}) o tu meta de ahorro."
                            }
                        } else {
                            return "Tu presupuesto seguro para hoy es de **S/ ${"%.2f".format(libreDiario)}**. Si gastas más de eso, reducirás tu saldo disponible para los días restantes del mes."
                        }
                    }

                    // 4. Preguntas sobre compromisos fijos y pagos pendientes
                    if (query.contains("fijo") || query.contains("recibo") || query.contains("debo") || query.contains("pagar") || query.contains("pendiente") || query.contains("banquito")) {
                        val fijosPendientesList = fijosList.filter { it.ultimoMesPagado != mesActual }
                        return if (fijosPendientesList.isEmpty()) {
                            "¡Excelente! No tienes pagos fijos pendientes este mes. Todos están al día."
                        } else {
                            val detalle = fijosPendientesList.joinToString("\n") { "• ${it.nombre}: S/ ${"%.2f".format(it.monto)} (vence día ${it.diaPago})" }
                            "Tienes **S/ ${"%.2f".format(fijosPend)}** en pagos pendientes este mes:\n$detalle"
                        }
                    }

                    // 5. Preguntas sobre ahorros e inversiones
                    if (query.contains("ahorro") || query.contains("inversion") || query.contains("meta")) {
                        val porcMeta = if (metaAhorro > 0) ((ahorroTotal / metaAhorro) * 100).toInt() else 0
                        return "Tienes acumulado **S/ ${"%.2f".format(ahorroTotal)}** en ahorros y **S/ ${"%.2f".format(inversionTotal)}** en inversiones. Meta mensual: S/ ${"%.2f".format(metaAhorro)} ($porcMeta% cumplido)."
                    }

                    // 6. Respuesta general / diagnóstico diario
                    return "Tu presupuesto recomendado para hoy es **S/ ${"%.2f".format(libreDiario)}**. Cuentas con **S/ ${"%.2f".format(disponible)}** en liquidez disponible y tienes **S/ ${"%.2f".format(fijosPend)}** reservados para tus pagos fijos."
                }

                // Intentar respuesta de red si la cuota lo permite, y si no, responder inmediatamente con el motor contable exacto
                val prompt = """
                    Eres el asesor financiero personal de MisGastos en Perú.
                    El usuario pregunta: "$textoUsuario"
                    Cuentas reales: ${cuentasList.joinToString { "${it.nombre}: S/ ${it.saldo}" }}
                    Liquidez disponible: S/ $disponible. Pagos fijos pendientes: S/ $fijosPend. Ahorro: S/ $ahorroTotal.
                    Saldo libre diario: S/ $libreDiario. Días restantes: $diasRestantes.
                    Responde con datos precisos, conciso y en español peruano en 2-3 líneas.
                """.trimIndent()

                val respuestaFinal = try {
                    llamarGeminiTexto(prompt)
                } catch (_: Exception) {
                    generarRespuestaAnalista()
                }

                _copilotoMensajes.value = _copilotoMensajes.value + MensajeChat(respuestaFinal, false)
            } catch (e: Exception) {
                _copilotoMensajes.value = _copilotoMensajes.value + MensajeChat("Copiloto: Tienes S/ ${"%.2f".format(saldoDisponibleTotal.value)} disponible y S/ ${"%.2f".format(saldoLibrePorDia.value)} libre hoy.", false)
            } finally {
                _ocupado.value = false
            }
        }
    }

    // === SINCRONIZACIÓN FIRESTORE ===
    fun iniciarSincronizacionFirestore() {
        firestoreJob?.cancel()
        firestoreJob = viewModelScope.launch {
            sincronizarPendientes()

            launch {
                FirestoreRepository.escucharGastos().collect { listaRemota ->
                    try {
                        val idsRemotos = listaRemota.map { it.id }.toSet()
                        val locales = dao.todosLista()
                        for (local in locales) {
                            if (local.sincronizado && !idsRemotos.contains(local.id)) {
                                dao.eliminar(local)
                            }
                        }
                        dao.insertarTodos(listaRemota)
                    } catch (_: Exception) {}
                }
            }

            launch {
                FirestoreRepository.escucharCuentas().collect { cuentasRemotas ->
                    if (cuentasRemotas.isNotEmpty()) {
                        dao.insertarCuentas(cuentasRemotas)
                    }
                }
            }

            launch {
                FirestoreRepository.escucharPagosFijos().collect { pagosRemotos ->
                    for (p in pagosRemotos) {
                        dao.insertarPagoFijo(p)
                    }
                }
            }
        }
    }

    private suspend fun sincronizarPendientes() {
        val pendientes = dao.pendientes()
        for (pend in pendientes) {
            try {
                FirestoreRepository.guardar(pend)
                dao.marcarSincronizado(pend.id)
            } catch (_: Exception) {}
        }
    }

    fun sincronizar() {
        viewModelScope.launch {
            _ocupado.value = true
            _mensaje.value = null
            try {
                sincronizarPendientes()
                val remotos = FirestoreRepository.obtenerGastosRemotos()
                val idsRemotos = remotos.map { it.id }.toSet()
                val locales = dao.todosLista()
                for (local in locales) {
                    if (local.sincronizado && !idsRemotos.contains(local.id)) {
                        dao.eliminar(local)
                    }
                }
                dao.insertarTodos(remotos)
                _mensaje.value = "Sincronizado con Firebase exitosamente"
            } catch (e: Exception) {
                _mensaje.value = "Error de sincronización: ${e.message}"
            } finally {
                _ocupado.value = false
            }
        }
    }

    fun desconectar() {
        firestoreJob?.cancel()
        firestoreJob = null

        try {
            FirebaseAuth.getInstance().signOut()
        } catch (_: Exception) {}

        _conectado.value = false
        _usuarioNombre.value = null
        _usuarioEmail.value = null
        _usuarioFoto.value = null
        _onboardingCompleted.value = false

        prefs.edit()
            .remove("conectado")
            .remove("usuarioNombre")
            .remove("usuarioEmail")
            .remove("usuarioFoto")
            .remove("onboarding_completed")
            .apply()

        viewModelScope.launch {
            // Limpiar absolutamente todos los datos locales para que no se filtren a otra cuenta
            dao.limpiarTodos()
            dao.limpiarCuentas()
            dao.limpiarPagosFijos()
        }
    }

    // === GESTIÓN DE CUENTAS & SALDOS ===
    fun actualizarSaldoCuenta(cuentaId: String, nuevoSaldo: Double) {
        viewModelScope.launch {
            dao.actualizarSaldo(cuentaId, nuevoSaldo)
            val cuenta = dao.todasCuentasLista().find { it.id == cuentaId }
            if (cuenta != null) {
                try {
                    FirestoreRepository.guardarCuenta(cuenta.copy(saldo = nuevoSaldo))
                } catch (_: Exception) {}
            }
        }
    }

    fun crearCuenta(nombre: String, tipo: String, saldoInicial: Double) {
        viewModelScope.launch {
            val nueva = Cuenta(
                nombre = nombre,
                tipo = tipo,
                saldo = saldoInicial,
                colorHex = when(tipo) {
                    Cuenta.TIPO_DISPONIBLE -> 0xFF4CAF50
                    Cuenta.TIPO_AHORRO -> 0xFF009688
                    else -> 0xFFFF9800
                }
            )
            dao.insertarCuenta(nueva)
            try {
                FirestoreRepository.guardarCuenta(nueva)
            } catch (_: Exception) {}
        }
    }

    // === TRANSFERENCIA NEUTRA ENTRE CUENTAS ===
    fun realizarTransferencia(cuentaOrigenId: String, cuentaDestinoId: String, monto: Double, nota: String) {
        viewModelScope.launch {
            val cuentasList = dao.todasCuentasLista()
            val origen = cuentasList.find { it.id == cuentaOrigenId }
            val destino = cuentasList.find { it.id == cuentaDestinoId }

            if (origen == null || destino == null) {
                _mensaje.value = "Cuentas no válidas"
                return@launch
            }

            val nuevoSaldoOrigen = origen.saldo - monto
            val nuevoSaldoDestino = destino.saldo + monto
            dao.actualizarSaldo(origen.id, nuevoSaldoOrigen)
            dao.actualizarSaldo(destino.id, nuevoSaldoDestino)

            try {
                FirestoreRepository.guardarCuenta(origen.copy(saldo = nuevoSaldoOrigen))
                FirestoreRepository.guardarCuenta(destino.copy(saldo = nuevoSaldoDestino))
            } catch (_: Exception) {}

            val gasto = Gasto(
                monto = monto,
                categoria = "Transferencia",
                nota = if (nota.isNotBlank()) nota else "De ${origen.nombre} a ${destino.nombre}",
                fecha = System.currentTimeMillis(),
                esIngreso = false,
                esTransferencia = true,
                cuentaId = origen.id,
                cuentaDestinoId = destino.id,
                sincronizado = false
            )
            dao.insertar(gasto)
            try {
                FirestoreRepository.guardar(gasto)
                dao.marcarSincronizado(gasto.id)
            } catch (_: Exception) {}

            _mensaje.value = "Transferencia de S/ $monto realizada"
        }
    }

    // === TRANSACCIONES (GASTOS E INGRESOS) ===
    fun agregar(
        monto: Double,
        categoria: String,
        nota: String,
        fecha: Long,
        esIngreso: Boolean = false,
        cuentaId: String = "cuenta_bcp_yape",
        montoPorCobrar: Double = 0.0,
        deudor: String = "",
        codigoOperacion: String = ""
    ) {
        viewModelScope.launch {
            val gasto = Gasto(
                monto = monto,
                categoria = categoria,
                nota = nota.trim(),
                fecha = fecha,
                esIngreso = esIngreso,
                cuentaId = cuentaId,
                montoPorCobrar = montoPorCobrar,
                deudor = deudor.trim(),
                codigoOperacion = codigoOperacion.trim(),
                sincronizado = false
            )
            dao.insertar(gasto)

            val cuentasList = dao.todasCuentasLista()
            val cuenta = cuentasList.find { it.id == cuentaId }
            if (cuenta != null) {
                val ajuste = if (esIngreso) monto else -monto
                val nuevoSaldo = cuenta.saldo + ajuste
                dao.actualizarSaldo(cuenta.id, nuevoSaldo)
                try {
                    FirestoreRepository.guardarCuenta(cuenta.copy(saldo = nuevoSaldo))
                } catch (_: Exception) {}
            }

            try {
                FirestoreRepository.guardar(gasto)
                dao.marcarSincronizado(gasto.id)
            } catch (_: Exception) {}

            if (esIngreso && _reglaAhorroAutoPct.value > 0) {
                val montoASeparar = (monto * _reglaAhorroAutoPct.value) / 100.0
                _sugerenciaAhorroIngreso.value = Pair(monto, montoASeparar)
            }
        }
    }

    fun aceptarSugerenciaAhorro(montoAhorro: Double) {
        val cuentaOrigen = cuentas.value.find { it.tipo == Cuenta.TIPO_DISPONIBLE }?.id ?: "cuenta_bcp_yape"
        val cuentaAhorro = cuentas.value.find { it.tipo == Cuenta.TIPO_AHORRO }?.id ?: "cuenta_ahorro"
        realizarTransferencia(cuentaOrigen, cuentaAhorro, montoAhorro, "Ahorro automático (${_reglaAhorroAutoPct.value}% de ingreso)")
        _sugerenciaAhorroIngreso.value = null
    }

    fun descartarSugerenciaAhorro() {
        _sugerenciaAhorroIngreso.value = null
    }

    fun eliminar(gasto: Gasto) {
        viewModelScope.launch {
            dao.eliminar(gasto)
            val cuenta = dao.todasCuentasLista().find { it.id == gasto.cuentaId }
            if (cuenta != null) {
                val reversa = if (gasto.esIngreso) -gasto.monto else gasto.monto
                val nuevoSaldo = cuenta.saldo + reversa
                dao.actualizarSaldo(cuenta.id, nuevoSaldo)
                try {
                    FirestoreRepository.guardarCuenta(cuenta.copy(saldo = nuevoSaldo))
                } catch (_: Exception) {}
            }
            try {
                FirestoreRepository.eliminar(gasto.id)
            } catch (_: Exception) {}
        }
    }

    fun marcarCuentaCobrada(gastoId: String) {
        viewModelScope.launch {
            dao.marcarCobrado(gastoId)
            val gasto = dao.todosLista().find { it.id == gastoId }
            if (gasto != null) {
                val actualizado = gasto.copy(cobrado = true)
                val cuenta = dao.todasCuentasLista().find { it.id == gasto.cuentaId }
                if (cuenta != null && gasto.montoPorCobrar > 0) {
                    val nuevoSaldo = cuenta.saldo + gasto.montoPorCobrar
                    dao.actualizarSaldo(cuenta.id, nuevoSaldo)
                    try {
                        FirestoreRepository.guardarCuenta(cuenta.copy(saldo = nuevoSaldo))
                    } catch (_: Exception) {}
                }
                try {
                    FirestoreRepository.guardar(actualizado)
                } catch (_: Exception) {}
                _mensaje.value = "Cobro registrado de S/ ${gasto.montoPorCobrar} (${gasto.deudor})"
            }
        }
    }

    // === PAGOS FIJOS CHECKLIST ===
    fun crearPagoFijo(
        nombre: String,
        monto: Double,
        diaPago: Int,
        categoria: String,
        cuentaId: String,
        frecuenciaTipo: Int = FRECUENCIA_MENSUAL // 0: Mensual, 1: Quincenal, 2: Semanal, 3: Diario
    ) {
        viewModelScope.launch {
            val cal = Calendar.getInstance()
            val diasMax = cal.getActualMaximum(Calendar.DAY_OF_MONTH)

            when (frecuenciaTipo) {
                FRECUENCIA_SEMANAL -> {
                    // Semanal: cada 7 días a partir del día inicial, dentro del mes
                    val diaBase = diaPago.coerceIn(1, 7)
                    val dias = mutableListOf<Int>()
                    var d = diaBase
                    while (d <= diasMax) {
                        dias.add(d)
                        d += 7
                    }
                    dias.forEachIndexed { index, diaCalculado ->
                        val nuevo = PagoFijo(
                            nombre = "$nombre (Semana ${index + 1})",
                            monto = monto,
                            diaPago = diaCalculado,
                            categoria = categoria,
                            cuentaId = cuentaId
                        )
                        dao.insertarPagoFijo(nuevo)
                        try {
                            FirestoreRepository.guardarPagoFijo(nuevo)
                        } catch (_: Exception) {}
                    }
                }
                FRECUENCIA_QUINCENAL -> {
                    // Quincenal: 2 pagos en el mes espaciados congruentemente (ej. día base y día base + 15 o fin de mes)
                    val dia1 = diaPago.coerceIn(1, 15)
                    val dia2 = (dia1 + 15).coerceAtMost(diasMax)
                    val fechas = listOf(dia1, dia2)

                    fechas.forEachIndexed { index, diaCalculado ->
                        val nuevo = PagoFijo(
                            nombre = "$nombre (Quincena ${index + 1})",
                            monto = monto,
                            diaPago = diaCalculado,
                            categoria = categoria,
                            cuentaId = cuentaId
                        )
                        dao.insertarPagoFijo(nuevo)
                        try {
                            FirestoreRepository.guardarPagoFijo(nuevo)
                        } catch (_: Exception) {}
                    }
                }
                FRECUENCIA_DIARIA -> {
                    // Diario: 1 pago fijo representativo acumulado o diario
                    val nuevo = PagoFijo(
                        nombre = "$nombre (Diario)",
                        monto = monto,
                        diaPago = diaPago.coerceIn(1, diasMax),
                        categoria = categoria,
                        cuentaId = cuentaId
                    )
                    dao.insertarPagoFijo(nuevo)
                    try {
                        FirestoreRepository.guardarPagoFijo(nuevo)
                    } catch (_: Exception) {}
                }
                else -> {
                    // Mensual: 1 solo pago en el día seleccionado
                    val nuevo = PagoFijo(
                        nombre = nombre,
                        monto = monto,
                        diaPago = diaPago.coerceIn(1, diasMax),
                        categoria = categoria,
                        cuentaId = cuentaId
                    )
                    dao.insertarPagoFijo(nuevo)
                    try {
                        FirestoreRepository.guardarPagoFijo(nuevo)
                    } catch (_: Exception) {}
                }
            }
        }
    }

    companion object {
        const val FRECUENCIA_MENSUAL = 0
        const val FRECUENCIA_QUINCENAL = 1
        const val FRECUENCIA_SEMANAL = 2
        const val FRECUENCIA_DIARIA = 3

        fun diasSemanaNombres() = listOf("Lunes", "Martes", "Miércoles", "Jueves", "Viernes", "Sábado", "Domingo")

        fun obtenerDiasCobroMes(ingreso: IngresoProgramado, anio: Int, mes: Int): List<Int> {
            val cal = Calendar.getInstance().apply {
                set(Calendar.YEAR, anio)
                set(Calendar.MONTH, mes)
                set(Calendar.DAY_OF_MONTH, 1)
            }
            val maxDias = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
            return when (ingreso.frecuenciaTipo) {
                FRECUENCIA_MENSUAL -> {
                    listOf(ingreso.diaCobro.coerceIn(1, maxDias))
                }
                FRECUENCIA_QUINCENAL -> {
                    val d1 = ingreso.diaCobro.coerceIn(1, maxDias)
                    val d2 = (if (ingreso.diaCobro2 > 0) ingreso.diaCobro2 else 30).coerceIn(1, maxDias)
                    listOf(d1, d2).distinct().sorted()
                }
                FRECUENCIA_SEMANAL -> {
                    val targetCalDay = when (ingreso.diaCobro) {
                        1 -> Calendar.MONDAY
                        2 -> Calendar.TUESDAY
                        3 -> Calendar.WEDNESDAY
                        4 -> Calendar.THURSDAY
                        5 -> Calendar.FRIDAY
                        6 -> Calendar.SATURDAY
                        7 -> Calendar.SUNDAY
                        else -> Calendar.FRIDAY
                    }
                    val dias = mutableListOf<Int>()
                    for (d in 1..maxDias) {
                        cal.set(Calendar.DAY_OF_MONTH, d)
                        if (cal.get(Calendar.DAY_OF_WEEK) == targetCalDay) {
                            dias.add(d)
                        }
                    }
                    dias
                }
                else -> listOf(1)
            }
        }

        fun estaFechaCobrada(ingreso: IngresoProgramado, fechaKey: String): Double? {
            try {
                val obj = org.json.JSONObject(ingreso.cobrosJson.ifBlank { "{}" })
                if (obj.has(fechaKey)) {
                    val item = obj.getJSONObject(fechaKey)
                    return item.optDouble("monto", ingreso.montoEsperado)
                }
            } catch (_: Exception) {}
            val mesPrefix = fechaKey.substringBeforeLast("-")
            if (ingreso.ultimoMesCobrado == mesPrefix && ingreso.montoCobradoReal > 0) {
                return ingreso.montoCobradoReal
            }
            return null
        }

        fun totalEsperadoMes(ingreso: IngresoProgramado, anio: Int, mes: Int): Double {
            val dias = obtenerDiasCobroMes(ingreso, anio, mes)
            return when (ingreso.frecuenciaTipo) {
                FRECUENCIA_MENSUAL -> ingreso.montoEsperado
                FRECUENCIA_QUINCENAL -> ingreso.montoEsperado * dias.size
                FRECUENCIA_SEMANAL -> ingreso.montoEsperado * dias.size
                else -> ingreso.montoEsperado
            }
        }

        fun totalCobradoMes(ingreso: IngresoProgramado, anio: Int, mes: Int): Double {
            val dias = obtenerDiasCobroMes(ingreso, anio, mes)
            var total = 0.0
            for (d in dias) {
                val fKey = String.format(java.util.Locale.US, "%04d-%02d-%02d", anio, mes + 1, d)
                val cobrado = estaFechaCobrada(ingreso, fKey)
                if (cobrado != null) {
                    total += cobrado
                }
            }
            return total
        }

        fun coincideFechaDeuda(timestamp: Long, anio: Int, mes: Int, dia: Int): Boolean {
            if (timestamp <= 0L) return false
            val cal = Calendar.getInstance().apply { timeInMillis = timestamp }
            return cal.get(Calendar.YEAR) == anio &&
                   cal.get(Calendar.MONTH) == mes &&
                   cal.get(Calendar.DAY_OF_MONTH) == dia
        }
    }

    fun marcarPagoFijoPagado(pago: PagoFijo) {
        viewModelScope.launch {
            val mesActual = SimpleDateFormat("yyyy-MM", Locale.US).format(Date())
            dao.marcarPagoFijoMes(pago.id, mesActual)
            try {
                FirestoreRepository.guardarPagoFijo(pago.copy(ultimoMesPagado = mesActual))
            } catch (_: Exception) {}

            agregar(
                monto = pago.monto,
                categoria = pago.categoria,
                nota = "Pago fijo: ${pago.nombre}",
                fecha = System.currentTimeMillis(),
                esIngreso = false,
                cuentaId = pago.cuentaId
            )
            _mensaje.value = "Pago '${pago.nombre}' marcado como pagado"
        }
    }

    fun eliminarPagoFijo(pago: PagoFijo) {
        viewModelScope.launch {
            dao.eliminarPagoFijo(pago)
            try {
                FirestoreRepository.eliminarPagoFijo(pago.id)
            } catch (_: Exception) {}
        }
    }

    fun eliminarCuenta(cuenta: Cuenta) {
        viewModelScope.launch {
            dao.eliminarCuenta(cuenta)
            try {
                FirestoreRepository.eliminarCuenta(cuenta.id)
            } catch (_: Exception) {}
            _mensaje.value = "Cuenta '${cuenta.nombre}' eliminada"
        }
    }

    // Editar cuenta directamente (nombre y/o saldo)
    fun editarCuenta(cuenta: Cuenta) {
        viewModelScope.launch {
            dao.insertarCuenta(cuenta) // Room usa REPLACE on conflict
            try {
                FirestoreRepository.guardarCuenta(cuenta)
            } catch (_: Exception) {}
            _mensaje.value = "Cuenta '${cuenta.nombre}' actualizada"
        }
    }

    // Editar pago fijo existente
    fun editarPagoFijo(pago: PagoFijo) {
        viewModelScope.launch {
            dao.insertarPagoFijo(pago)
            try {
                FirestoreRepository.guardarPagoFijo(pago)
            } catch (_: Exception) {}
            _mensaje.value = "Pago '${pago.nombre}' actualizado"
        }
    }

    // === INGRESOS PROGRAMADOS / PROYECTADOS ===
    private val _ingresosProgramados = MutableStateFlow<List<IngresoProgramado>>(cargarIngresosProgramados())
    val ingresosProgramados: StateFlow<List<IngresoProgramado>> = _ingresosProgramados.asStateFlow()

    private fun cargarIngresosProgramados(): List<IngresoProgramado> {
        val raw = prefs.getString("ingresos_programados", null) ?: return listOf(
            IngresoProgramado(
                nombre = "Sueldo Principal",
                montoEsperado = 1500.0,
                frecuenciaTipo = FRECUENCIA_MENSUAL,
                diaCobro = 15,
                diaCobro2 = 30,
                categoria = "Sueldo"
            )
        )
        return try {
            val arr = org.json.JSONArray(raw)
            val list = mutableListOf<IngresoProgramado>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    IngresoProgramado(
                        id = obj.optString("id", java.util.UUID.randomUUID().toString()),
                        nombre = obj.optString("nombre", "Ingreso"),
                        montoEsperado = obj.optDouble("montoEsperado", 0.0),
                        frecuenciaTipo = obj.optInt("frecuenciaTipo", 0),
                        diaCobro = obj.optInt("diaCobro", 1),
                        diaCobro2 = obj.optInt("diaCobro2", 30),
                        categoria = obj.optString("categoria", "Sueldo"),
                        cuentaId = obj.optString("cuentaId", "cuenta_bcp_yape"),
                        ultimoMesCobrado = obj.optString("ultimoMesCobrado", ""),
                        montoCobradoReal = obj.optDouble("montoCobradoReal", 0.0),
                        notaCobro = obj.optString("notaCobro", ""),
                        cobrosJson = obj.optString("cobrosJson", "{}")
                    )
                )
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun guardarIngresosProgramadosEnPrefs(lista: List<IngresoProgramado>) {
        val arr = org.json.JSONArray()
        for (item in lista) {
            val obj = org.json.JSONObject().apply {
                put("id", item.id)
                put("nombre", item.nombre)
                put("montoEsperado", item.montoEsperado)
                put("frecuenciaTipo", item.frecuenciaTipo)
                put("diaCobro", item.diaCobro)
                put("diaCobro2", item.diaCobro2)
                put("categoria", item.categoria)
                put("cuentaId", item.cuentaId)
                put("ultimoMesCobrado", item.ultimoMesCobrado)
                put("montoCobradoReal", item.montoCobradoReal)
                put("notaCobro", item.notaCobro)
                put("cobrosJson", item.cobrosJson)
            }
            arr.put(obj)
        }
        prefs.edit().putString("ingresos_programados", arr.toString()).apply()
    }

    fun crearIngresoProgramado(
        nombre: String,
        montoEsperado: Double,
        diaCobro: Int,
        diaCobro2: Int = 30,
        categoria: String,
        cuentaId: String,
        frecuenciaTipo: Int
    ) {
        val nuevo = IngresoProgramado(
            nombre = nombre.trim(),
            montoEsperado = montoEsperado,
            diaCobro = diaCobro,
            diaCobro2 = diaCobro2,
            categoria = categoria,
            cuentaId = cuentaId,
            frecuenciaTipo = frecuenciaTipo
        )
        val nuevaLista = _ingresosProgramados.value + nuevo
        _ingresosProgramados.value = nuevaLista
        guardarIngresosProgramadosEnPrefs(nuevaLista)
        _mensaje.value = "Ingreso programado '$nombre' registrado"
    }

    fun editarIngresoProgramado(ingreso: IngresoProgramado) {
        val nuevaLista = _ingresosProgramados.value.map { if (it.id == ingreso.id) ingreso else it }
        _ingresosProgramados.value = nuevaLista
        guardarIngresosProgramadosEnPrefs(nuevaLista)
        _mensaje.value = "Ingreso '${ingreso.nombre}' actualizado"
    }

    fun eliminarIngresoProgramado(ingreso: IngresoProgramado) {
        val nuevaLista = _ingresosProgramados.value.filter { it.id != ingreso.id }
        _ingresosProgramados.value = nuevaLista
        guardarIngresosProgramadosEnPrefs(nuevaLista)
        _mensaje.value = "Ingreso '${ingreso.nombre}' eliminado"
    }

    fun cobrarIngresoProgramadoFecha(
        ingreso: IngresoProgramado,
        fechaKey: String, // "yyyy-MM-dd"
        montoReal: Double,
        cuentaDestinoId: String,
        nota: String = ""
    ) {
        viewModelScope.launch {
            val mesActual = fechaKey.substringBeforeLast("-")
            val notaFinal = if (nota.isNotBlank()) {
                nota.trim()
            } else if (montoReal != ingreso.montoEsperado) {
                "${ingreso.nombre} ($fechaKey: S/ ${"%.2f".format(montoReal)}, proyectado S/ ${"%.2f".format(ingreso.montoEsperado)})"
            } else {
                "${ingreso.nombre} ($fechaKey)"
            }

            agregar(
                monto = montoReal,
                categoria = ingreso.categoria,
                nota = notaFinal,
                fecha = System.currentTimeMillis(),
                esIngreso = true,
                cuentaId = cuentaDestinoId
            )

            val objCobros = try {
                org.json.JSONObject(ingreso.cobrosJson.ifBlank { "{}" })
            } catch (_: Exception) {
                org.json.JSONObject()
            }
            val detalle = org.json.JSONObject().apply {
                put("monto", montoReal)
                put("fecha", System.currentTimeMillis())
                put("nota", notaFinal)
                put("cuentaId", cuentaDestinoId)
            }
            objCobros.put(fechaKey, detalle)

            val actualizado = ingreso.copy(
                ultimoMesCobrado = mesActual,
                montoCobradoReal = montoReal,
                notaCobro = notaFinal,
                cuentaId = cuentaDestinoId,
                cobrosJson = objCobros.toString()
            )
            val nuevaLista = _ingresosProgramados.value.map { if (it.id == ingreso.id) actualizado else it }
            _ingresosProgramados.value = nuevaLista
            guardarIngresosProgramadosEnPrefs(nuevaLista)
            _mensaje.value = "¡Cobrado! +S/ ${"%.2f".format(montoReal)} a ${ingreso.nombre}"
        }
    }

    fun cobrarIngresoProgramado(
        ingreso: IngresoProgramado,
        montoReal: Double,
        cuentaDestinoId: String,
        nota: String = ""
    ) {
        val hoyKey = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        cobrarIngresoProgramadoFecha(ingreso, hoyKey, montoReal, cuentaDestinoId, nota)
    }

    // === DEUDAS Y PRÉSTAMOS (NOS DEBEN / DEBEMOS) ===
    private val _deudasPrestamos = MutableStateFlow<List<DeudaPrestamo>>(cargarDeudasPrestamos())
    val deudasPrestamos: StateFlow<List<DeudaPrestamo>> = _deudasPrestamos.asStateFlow()

    private fun cargarDeudasPrestamos(): List<DeudaPrestamo> {
        val raw = prefs.getString("deudas_prestamos", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<DeudaPrestamo>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    DeudaPrestamo(
                        id = obj.optString("id", java.util.UUID.randomUUID().toString()),
                        persona = obj.optString("persona", ""),
                        tipo = obj.optInt("tipo", DeudaPrestamo.TIPO_POR_COBRAR),
                        montoBase = obj.optDouble("montoBase", 0.0),
                        fechaRegistro = obj.optLong("fechaRegistro", System.currentTimeMillis()),
                        tipoInteres = obj.optInt("tipoInteres", DeudaPrestamo.INTERES_NINGUNO),
                        valorInteres = obj.optDouble("valorInteres", 0.0),
                        fechaLimitePago = obj.optLong("fechaLimitePago", 0L),
                        cuentaId = obj.optString("cuentaId", "cuenta_bcp_yape"),
                        estado = obj.optInt("estado", DeudaPrestamo.ESTADO_PENDIENTE),
                        fechaCobradoPagado = obj.optLong("fechaCobradoPagado", 0L),
                        nota = obj.optString("nota", "")
                    )
                )
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun guardarDeudasPrestamosEnPrefs(lista: List<DeudaPrestamo>) {
        val arr = JSONArray()
        for (item in lista) {
            val obj = JSONObject().apply {
                put("id", item.id)
                put("persona", item.persona)
                put("tipo", item.tipo)
                put("montoBase", item.montoBase)
                put("fechaRegistro", item.fechaRegistro)
                put("tipoInteres", item.tipoInteres)
                put("valorInteres", item.valorInteres)
                put("fechaLimitePago", item.fechaLimitePago)
                put("cuentaId", item.cuentaId)
                put("estado", item.estado)
                put("fechaCobradoPagado", item.fechaCobradoPagado)
                put("nota", item.nota)
            }
            arr.put(obj)
        }
        prefs.edit().putString("deudas_prestamos", arr.toString()).apply()
    }

    fun crearDeudaPrestamo(
        persona: String,
        tipo: Int,
        montoBase: Double,
        fechaRegistro: Long,
        tipoInteres: Int,
        valorInteres: Double,
        fechaLimitePago: Long,
        cuentaId: String,
        nota: String,
        afectarSaldoInicial: Boolean = false
    ) {
        val nuevo = DeudaPrestamo(
            persona = persona.trim(),
            tipo = tipo,
            montoBase = montoBase,
            fechaRegistro = fechaRegistro,
            tipoInteres = tipoInteres,
            valorInteres = valorInteres,
            fechaLimitePago = fechaLimitePago,
            cuentaId = cuentaId,
            nota = nota.trim()
        )
        val nuevaLista = _deudasPrestamos.value + nuevo
        _deudasPrestamos.value = nuevaLista
        guardarDeudasPrestamosEnPrefs(nuevaLista)

        if (afectarSaldoInicial) {
            if (tipo == DeudaPrestamo.TIPO_POR_COBRAR) {
                // Prestamos dinero: sale dinero de la cuenta
                agregar(
                    monto = montoBase,
                    categoria = "Préstamos",
                    nota = "Préstamo otorgado a ${persona.trim()}${if (nota.isNotBlank()) " ($nota)" else ""}",
                    fecha = fechaRegistro,
                    esIngreso = false,
                    cuentaId = cuentaId
                )
            } else {
                // Nos prestaron dinero: entra dinero a la cuenta
                agregar(
                    monto = montoBase,
                    categoria = "Préstamos",
                    nota = "Préstamo recibido de ${persona.trim()}${if (nota.isNotBlank()) " ($nota)" else ""}",
                    fecha = fechaRegistro,
                    esIngreso = true,
                    cuentaId = cuentaId
                )
            }
        }
        _mensaje.value = if (tipo == DeudaPrestamo.TIPO_POR_COBRAR) {
            "Préstamo a '${persona.trim()}' registrado"
        } else {
            "Deuda con '${persona.trim()}' registrada"
        }
    }

    fun editarDeudaPrestamo(deuda: DeudaPrestamo) {
        val nuevaLista = _deudasPrestamos.value.map { if (it.id == deuda.id) deuda else it }
        _deudasPrestamos.value = nuevaLista
        guardarDeudasPrestamosEnPrefs(nuevaLista)
        _mensaje.value = "Registro actualizado"
    }

    fun eliminarDeudaPrestamo(id: String) {
        val nuevaLista = _deudasPrestamos.value.filter { it.id != id }
        _deudasPrestamos.value = nuevaLista
        guardarDeudasPrestamosEnPrefs(nuevaLista)
        _mensaje.value = "Registro eliminado"
    }

    fun liquidarDeudaPrestamo(id: String, cuentaDestinoId: String? = null) {
        viewModelScope.launch {
            val deuda = _deudasPrestamos.value.find { it.id == id } ?: return@launch
            val cId = cuentaDestinoId ?: deuda.cuentaId
            val montoTotal = deuda.montoTotalFinal

            if (deuda.tipo == DeudaPrestamo.TIPO_POR_COBRAR) {
                // Cobramos el préstamo (+ capital + interés)
                agregar(
                    monto = montoTotal,
                    categoria = "Préstamos",
                    nota = "Cobro de préstamo a ${deuda.persona}${if (deuda.tipoInteres != DeudaPrestamo.INTERES_NINGUNO) " (incluye intereses)" else ""}",
                    fecha = System.currentTimeMillis(),
                    esIngreso = true,
                    cuentaId = cId
                )
                _mensaje.value = "¡Cobro de S/ ${"%.2f".format(montoTotal)} a ${deuda.persona} registrado con éxito!"
            } else {
                // Pagamos la deuda (- capital + interés)
                agregar(
                    monto = montoTotal,
                    categoria = "Préstamos",
                    nota = "Pago de deuda a ${deuda.persona}${if (deuda.tipoInteres != DeudaPrestamo.INTERES_NINGUNO) " (incluye intereses)" else ""}",
                    fecha = System.currentTimeMillis(),
                    esIngreso = false,
                    cuentaId = cId
                )
                _mensaje.value = "¡Pago de S/ ${"%.2f".format(montoTotal)} a ${deuda.persona} registrado con éxito!"
            }

            val actualizada = deuda.copy(
                estado = DeudaPrestamo.ESTADO_COMPLETADO,
                fechaCobradoPagado = System.currentTimeMillis(),
                cuentaId = cId
            )
            val nuevaLista = _deudasPrestamos.value.map { if (it.id == id) actualizada else it }
            _deudasPrestamos.value = nuevaLista
            guardarDeudasPrestamosEnPrefs(nuevaLista)
        }
    }

    // Editar tope de una categoría
    fun editarTopeCat(categoria: String, nuevoTope: Double) {
        val map = _topesCategorias.value.toMutableMap()
        map[categoria] = nuevoTope
        _topesCategorias.value = map
        _mensaje.value = "Tope de '$categoria' actualizado a S/ $nuevoTope"
    }

    // === AJUSTES ===
    fun guardarPresupuesto(valor: Double) {
        _presupuesto.value = valor
        prefs.edit().putFloat("presupuesto", valor.toFloat()).apply()
    }

    fun guardarMetaAhorroMes(valor: Double) {
        _metaAhorroMes.value = valor
        prefs.edit().putFloat("meta_ahorro_mes", valor.toFloat()).apply()
    }

    fun guardarReglaAhorroAuto(pct: Int) {
        _reglaAhorroAutoPct.value = pct
        prefs.edit().putInt("regla_ahorro_pct", pct).apply()
    }

    fun guardarDiaCorteMes(dia: Int) {
        _diaCorteMes.value = dia
        prefs.edit().putInt("dia_corte_mes", dia).apply()
    }

    fun limpiarMensaje() {
        _mensaje.value = null
    }

    fun descartarMovimientosDetectados() {
        _movimientosDetectados.value = null
    }

    fun confirmarMovimientosDetectados(confirmados: List<MovimientoDetectado>) {
        viewModelScope.launch {
            val cuentasList = dao.todasCuentasLista()
            for (m in confirmados) {
                // Búsqueda inteligente de cuenta:
                // 1. Coincidencia por nombre (ej: "yape" en "BCP / Yape")
                // 2. Si no coincide, buscar si mencionó "bcp" o "yape" específicamente
                // 3. Fallback a la primera cuenta de tipo DISPONIBLE
                val cuentaId = cuentasList.find { cta ->
                    cta.nombre.contains(m.cuentaNombre, ignoreCase = true) ||
                    (m.cuentaNombre.contains("yape", ignoreCase = true) && cta.nombre.contains("yape", ignoreCase = true)) ||
                    (m.cuentaNombre.contains("bcp", ignoreCase = true) && cta.nombre.contains("bcp", ignoreCase = true)) ||
                    (m.cuentaNombre.contains("efectivo", ignoreCase = true) && cta.nombre.contains("efectivo", ignoreCase = true))
                }?.id ?: cuentasList.find { it.tipo == Cuenta.TIPO_DISPONIBLE }?.id ?: "cuenta_bcp_yape"

                if (m.esTransferencia && m.cuentaDestinoNombre != null) {
                    val destId = cuentasList.find { cta ->
                        cta.nombre.contains(m.cuentaDestinoNombre, ignoreCase = true) ||
                        (m.cuentaDestinoNombre.contains("ahorro", ignoreCase = true) && cta.tipo == Cuenta.TIPO_AHORRO)
                    }?.id ?: cuentasList.find { it.tipo == Cuenta.TIPO_AHORRO }?.id ?: "cuenta_ahorro"
                    realizarTransferencia(cuentaId, destId, m.monto, m.nota)
                } else {
                    agregar(
                        monto = m.monto,
                        categoria = m.categoria,
                        nota = m.nota,
                        fecha = m.fecha,
                        esIngreso = m.esIngreso,
                        cuentaId = cuentaId,
                        montoPorCobrar = m.montoPorCobrar,
                        deudor = m.deudor,
                        codigoOperacion = m.codigoOperacion
                    )
                }
            }
            _movimientosDetectados.value = null
            _mensaje.value = "Movimientos guardados exitosamente"
        }
    }

    // === 1. BARRA INTELIGENTE MULTIMODO (GEMINI NLP) ===
    fun procesarTextoInteligente(textoUsuario: String) {
        viewModelScope.launch {
            _ocupado.value = true
            try {
                val cuentasList = dao.todasCuentasLista().map { it.nombre }.joinToString(", ")
                val prompt = """
                    Eres un asistente financiero ultra inteligente para Perú.
                    Analiza la siguiente frase del usuario: "$textoUsuario"
                    
                    El usuario puede ingresar uno o varios movimientos a la vez, que pueden ser GASTOS, INGRESOS o TRANSFERENCIAS.
                    Ejemplos de frases posibles:
                    - "Cena 15 soles con Yape y pasaje 3 en efectivo" (gastos)
                    - "Me pagaron mi sueldo 2500 soles en BCP" (ingreso: esIngreso = true)
                    - "Cobré 80 soles de una venta en efectivo" (ingreso: esIngreso = true)
                    - "Me yapearon 50 soles por el almuerzo" (ingreso: esIngreso = true)
                    - "Pasé 100 de BCP a mi ahorro" (transferencia: esTransferencia = true)
                    
                    Cuentas disponibles del usuario: [$cuentasList].
                    Categorías disponibles: [Sueldo/Ingresos, Comida, Transporte, Hogar, Servicios, Salud, Ocio, Educación, Ropa, Ahorro/Inversión, Otros].
                    
                    Devuelve ESTRICTAMENTE un JSON Array con cada movimiento detectado con esta estructura:
                    [
                      {
                        "monto": 15.0,
                        "categoria": "Comida",
                        "nota": "cena",
                        "esIngreso": false,
                        "esTransferencia": false,
                        "cuentaNombre": "Yape",
                        "cuentaDestinoNombre": null,
                        "diasAtras": 0,
                        "montoPorCobrar": 0.0,
                        "deudor": ""
                      }
                    ]
                    Reglas estrictas:
                    - ¡MUY IMPORTANTE!: Si el usuario dice "me pagaron", "sueldo", "ingreso", "cobré", "gané", "recibí", "me transfirieron", "me yapearon", o cualquier entrada de dinero, DEBES poner OBLIGATORIAMENTE "esIngreso": true. Bajo NINGUNA circunstancia pongas false si es dinero que entra.
                    - Si es un gasto ("gasté", "compré", "pagué", "salida", "almuerzo"), pon "esIngreso": false.
                    - Si menciona "ayer", diasAtras = 1. Si menciona "hace 2 días", diasAtras = 2. Si es hoy, diasAtras = 0.
                    - Si menciona que pasó dinero a otra cuenta o ahorro ("pasé 100 a ahorro"), esTransferencia = true, asigna cuentaNombre (origen) y cuentaDestinoNombre (destino).
                    - Si menciona préstamo o dividir ("pagué 60 pero me debe Juan 20"), llena montoPorCobrar y deudor.
                    - Devuelve SOLO el JSON array, sin markdown ni explicaciones.
                """.trimIndent()

                val res = llamarGeminiTexto(prompt)
                val jsonArray = JSONArray(res.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim())
                val lista = mutableListOf<MovimientoDetectado>()

                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val diasAtras = obj.optInt("diasAtras", 0)
                    val fecha = System.currentTimeMillis() - (diasAtras * 86400000L)

                    lista.add(
                        MovimientoDetectado(
                            monto = obj.getDouble("monto"),
                            categoria = obj.optString("categoria", "Otros"),
                            nota = obj.optString("nota", ""),
                            esIngreso = obj.optBoolean("esIngreso", false),
                            esTransferencia = obj.optBoolean("esTransferencia", false),
                            cuentaNombre = obj.optString("cuentaNombre", "BCP / Yape"),
                            cuentaDestinoNombre = if (obj.has("cuentaDestinoNombre") && !obj.isNull("cuentaDestinoNombre")) obj.getString("cuentaDestinoNombre") else null,
                            fecha = fecha,
                            montoPorCobrar = obj.optDouble("montoPorCobrar", 0.0),
                            deudor = obj.optString("deudor", "")
                        )
                    )
                }

                if (lista.isNotEmpty()) {
                    _movimientosDetectados.value = lista
                } else {
                    _mensaje.value = "No se detectaron montos válidos en la frase."
                }
            } catch (e: Exception) {
                val localParsed = parsearTextoLocalmente(textoUsuario)
                if (localParsed.isNotEmpty()) {
                    _movimientosDetectados.value = localParsed
                    _mensaje.value = "Movimientos detectados (Modo Local / Desconectado)"
                } else {
                    _mensaje.value = "Error al procesar: ${e.localizedMessage ?: e.message}"
                }
            } finally {
                _ocupado.value = false
            }
        }
    }

    private fun parsearTextoLocalmente(texto: String): List<MovimientoDetectado> {
        val regex = Regex("""([a-zA-ZáéíóúÁÉÍÓÚñÑ\s]+)?\s*(\d+(?:\.\d+)?)\s*(soles|sol|s/)?\s*(con\s+[a-zA-Z\s]+)?""", RegexOption.IGNORE_CASE)
        val matches = regex.findAll(texto).toList()
        if (matches.isEmpty()) return emptyList()

        val resultados = mutableListOf<MovimientoDetectado>()
        for (m in matches) {
            val montoStr = m.groupValues.getOrNull(2) ?: continue
            val monto = montoStr.toDoubleOrNull() ?: continue
            val notaRaw = m.groupValues.getOrNull(1)?.trim() ?: "Gasto"
            val cuentaRaw = m.groupValues.getOrNull(4)?.trim() ?: ""

            val esYape = texto.contains("yape", ignoreCase = true) || cuentaRaw.contains("yape", ignoreCase = true)
            val cuentaNombre = if (esYape) "BCP / Yape" else "Efectivo / Billetera"

            var cat = "Otros"
            val lower = (notaRaw + " " + texto).lowercase()
            when {
                lower.contains("cena") || lower.contains("almuerzo") || lower.contains("menu") || lower.contains("comida") || lower.contains("café") -> cat = "Comida"
                lower.contains("pasaje") || lower.contains("taxi") || lower.contains("bus") || lower.contains("combis") -> cat = "Transporte"
                lower.contains("cine") || lower.contains("juego") || lower.contains("ocio") || lower.contains("cerveza") -> cat = "Ocio"
                lower.contains("luz") || lower.contains("agua") || lower.contains("internet") || lower.contains("gas") -> cat = "Servicios"
            }

            resultados.add(
                MovimientoDetectado(
                    monto = monto,
                    categoria = cat,
                    nota = if (notaRaw.isNotBlank()) notaRaw else "Gasto rápido",
                    esIngreso = lower.contains("cobro") || lower.contains("ingreso") || lower.contains("gane"),
                    cuentaNombre = cuentaNombre
                )
            )
        }
        return resultados
    }

    // === 2. LECTOR DE VOUCHERS POR IMAGEN (SHARE TARGET / SELECCIÓN) ===
    fun procesarVoucherImagen(uri: Uri) {
        viewModelScope.launch {
            _ocupado.value = true
            try {
                val base64Image = withContext(Dispatchers.IO) {
                    val inputStream = getApplication<Application>().contentResolver.openInputStream(uri)
                    val bitmap = BitmapFactory.decodeStream(inputStream)
                    inputStream?.close()

                    val scaled = if (bitmap.width > 1200 || bitmap.height > 1200) {
                        val ratio = 1200f / Math.max(bitmap.width, bitmap.height)
                        Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).toInt(), (bitmap.height * ratio).toInt(), true)
                    } else bitmap

                    val bos = ByteArrayOutputStream()
                    scaled.compress(Bitmap.CompressFormat.JPEG, 85, bos)
                    Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
                }

                val prompt = """
                    Analiza esta imagen que corresponde a un voucher de pago o transferencia de billetera móvil o banco (ej. Yape, Plin, BCP, BBVA, Interbank).
                    Extrae con máxima precisión en formato JSON estricto:
                    {
                      "monto": 25.50,
                      "destinatario": "Juan Perez o Nombre Comercio",
                      "codigoOperacion": "12345678",
                      "categoriaSugerida": "Comida",
                      "billetera": "Yape"
                    }
                    Devuelve SOLO el JSON sin markdown ni texto extra.
                """.trimIndent()

                val respuesta = llamarGeminiVision(prompt, base64Image)
                val json = JSONObject(respuesta.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim())

                val monto = json.optDouble("monto", 0.0)
                val dest = json.optString("destinatario", "")
                val codigo = json.optString("codigoOperacion", "")
                val cat = json.optString("categoriaSugerida", "Otros")
                val billetera = json.optString("billetera", "BCP / Yape")

                _movimientosDetectados.value = listOf(
                    MovimientoDetectado(
                        monto = monto,
                        categoria = cat,
                        nota = if (dest.isNotBlank()) "Pago a $dest" else "Voucher",
                        esIngreso = false,
                        cuentaNombre = billetera,
                        codigoOperacion = codigo,
                        fecha = System.currentTimeMillis()
                    )
                )
            } catch (e: Exception) {
                _mensaje.value = "Error al leer voucher: ${e.localizedMessage ?: e.message}"
            } finally {
                _ocupado.value = false
            }
        }
    }

    // === 3. SIMULADOR DE COMPRAS ("¿ME ALCANZA?") ===
    fun simularCompra(consultaUsuario: String, montoOpcional: Double? = null) {
        viewModelScope.launch {
            _ocupado.value = true
            _simuladorResultado.value = null
            try {
                val disponible = saldoDisponibleTotal.value
                val fijosPend = pagosFijosPendientesMes.value
                val metaAhorro = metaAhorroMes.value
                val libreDiario = saldoLibrePorDia.value

                val cal = Calendar.getInstance()
                val diasRestantes = (cal.getActualMaximum(Calendar.DAY_OF_MONTH) - cal.get(Calendar.DAY_OF_MONTH) + 1).coerceAtLeast(1)

                val prompt = """
                    Eres un analista financiero personal implacable y empático.
                    El usuario pregunta: "$consultaUsuario"
                    ${if (montoOpcional != null) "Monto detectado o ingresado: S/ $montoOpcional" else ""}
                    
                    Contexto financiero real del usuario:
                    - Saldo Disponible en cuentas: S/ $disponible
                    - Gastos fijos pendientes por pagar este mes: S/ $fijosPend
                    - Meta de ahorro mensual comprometida: S/ $metaAhorro
                    - Días restantes del mes: $diasRestantes días
                    - Su saldo libre diario actual: S/ $libreDiario / día
                    
                    Calcula cómo afectaría esta compra a su saldo libre por día.
                    Devuelve ESTRICTAMENTE un JSON con:
                    {
                      "veredicto": "VERDE" (si le sobra cómodo) | "AMARILLO" (si queda ajustado) | "ROJO" (si toca dinero de recibos o ahorro),
                      "titulo": "Luz verde: Puedes comprarlo" o advertencia clara,
                      "explicacion": "Explicación breve de 2 líneas sobre cómo impacta tus números.",
                      "impactoDiario": "Tu saldo libre pasaría de S/ X a S/ Y por día."
                    }
                """.trimIndent()

                val respuesta = llamarGeminiTexto(prompt, formatoJson = true)
                val json = JSONObject(respuesta.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim())

                _simuladorResultado.value = SimuladorResultado(
                    veredicto = json.optString("veredicto", "AMARILLO"),
                    titulo = json.optString("titulo", "Resultado de simulación"),
                    explicacion = json.optString("explicacion", ""),
                    impactoDiario = json.optString("impactoDiario", "")
                )
            } catch (e: Exception) {
                _mensaje.value = "Error al simular: ${e.localizedMessage ?: e.message}"
            } finally {
                _ocupado.value = false
            }
        }
    }

    fun cerrarSimulador() {
        _simuladorResultado.value = null
    }

    // === 4. DETECCIÓN DE GASTOS HORMIGA Y ANOMALÍAS ===
    fun analizarGastosHormigaYAnomalias() {
        viewModelScope.launch {
            _ocupado.value = true
            try {
                val todosGastos = dao.todosLista().filter { !it.esTransferencia && !it.esIngreso }
                val hace14Dias = System.currentTimeMillis() - (14 * 86400000L)
                val hace7Dias = System.currentTimeMillis() - (7 * 86400000L)

                val ultimos14 = todosGastos.filter { it.fecha >= hace14Dias }
                val semanaActual = ultimos14.filter { it.fecha >= hace7Dias }
                val semanaAnterior = ultimos14.filter { it.fecha < hace7Dias }

                val microGastosSemana = semanaActual.filter { it.montoRealUsuario <= 15.0 }.sumOf { it.montoRealUsuario }

                val porCategoriaSemActual = semanaActual.groupBy { it.categoria }.mapValues { it.value.sumOf { g -> g.montoRealUsuario } }
                val porCategoriaSemAnterior = semanaAnterior.groupBy { it.categoria }.mapValues { it.value.sumOf { g -> g.montoRealUsuario } }

                val resumenDatos = JSONObject().apply {
                    put("microGastosMenoresA15SolesSemanaActual", microGastosSemana)
                    put("gastosSemanaActual", JSONObject(porCategoriaSemActual))
                    put("gastosSemanaAnterior", JSONObject(porCategoriaSemAnterior))
                }.toString()

                val prompt = """
                    Analiza estos datos de gastos de las últimas dos semanas en Perú:
                    $resumenDatos
                    
                    Detecta gastos hormiga (micro-compras acumuladas) y anomalías (categorías que se dispararon repentinamente).
                    Devuelve ESTRICTAMENTE un JSON con:
                    {
                      "titulo": "Alerta de Gastos Hormiga" o "Ritmo de Gasto Saludable",
                      "resumen": "Resumen conciso de 2 líneas sobre qué categoría o microgastos están drenando el dinero.",
                      "gastosHormigaMonto": $microGastosSemana,
                      "recomendaciones": ["Consejo 1", "Consejo 2"]
                    }
                """.trimIndent()

                val res = llamarGeminiTexto(prompt, formatoJson = true)
                val json = JSONObject(res.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim())

                val recArray = json.optJSONArray("recomendaciones") ?: JSONArray()
                val recs = mutableListOf<String>()
                for (i in 0 until recArray.length()) recs.add(recArray.getString(i))

                _diagnosticoAnomalias.value = DiagnosticoAnomalias(
                    titulo = json.optString("titulo", "Diagnóstico Financiero"),
                    resumen = json.optString("resumen", ""),
                    gastosHormigaMonto = json.optDouble("gastosHormigaMonto", microGastosSemana),
                    recomendaciones = recs
                )
            } catch (e: Exception) {
                _mensaje.value = "Error al analizar anomalías: ${e.localizedMessage ?: e.message}"
            } finally {
                _ocupado.value = false
            }
        }
    }

    // === EXPORTACIÓN A CSV ===
    fun exportarHistorialCSV(onUriReady: (Intent) -> Unit) {
        viewModelScope.launch {
            _ocupado.value = true
            try {
                val context = getApplication<Application>()
                val lista = dao.todosLista()
                val cuentasMap = dao.todasCuentasLista().associateBy({ it.id }, { it.nombre })

                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
                val sb = StringBuilder()
                sb.append("ID,Fecha,Tipo,Cuenta,Categoria,Nota,Monto,Por Cobrar,Deudor,Codigo Operacion\n")

                for (g in lista) {
                    val tipo = if (g.esTransferencia) "TRANSFERENCIA" else if (g.esIngreso) "INGRESO" else "GASTO"
                    val cuentaNom = cuentasMap[g.cuentaId] ?: "Cuenta"
                    val notaEsc = "\"${g.nota.replace("\"", "\"\"")}\""
                    val deudorEsc = "\"${g.deudor.replace("\"", "\"\"")}\""
                    sb.append("${g.id},${sdf.format(Date(g.fecha))},$tipo,\"$cuentaNom\",\"${g.categoria}\",$notaEsc,${g.monto},${g.montoPorCobrar},$deudorEsc,\"${g.codigoOperacion}\"\n")
                }

                val exportDir = File(context.cacheDir, "exports")
                if (!exportDir.exists()) exportDir.mkdirs()

                val fileName = "MisGastos_${SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())}.csv"
                val file = File(exportDir, fileName)
                file.writeText(sb.toString())

                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/csv"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "Exportación de Gastos - $fileName")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                onUriReady(Intent.createChooser(shareIntent, "Compartir o Abrir con Google Sheets/Excel"))
            } catch (e: Exception) {
                _mensaje.value = "Error al exportar CSV: ${e.localizedMessage ?: e.message}"
            } finally {
                _ocupado.value = false
            }
        }
    }

    // === LLAMADAS HTTP GEMINI ===
    private fun buildGeminiConnection(modelEndpoint: String): HttpURLConnection {
        // Las nuevas keys AQ. de Google AI Studio usan Bearer token; las AIzaSy usan ?key=
        return if (geminiApiKey.startsWith("AIza")) {
            val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$modelEndpoint?key=$geminiApiKey")
            (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
                connectTimeout = 20000
                readTimeout = 20000
            }
        } else {
            val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$modelEndpoint")
            (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $geminiApiKey")
                setRequestProperty("x-goog-api-key", geminiApiKey)
                doOutput = true
                connectTimeout = 20000
                readTimeout = 20000
            }
        }
    }

    private suspend fun llamarGeminiTexto(prompt: String, formatoJson: Boolean = false): String = withContext(Dispatchers.IO) {
        val conn = buildGeminiConnection("gemini-1.5-flash:generateContent").also {
            it.connectTimeout = 20000
            it.readTimeout = 20000
        }

        val jsonPayload = JSONObject().apply {
            put("contents", JSONArray().put(
                JSONObject().apply {
                    put("parts", JSONArray().put(JSONObject().put("text", prompt)))
                }
            ))
            if (formatoJson) {
                put("generationConfig", JSONObject().apply {
                    put("response_mime_type", "application/json")
                })
            }
        }.toString()

        OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(jsonPayload) }

        val code = conn.responseCode
        if (code in 200..299) {
            val responseString = conn.inputStream.bufferedReader().use { it.readText() }
            val responseJson = JSONObject(responseString)
            val candidates = responseJson.optJSONArray("candidates")
            val content = candidates?.optJSONObject(0)?.optJSONObject("content")
            val parts = content?.optJSONArray("parts")
            parts?.optJSONObject(0)?.optString("text", "") ?: ""
        } else {
            val rawErr = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP $code"
            android.util.Log.e("GEMINI_ERROR", "HTTP $code — $rawErr")
            val cleanMsg = try {
                JSONObject(rawErr).optJSONObject("error")?.optString("message", rawErr) ?: rawErr
            } catch (_: Exception) {
                rawErr
            }
            throw Exception("Error $code: $cleanMsg")
        }
    }

    private suspend fun llamarGeminiVision(prompt: String, base64Jpeg: String): String = withContext(Dispatchers.IO) {
        val conn = buildGeminiConnection("gemini-1.5-flash:generateContent").also {
            it.connectTimeout = 30000
            it.readTimeout = 30000
        }

        val jsonPayload = JSONObject().apply {
            put("contents", JSONArray().put(
                JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().put("text", prompt))
                        put(JSONObject().put("inline_data", JSONObject().apply {
                            put("mime_type", "image/jpeg")
                            put("data", base64Jpeg)
                        }))
                    })
                }
            ))
            put("generationConfig", JSONObject().apply {
                put("response_mime_type", "application/json")
            })
        }.toString()

        OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(jsonPayload) }

        val code = conn.responseCode
        if (code in 200..299) {
            val responseString = conn.inputStream.bufferedReader().use { it.readText() }
            val responseJson = JSONObject(responseString)
            val candidates = responseJson.optJSONArray("candidates")
            val content = candidates?.optJSONObject(0)?.optJSONObject("content")
            val parts = content?.optJSONArray("parts")
            parts?.optJSONObject(0)?.optString("text", "") ?: ""
        } else {
            val rawErr = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP $code"
            val cleanMsg = try {
                JSONObject(rawErr).optJSONObject("error")?.optString("message", rawErr) ?: rawErr
            } catch (_: Exception) {
                rawErr
            }
            throw Exception(cleanMsg)
        }
    }

    // === GESTIÓN DE ACTUALIZACIONES IN-APP ===
    fun cerrarModalActualizacion() {
        _estadoActualizacion.value = EstadoActualizacion.Inactivo
    }

    fun buscarActualizaciones(
        versionCodeActual: Long,
        versionNameActual: String = "1.0",
        esManual: Boolean = true
    ) {
        viewModelScope.launch {
            _estadoActualizacion.value = EstadoActualizacion.Comprobando
            val info = FirestoreRepository.verificarActualizacion(versionCodeActual, versionNameActual)
            if (info.hayActualizacion) {
                _estadoActualizacion.value = EstadoActualizacion.Disponible(info)
            } else if (info.error != null) {
                _estadoActualizacion.value = EstadoActualizacion.Error("Error al consultar actualizaciones: ${info.error}")
            } else {
                _estadoActualizacion.value = if (esManual) EstadoActualizacion.AlDia(info) else EstadoActualizacion.Inactivo
            }
        }
    }

    fun descargarEInstalarApk(context: Context, info: InfoActualizacion) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _estadoActualizacion.value = EstadoActualizacion.Descargando(0f, info)
                val updatesDir = File(context.cacheDir, "updates")
                if (!updatesDir.exists()) updatesDir.mkdirs()
                val apkFile = File(updatesDir, "MisGastos_update.apk")
                if (apkFile.exists()) apkFile.delete()

                val url = URL(info.apkUrl)
                var currentConnection = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 30000
                    instanceFollowRedirects = true
                    connect()
                }

                // Manejo de redirecciones HTTP (301, 302, 303, 307, 308)
                var redirectCount = 0
                while (currentConnection.responseCode in listOf(301, 302, 303, 307, 308) && redirectCount < 5) {
                    val newUrl = currentConnection.getHeaderField("Location") ?: break
                    currentConnection.disconnect()
                    currentConnection = (URL(newUrl).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 15000
                        readTimeout = 30000
                        instanceFollowRedirects = true
                        connect()
                    }
                    redirectCount++
                }

                val contentLength = currentConnection.contentLengthLong
                var bytesDownloaded = 0L

                currentConnection.inputStream.use { input ->
                    apkFile.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            bytesDownloaded += bytesRead
                            if (contentLength > 0) {
                                val progress = (bytesDownloaded.toFloat() / contentLength.toFloat()).coerceIn(0f, 1f)
                                _estadoActualizacion.value = EstadoActualizacion.Descargando(progress, info)
                            }
                        }
                    }
                }
                currentConnection.disconnect()

                _estadoActualizacion.value = EstadoActualizacion.ListoParaInstalar(apkFile, info)
                withContext(Dispatchers.Main) {
                    ejecutarInstalacion(context, apkFile)
                }
            } catch (e: Exception) {
                _estadoActualizacion.value = EstadoActualizacion.Error("Error al descargar: ${e.message}")
            }
        }
    }

    fun ejecutarInstalacion(context: Context, apkFile: File) {
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            _mensaje.value = "Error al abrir instalador: ${e.message}"
        }
    }
}
