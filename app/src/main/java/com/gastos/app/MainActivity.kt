package com.gastos.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import com.gastos.app.data.Cuenta
import com.gastos.app.data.Gasto
import com.gastos.app.data.PagoFijo
import com.gastos.app.ui.BiometricHelper
import com.gastos.app.ui.BotonRapido
import com.gastos.app.ui.GastosViewModel
import com.gastos.app.ui.IngresoProgramado
import com.gastos.app.ui.DeudaPrestamo
import com.gastos.app.ui.EstadoActualizacion
import com.gastos.app.ui.MensajeChat
import com.gastos.app.ui.MovimientoDetectado
import com.gastos.app.ui.theme.MisGastosTheme
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.common.api.ApiException
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.*

// Rutas de Navegación NavHost
sealed class AppScreen {
    object Splash : AppScreen()
    object Login : AppScreen()
    object BiometricLock : AppScreen()
    object SetupAccounts : AppScreen()
    object SetupRecurring : AppScreen()
    object SetupGoals : AppScreen()
    object Main : AppScreen()
    object History : AppScreen()
    object Settings : AppScreen()
}

class MainActivity : FragmentActivity() {
    private val viewModel: GastosViewModel by viewModels()
    private val accionWidget = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        procesarIntentCompartir(intent)
        procesarIntentWidget(intent)

        setContent {
            val modoTema by viewModel.modoTema.collectAsState()
            val esOscuro = when (modoTema) {
                "claro" -> false
                "oscuro" -> true
                else -> androidx.compose.foundation.isSystemInDarkTheme()
            }

            MisGastosTheme(darkTheme = esOscuro) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    val mensaje by viewModel.mensaje.collectAsState()
                    LaunchedEffect(mensaje) {
                        mensaje?.let {
                            Toast.makeText(this@MainActivity, it, Toast.LENGTH_SHORT).show()
                            viewModel.limpiarMensaje()
                        }
                    }

                    AppNavHost(
                        viewModel = viewModel,
                        activity = this@MainActivity,
                        accionWidget = accionWidget.value,
                        onConsumirAccionWidget = { accionWidget.value = null },
                        onExportar = { startActivity(it) }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        procesarIntentCompartir(intent)
        procesarIntentWidget(intent)
    }

    private fun procesarIntentWidget(intent: Intent?) {
        val action = intent?.action
        if (action == com.gastos.app.widget.AccesoRapidoWidgetProvider.ACTION_NUEVO_CONSUMO ||
            action == com.gastos.app.widget.AccesoRapidoWidgetProvider.ACTION_VOZ) {
            accionWidget.value = action
        }
    }

    private fun procesarIntentCompartir(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type?.startsWith("image/") == true) {
            val imageUri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            if (imageUri != null) {
                viewModel.procesarVoucherImagen(imageUri)
            }
        }
    }
}

// =========================================================================
// ROUTER PRINCIPAL DE LA APP (NAVHOST COMPACTO CON ESTADOS DE RUTA)
// =========================================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNavHost(
    viewModel: GastosViewModel,
    activity: FragmentActivity,
    accionWidget: String? = null,
    onConsumirAccionWidget: () -> Unit = {},
    onExportar: (Intent) -> Unit
) {
    var currentScreen by remember { mutableStateOf<AppScreen>(AppScreen.Splash) }
    val format = remember { NumberFormat.getCurrencyInstance(Locale("es", "PE")) }

    // Estados de Onboarding temporal
    var tempEfectivo by remember { mutableStateOf("100") }
    var tempBcp by remember { mutableStateOf("500") }
    var tempAhorro by remember { mutableStateOf("300") }
    var tempInversion by remember { mutableStateOf("0") }
    var tempDiaCorte by remember { mutableStateOf(1) }
    var tempMetaAhorro by remember { mutableStateOf("250") }

    // Hojas Emergentes (Modal Bottom Sheets)
    val movimientosDetectados by viewModel.movimientosDetectados.collectAsState()
    var showManualEntrySheet by remember { mutableStateOf(false) }

    val voiceLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val spokenText = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!spokenText.isNullOrBlank()) {
                viewModel.procesarTextoInteligente(spokenText)
            }
        }
    }

    LaunchedEffect(currentScreen, accionWidget) {
        if (currentScreen is AppScreen.Main && accionWidget != null) {
            when (accionWidget) {
                com.gastos.app.widget.AccesoRapidoWidgetProvider.ACTION_NUEVO_CONSUMO -> {
                    showManualEntrySheet = true
                    onConsumirAccionWidget()
                }
                com.gastos.app.widget.AccesoRapidoWidgetProvider.ACTION_VOZ -> {
                    val intentVoz = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-PE")
                    }
                    try {
                        voiceLauncher.launch(intentVoz)
                    } catch (_: Exception) {}
                    onConsumirAccionWidget()
                }
            }
        }
    }

    when (val screen = currentScreen) {
        // === ETAPA 1: ACCESO Y SEGURIDAD ===
        is AppScreen.Splash -> {
            SplashScreen(
                viewModel = viewModel,
                onNavigate = { currentScreen = it }
            )
        }
        is AppScreen.Login -> {
            LoginScreen(
                viewModel = viewModel,
                onLoginSuccess = {
                    if (viewModel.onboardingCompleted.value) {
                        currentScreen = AppScreen.Main
                    } else {
                        currentScreen = AppScreen.SetupAccounts
                    }
                }
            )
        }
        is AppScreen.BiometricLock -> {
            BiometricLockScreen(
                activity = activity,
                onUnlockSuccess = {
                    viewModel.desbloquearBiometria()
                    currentScreen = AppScreen.Main
                }
            )
        }

        // === ETAPA 2: CONFIGURACIÓN INICIAL (ONBOARDING) ===
        is AppScreen.SetupAccounts -> {
            SetupAccountsScreen(
                efectivo = tempEfectivo,
                onEfectivoChange = { tempEfectivo = it },
                bcp = tempBcp,
                onBcpChange = { tempBcp = it },
                ahorro = tempAhorro,
                onAhorroChange = { tempAhorro = it },
                inversion = tempInversion,
                onInversionChange = { tempInversion = it },
                onSiguiente = { currentScreen = AppScreen.SetupRecurring }
            )
        }
        is AppScreen.SetupRecurring -> {
            SetupRecurringScreen(
                diaCorte = tempDiaCorte,
                onDiaCorteChange = { tempDiaCorte = it },
                viewModel = viewModel,
                onSiguiente = { currentScreen = AppScreen.SetupGoals },
                onVolver = { currentScreen = AppScreen.SetupAccounts }
            )
        }
        is AppScreen.SetupGoals -> {
            SetupGoalsScreen(
                metaAhorro = tempMetaAhorro,
                onMetaAhorroChange = { tempMetaAhorro = it },
                onComenzar = {
                    viewModel.completarOnboardingConDatos(
                        saldoEfectivo = tempEfectivo.toDoubleOrNull() ?: 0.0,
                        saldoBcp = tempBcp.toDoubleOrNull() ?: 0.0,
                        saldoAhorro = tempAhorro.toDoubleOrNull() ?: 0.0,
                        saldoInversion = tempInversion.toDoubleOrNull() ?: 0.0,
                        diaCorte = tempDiaCorte,
                        metaAhorro = tempMetaAhorro.toDoubleOrNull() ?: 0.0
                    )
                    currentScreen = AppScreen.Main
                },
                onVolver = { currentScreen = AppScreen.SetupRecurring }
            )
        }

        // === ETAPA 3: PANTALLA PRINCIPAL CON LAS 4 PESTAÑAS ===
        is AppScreen.Main -> {
            MainTabsScreen(
                viewModel = viewModel,
                format = format,
                onOpenManualEntry = { showManualEntrySheet = true },
                onOpenHistory = { currentScreen = AppScreen.History },
                onOpenSettings = { currentScreen = AppScreen.Settings }
            )
        }

        // === ETAPA 4: PANTALLAS SECUNDARIAS ===
        is AppScreen.History -> {
            HistoryScreen(
                viewModel = viewModel,
                format = format,
                onBack = { currentScreen = AppScreen.Main }
            )
        }
        is AppScreen.Settings -> {
            SettingsScreen(
                viewModel = viewModel,
                onExportar = { viewModel.exportarHistorialCSV(onExportar) },
                onBack = { currentScreen = AppScreen.Main },
                onCerrarSesion = { currentScreen = AppScreen.Login }
            )
        }
    }

    // === ETAPA 4: HOJAS EMERGENTES (MODAL BOTTOM SHEETS) ===
    // 1. Hoja de Confirmación de IA y Vouchers
    if (movimientosDetectados != null) {
        val cuentas by viewModel.cuentas.collectAsState()
        AiConfirmationSheet(
            movimientos = movimientosDetectados!!,
            cuentas = cuentas,
            onConfirmar = { viewModel.confirmarMovimientosDetectados(it) },
            onDismiss = { viewModel.descartarMovimientosDetectados() }
        )
    }

    // 2. Hoja de Teclado Manual Rápido (Calculadora)
    if (showManualEntrySheet) {
        val cuentas by viewModel.cuentas.collectAsState()
        ManualEntrySheet(
            cuentas = cuentas,
            onGuardar = { monto, cat, nota, esIngreso, cuentaId, porCobrar, deudor ->
                viewModel.agregar(
                    monto = monto,
                    categoria = cat,
                    nota = nota,
                    fecha = System.currentTimeMillis(),
                    esIngreso = esIngreso,
                    cuentaId = cuentaId,
                    montoPorCobrar = porCobrar,
                    deudor = deudor
                )
                showManualEntrySheet = false
            },
            onDismiss = { showManualEntrySheet = false }
        )
    }
}

// =========================================================================
// ETAPA 1: ACCESO Y SEGURIDAD
// =========================================================================

@Composable
fun SplashScreen(
    viewModel: GastosViewModel,
    onNavigate: (AppScreen) -> Unit
) {
    val conectado by viewModel.conectado.collectAsState()
    val onboardingCompleted by viewModel.onboardingCompleted.collectAsState()
    val biometriaHabilitada by viewModel.biometriaHabilitada.collectAsState()

    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(800)
        if (!conectado) {
            onNavigate(AppScreen.Login)
        } else if (!onboardingCompleted) {
            onNavigate(AppScreen.SetupAccounts)
        } else if (biometriaHabilitada) {
            onNavigate(AppScreen.BiometricLock)
        } else {
            onNavigate(AppScreen.Main)
        }
    }

    Box(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.AccountBalanceWallet, contentDescription = null, modifier = Modifier.size(90.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(16.dp))
            Text("Mis Gastos AI", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(8.dp))
            CircularProgressIndicator(modifier = Modifier.size(28.dp), color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
fun LoginScreen(
    viewModel: GastosViewModel,
    onLoginSuccess: () -> Unit
) {
    val context = LocalContext.current
    var cargando by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var exitoMsg by remember { mutableStateOf<String?>(null) }

    // 0: Correo/Contraseña, 1: Teléfono
    var modoAuth by remember { mutableStateOf(0) }
    // En modo Correo -> true: Iniciar sesión, false: Registrarse
    var esIniciarSesion by remember { mutableStateOf(true) }
    var mostrarRecuperarDialog by remember { mutableStateOf(false) }

    // Campos Correo / Registro
    var nombreInput by remember { mutableStateOf("") }
    var apellidoInput by remember { mutableStateOf("") }
    var sexoInput by remember { mutableStateOf("Prefiero no decir") }
    var edadInput by remember { mutableStateOf("") }
    var profesionInput by remember { mutableStateOf("") }
    var estadoCivilInput by remember { mutableStateOf("") }

    var correoInput by remember { mutableStateOf("") }
    var contrasenaInput by remember { mutableStateOf("") }
    var contrasenaVisible by remember { mutableStateOf(false) }

    // Campos Teléfono / SMS
    var telefonoInput by remember { mutableStateOf("") }
    var codigoSmsInput by remember { mutableStateOf("") }
    var verificationIdStored by remember { mutableStateOf<String?>(null) }
    var resendTokenStored by remember { mutableStateOf<com.google.firebase.auth.PhoneAuthProvider.ForceResendingToken?>(null) }
    var esperandoCodigoSms by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        item {
            Icon(Icons.Default.AccountBalanceWallet, contentDescription = null, modifier = Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(12.dp))
            Text("Mis Gastos AI", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(
                text = if (modoAuth == 0) (if (esIniciarSesion) "Inicia sesión con tu correo" else "Crea tu nueva cuenta") else "Accede con tu número celular",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(20.dp))

            // Selector Correo vs Teléfono
            TabRow(selectedTabIndex = modoAuth) {
                Tab(selected = modoAuth == 0, onClick = { modoAuth = 0; errorMsg = null; exitoMsg = null }, text = { Text("Correo") })
                Tab(selected = modoAuth == 1, onClick = { modoAuth = 1; errorMsg = null; exitoMsg = null }, text = { Text("Teléfono") })
            }
            Spacer(modifier = Modifier.height(20.dp))
        }

        if (modoAuth == 0) {
            // ================= MODO CORREO / CONTRASEÑA =================
            if (!esIniciarSesion) {
                item {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = nombreInput,
                            onValueChange = { nombreInput = it },
                            label = { Text("Nombre") },
                            leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = apellidoInput,
                            onValueChange = { apellidoInput = it },
                            label = { Text("Apellido") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))

                    // Sexo y Edad
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = edadInput,
                            onValueChange = { edadInput = it.filter { c -> c.isDigit() }.take(3) },
                            label = { Text("Edad") },
                            placeholder = { Text("Ej: 25") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(0.8f)
                        )

                        // Selector Sexo Chips
                        Column(modifier = Modifier.weight(1.2f)) {
                            Text("Sexo:", style = MaterialTheme.typography.labelSmall)
                            Row(modifier = Modifier.horizontalScroll(rememberScrollState()).padding(top = 2.dp)) {
                                listOf("Masculino", "Femenino", "Otro").forEach { s ->
                                    FilterChip(
                                        selected = sexoInput == s,
                                        onClick = { sexoInput = s },
                                        label = { Text(s, style = MaterialTheme.typography.labelSmall) },
                                        modifier = Modifier.padding(end = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))

                    // Campos opcionales: Profesión y Estado Civil
                    OutlinedTextField(
                        value = profesionInput,
                        onValueChange = { profesionInput = it },
                        label = { Text("Profesión / Ocupación (opcional)") },
                        placeholder = { Text("Ej: Ingeniero, Comerciante, Estudiante") },
                        leadingIcon = { Icon(Icons.Default.Work, contentDescription = null) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    // Estado civil chips opcionales
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text("Estado civil (opcional):", style = MaterialTheme.typography.labelSmall)
                        Row(modifier = Modifier.horizontalScroll(rememberScrollState()).padding(top = 2.dp)) {
                            listOf("Soltero(a)", "Casado(a)", "Conviviente", "Divorciado(a)").forEach { est ->
                                FilterChip(
                                    selected = estadoCivilInput == est,
                                    onClick = { estadoCivilInput = if (estadoCivilInput == est) "" else est },
                                    label = { Text(est, style = MaterialTheme.typography.labelSmall) },
                                    modifier = Modifier.padding(end = 4.dp)
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                }
            }

            item {
                OutlinedTextField(
                    value = correoInput,
                    onValueChange = { correoInput = it },
                    label = { Text("Correo electrónico") },
                    leadingIcon = { Icon(Icons.Default.Email, contentDescription = null) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = contrasenaInput,
                    onValueChange = { contrasenaInput = it },
                    label = { Text("Contraseña") },
                    leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                    trailingIcon = {
                        IconButton(onClick = { contrasenaVisible = !contrasenaVisible }) {
                            Icon(
                                if (contrasenaVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = "Alternar contraseña"
                            )
                        }
                    },
                    visualTransformation = if (contrasenaVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                if (esIniciarSesion) {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                        TextButton(onClick = { mostrarRecuperarDialog = true }) {
                            Text("¿Olvidaste tu contraseña?", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                } else {
                    Spacer(modifier = Modifier.height(12.dp))
                }

                Button(
                    onClick = {
                        if (correoInput.isBlank() || contrasenaInput.isBlank()) {
                            errorMsg = "Por favor completa todos los campos"
                            return@Button
                        }
                        cargando = true
                        errorMsg = null
                        exitoMsg = null
                        if (esIniciarSesion) {
                            viewModel.iniciarSesionCorreo(
                                correo = correoInput,
                                contrasena = contrasenaInput,
                                onExito = {
                                    cargando = false
                                    onLoginSuccess()
                                },
                                onError = { msg ->
                                    cargando = false
                                    errorMsg = msg
                                }
                            )
                        } else {
                            if (nombreInput.isBlank()) {
                                cargando = false
                                errorMsg = "Por favor ingresa tu nombre"
                                return@Button
                            }
                            if (contrasenaInput.length < 6) {
                                cargando = false
                                errorMsg = "La contraseña debe tener al menos 6 caracteres"
                                return@Button
                            }
                            viewModel.registrarseCorreo(
                                correo = correoInput,
                                contrasena = contrasenaInput,
                                nombre = nombreInput,
                                apellido = apellidoInput,
                                sexo = sexoInput,
                                edad = edadInput.toIntOrNull(),
                                profesion = profesionInput,
                                estadoCivil = estadoCivilInput,
                                onExito = {
                                    cargando = false
                                    onLoginSuccess()
                                },
                                onError = { msg ->
                                    cargando = false
                                    errorMsg = msg
                                }
                            )
                        }
                    },
                    enabled = !cargando,
                    modifier = Modifier.fillMaxWidth().height(50.dp)
                ) {
                    if (cargando) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), color = MaterialTheme.colorScheme.onPrimary)
                    } else {
                        Text(if (esIniciarSesion) "Iniciar Sesión" else "Registrarme")
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(if (esIniciarSesion) "¿No tienes una cuenta?" else "¿Ya tienes cuenta?")
                    TextButton(onClick = {
                        esIniciarSesion = !esIniciarSesion
                        errorMsg = null
                        exitoMsg = null
                    }) {
                        Text(if (esIniciarSesion) "Regístrate" else "Inicia Sesión", fontWeight = FontWeight.Bold)
                    }
                }
            }
        } else {
            // ================= MODO TELÉFONO / SMS =================
            if (!esperandoCodigoSms) {
                if (!esIniciarSesion) {
                    item {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = nombreInput,
                                onValueChange = { nombreInput = it },
                                label = { Text("Nombre") },
                                leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = apellidoInput,
                                onValueChange = { apellidoInput = it },
                                label = { Text("Apellido") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Spacer(modifier = Modifier.height(10.dp))

                        // Sexo y Edad
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = edadInput,
                                onValueChange = { edadInput = it.filter { c -> c.isDigit() }.take(3) },
                                label = { Text("Edad") },
                                placeholder = { Text("Ej: 25") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.weight(0.8f)
                            )

                            // Selector Sexo Chips
                            Column(modifier = Modifier.weight(1.2f)) {
                                Text("Sexo:", style = MaterialTheme.typography.labelSmall)
                                Row(modifier = Modifier.horizontalScroll(rememberScrollState()).padding(top = 2.dp)) {
                                    listOf("Masculino", "Femenino", "Otro").forEach { s ->
                                        FilterChip(
                                            selected = sexoInput == s,
                                            onClick = { sexoInput = s },
                                            label = { Text(s, style = MaterialTheme.typography.labelSmall) },
                                            modifier = Modifier.padding(end = 4.dp)
                                        )
                                    }
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(10.dp))

                        // Campos opcionales: Profesión y Estado Civil
                        OutlinedTextField(
                            value = profesionInput,
                            onValueChange = { profesionInput = it },
                            label = { Text("Profesión / Ocupación (opcional)") },
                            placeholder = { Text("Ej: Ingeniero, Comerciante, Estudiante") },
                            leadingIcon = { Icon(Icons.Default.Work, contentDescription = null) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        // Estado civil chips opcionales
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text("Estado civil (opcional):", style = MaterialTheme.typography.labelSmall)
                            Row(modifier = Modifier.horizontalScroll(rememberScrollState()).padding(top = 2.dp)) {
                                listOf("Soltero(a)", "Casado(a)", "Conviviente", "Divorciado(a)").forEach { est ->
                                    FilterChip(
                                        selected = estadoCivilInput == est,
                                        onClick = { estadoCivilInput = if (estadoCivilInput == est) "" else est },
                                        label = { Text(est, style = MaterialTheme.typography.labelSmall) },
                                        modifier = Modifier.padding(end = 4.dp)
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                    }
                }

                item {
                    OutlinedTextField(
                        value = telefonoInput,
                        onValueChange = { nuevo ->
                            val filtrado = nuevo.filter { it.isDigit() }.take(9)
                            telefonoInput = filtrado
                        },
                        label = { Text("Número de celular") },
                        placeholder = { Text("987 654 321") },
                        leadingIcon = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(start = 12.dp, end = 4.dp)
                            ) {
                                Text("🇵🇪 +51", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.width(6.dp))
                                Box(
                                    modifier = Modifier
                                        .height(20.dp)
                                        .width(1.dp)
                                        .background(MaterialTheme.colorScheme.outlineVariant)
                                )
                            }
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = {
                            val tel = telefonoInput.trim()
                            if (tel.length != 9 || !tel.startsWith("9")) {
                                errorMsg = "Ingresa un número celular de Perú válido (9 dígitos comenzando con 9)"
                                return@Button
                            }
                            if (!esIniciarSesion && nombreInput.isBlank()) {
                                errorMsg = "Por favor ingresa tu nombre"
                                return@Button
                            }
                            val telefonoCompleto = "+51$tel"
                            cargando = true
                            errorMsg = null
                            val activity = context as? Activity
                            if (activity == null) {
                                cargando = false
                                errorMsg = "No se pudo obtener la actividad"
                                return@Button
                            }
                            val options = com.google.firebase.auth.PhoneAuthOptions.newBuilder(com.google.firebase.auth.FirebaseAuth.getInstance())
                                .setPhoneNumber(telefonoCompleto)
                                .setTimeout(60L, java.util.concurrent.TimeUnit.SECONDS)
                                .setActivity(activity)
                                .setCallbacks(object : com.google.firebase.auth.PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
                                    override fun onVerificationCompleted(credential: com.google.firebase.auth.PhoneAuthCredential) {
                                        cargando = true
                                        com.google.firebase.auth.FirebaseAuth.getInstance().signInWithCredential(credential)
                                            .addOnSuccessListener { res ->
                                                cargando = false
                                                res.user?.let { u ->
                                                    viewModel.guardarSesionTelefono(
                                                        user = u,
                                                        nombre = nombreInput,
                                                        apellido = apellidoInput,
                                                        sexo = sexoInput,
                                                        edad = edadInput.toIntOrNull(),
                                                        profesion = profesionInput,
                                                        estadoCivil = estadoCivilInput,
                                                        onExito = onLoginSuccess
                                                    )
                                                }
                                            }
                                            .addOnFailureListener { ex ->
                                                cargando = false
                                                errorMsg = ex.localizedMessage ?: "Error al autenticar teléfono"
                                            }
                                    }

                                    override fun onVerificationFailed(e: com.google.firebase.FirebaseException) {
                                        cargando = false
                                        errorMsg = "Error SMS: ${e.localizedMessage ?: e.message}"
                                    }

                                    override fun onCodeSent(verId: String, token: com.google.firebase.auth.PhoneAuthProvider.ForceResendingToken) {
                                        cargando = false
                                        verificationIdStored = verId
                                        resendTokenStored = token
                                        esperandoCodigoSms = true
                                        exitoMsg = "Código SMS enviado a +51 $tel"
                                    }
                                })
                                .build()
                            com.google.firebase.auth.PhoneAuthProvider.verifyPhoneNumber(options)
                        },
                        enabled = !cargando,
                        modifier = Modifier.fillMaxWidth().height(50.dp)
                    ) {
                        if (cargando) {
                            CircularProgressIndicator(modifier = Modifier.size(22.dp), color = MaterialTheme.colorScheme.onPrimary)
                        } else {
                            Icon(Icons.Default.Send, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(if (esIniciarSesion) "Enviar Código por SMS" else "Registrarme y Enviar Código")
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(if (esIniciarSesion) "¿No tienes una cuenta?" else "¿Ya tienes cuenta?")
                        TextButton(onClick = {
                            esIniciarSesion = !esIniciarSesion
                            errorMsg = null
                            exitoMsg = null
                        }) {
                            Text(if (esIniciarSesion) "Regístrate" else "Inicia Sesión", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            } else {
                item {
                    OutlinedTextField(
                        value = codigoSmsInput,
                        onValueChange = { codigoSmsInput = it },
                        label = { Text("Código de 6 dígitos") },
                        leadingIcon = { Icon(Icons.Default.Pin, contentDescription = null) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = {
                            val verId = verificationIdStored
                            if (verId.isNullOrBlank() || codigoSmsInput.trim().length < 6) {
                                errorMsg = "Ingresa el código completo de 6 dígitos"
                                return@Button
                            }
                            cargando = true
                            errorMsg = null
                            val credential = com.google.firebase.auth.PhoneAuthProvider.getCredential(verId, codigoSmsInput.trim())
                            com.google.firebase.auth.FirebaseAuth.getInstance().signInWithCredential(credential)
                                .addOnSuccessListener { res ->
                                    cargando = false
                                    res.user?.let { u ->
                                        viewModel.guardarSesionTelefono(
                                            user = u,
                                            nombre = nombreInput,
                                            apellido = apellidoInput,
                                            sexo = sexoInput,
                                            edad = edadInput.toIntOrNull(),
                                            profesion = profesionInput,
                                            estadoCivil = estadoCivilInput,
                                            onExito = onLoginSuccess
                                        )
                                    }
                                }
                                .addOnFailureListener { ex ->
                                    cargando = false
                                    errorMsg = ex.localizedMessage ?: "Código incorrecto o expirado"
                                }
                        },
                        enabled = !cargando,
                        modifier = Modifier.fillMaxWidth().height(50.dp)
                    ) {
                        if (cargando) {
                            CircularProgressIndicator(modifier = Modifier.size(22.dp), color = MaterialTheme.colorScheme.onPrimary)
                        } else {
                            Text("Verificar y Entrar")
                        }
                    }
                    TextButton(onClick = { esperandoCodigoSms = false }) {
                        Text("Cambiar número de teléfono")
                    }
                }
            }
        }

        // ================= MENSAJES DE ESTADO =================
        item {
            // Mensajes de error / éxito
            errorMsg?.let { err ->
                Spacer(modifier = Modifier.height(16.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "⚠ $err",
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            exitoMsg?.let { msg ->
                Spacer(modifier = Modifier.height(16.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "✓ $msg",
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }

    // Dialog Recuperar Contraseña
    if (mostrarRecuperarDialog) {
        var correoRecuperar by remember { mutableStateOf(correoInput) }
        var cargandoReset by remember { mutableStateOf(false) }
        var resetMsg by remember { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = { mostrarRecuperarDialog = false },
            title = { Text("Recuperar Contraseña") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Ingresa tu correo y te enviaremos un enlace seguro para restablecerla.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = correoRecuperar,
                        onValueChange = { correoRecuperar = it },
                        label = { Text("Correo electrónico") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    resetMsg?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (correoRecuperar.isBlank()) return@Button
                        cargandoReset = true
                        viewModel.recuperarContrasena(
                            correo = correoRecuperar,
                            onExito = {
                                cargandoReset = false
                                exitoMsg = "Correo enviado. Revisa tu bandeja de entrada o spam."
                                mostrarRecuperarDialog = false
                            },
                            onError = { err ->
                                cargandoReset = false
                                resetMsg = "Error: $err"
                            }
                        )
                    },
                    enabled = !cargandoReset
                ) {
                    Text(if (cargandoReset) "Enviando..." else "Enviar enlace")
                }
            },
            dismissButton = {
                TextButton(onClick = { mostrarRecuperarDialog = false }) { Text("Cancelar") }
            }
        )
    }
}

@Composable
fun BiometricLockScreen(
    activity: FragmentActivity,
    onUnlockSuccess: () -> Unit
) {
    LaunchedEffect(Unit) {
        BiometricHelper.mostrarAutenticacion(
            activity = activity,
            onSuccess = onUnlockSuccess,
            onError = { Toast.makeText(activity, it, Toast.LENGTH_SHORT).show() }
        )
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Default.Fingerprint, contentDescription = null, modifier = Modifier.size(100.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.height(24.dp))
        Text("Bloqueo de Seguridad", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))
        Text("Ocultando tus saldos y cuentas de miradas ajenas.", textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(32.dp))
        Button(onClick = {
            BiometricHelper.mostrarAutenticacion(
                activity = activity,
                onSuccess = onUnlockSuccess,
                onError = { Toast.makeText(activity, it, Toast.LENGTH_SHORT).show() }
            )
        }) {
            Icon(Icons.Default.LockOpen, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Desbloquear con Huella")
        }
    }
}

// =========================================================================
// ETAPA 2: CONFIGURACIÓN INICIAL (ONBOARDING DE ÚNICA VEZ)
// =========================================================================

@Composable
fun SetupAccountsScreen(
    efectivo: String, onEfectivoChange: (String) -> Unit,
    bcp: String, onBcpChange: (String) -> Unit,
    ahorro: String, onAhorroChange: (String) -> Unit,
    inversion: String, onInversionChange: (String) -> Unit,
    onSiguiente: () -> Unit
) {
    LazyColumn(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        item {
            Text("Paso 1 de 3", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text("Tus 3 Bloques de Liquidez", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("Ingresa con qué saldo comienzas en cada caja.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(24.dp))

            Text("1. Disponible Diario (Gasto cotidiano)", fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = efectivo, onValueChange = onEfectivoChange,
                label = { Text("Efectivo / Billetera (S/)") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )
            OutlinedTextField(
                value = bcp, onValueChange = onBcpChange,
                label = { Text("Cuenta Principal / Yape (S/)") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )

            Spacer(modifier = Modifier.height(20.dp))
            Text("2. Ahorro Intocable", fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = ahorro, onValueChange = onAhorroChange,
                label = { Text("Bolsillos / Alcancía (S/)") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )

            Spacer(modifier = Modifier.height(20.dp))
            Text("3. Inversiones", fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = inversion, onValueChange = onInversionChange,
                label = { Text("Fondos / Plazo Fijo (S/)") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )

            Spacer(modifier = Modifier.height(32.dp))
            Button(onClick = onSiguiente, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                Text("Siguiente: Pagos Fijos →")
            }
        }
    }
}

@Composable
fun SetupRecurringScreen(
    diaCorte: Int, onDiaCorteChange: (Int) -> Unit,
    viewModel: GastosViewModel,
    onSiguiente: () -> Unit,
    onVolver: () -> Unit
) {
    var diaText by remember { mutableStateOf(diaCorte.toString()) }
    var nombreServicio by remember { mutableStateOf("") }
    var montoServicio by remember { mutableStateOf("") }
    var diaVence by remember { mutableStateOf("15") }
    val pagosFijos by viewModel.pagosFijos.collectAsState()

    LazyColumn(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        item {
            Text("Paso 2 de 3", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text("Pagos Fijos y Día de Corte", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("¿Qué día inicia tu mes financiero y qué recibos fijos pagas?", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(20.dp))

            OutlinedTextField(
                value = diaText,
                onValueChange = {
                    diaText = it
                    it.toIntOrNull()?.let { d -> onDiaCorteChange(d.coerceIn(1, 31)) }
                },
                label = { Text("Día de corte del mes (ej. 1 o 25 al cobrar sueldo)") },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(24.dp))
            Text("Agregar tus Pagos Fijos Mensuales:", fontWeight = FontWeight.Bold)
            Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = nombreServicio, onValueChange = { nombreServicio = it }, label = { Text("Recibo (ej. Plan Celular)") }, modifier = Modifier.weight(1.5f), singleLine = true)
                OutlinedTextField(value = montoServicio, onValueChange = { montoServicio = it }, label = { Text("S/") }, modifier = Modifier.weight(1f), singleLine = true)
            }
            Button(
                onClick = {
                    val m = montoServicio.toDoubleOrNull()
                    if (nombreServicio.isNotBlank() && m != null) {
                        viewModel.crearPagoFijo(nombreServicio, m, diaVence.toIntOrNull() ?: 1, "Servicios", "cuenta_bcp_yape")
                        nombreServicio = ""
                        montoServicio = ""
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) {
                Text("+ Añadir Pago Fijo")
            }

            Spacer(modifier = Modifier.height(16.dp))
            pagosFijos.forEach { p ->
                Text("• ${p.nombre}: S/ ${p.monto} (Vence día ${p.diaPago})", style = MaterialTheme.typography.bodyMedium)
            }

            Spacer(modifier = Modifier.height(32.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onVolver, modifier = Modifier.weight(1f)) { Text("Volver") }
                Button(onClick = onSiguiente, modifier = Modifier.weight(1f)) { Text("Siguiente →") }
            }
        }
    }
}

@Composable
fun SetupGoalsScreen(
    metaAhorro: String, onMetaAhorroChange: (String) -> Unit,
    onComenzar: () -> Unit,
    onVolver: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("Paso 3 de 3", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Text("Tu Meta de Ahorro", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("¿Cuánto dinero o porcentaje deseas separar de forma intocable cada mes para que el sistema nunca lo cuente como disponible para gastar?", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(24.dp))

        OutlinedTextField(
            value = metaAhorro,
            onValueChange = onMetaAhorroChange,
            label = { Text("Meta de Ahorro Mensual (S/)") },
            placeholder = { Text("Ej: 300") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(32.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onVolver, modifier = Modifier.weight(1f)) { Text("Volver") }
            Button(onClick = onComenzar, modifier = Modifier.weight(1f)) { Text("¡Comenzar!") }
        }
    }
}

// =========================================================================
// ETAPA 3: PANTALLA PRINCIPAL CON LAS 4 PESTAÑAS (HOME, ACCOUNTS, BUDGET, ANALYTICS)
// =========================================================================

enum class MainTab { HOME, ACCOUNTS, BUDGET, ANALYTICS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainTabsScreen(
    viewModel: GastosViewModel,
    format: NumberFormat,
    onOpenManualEntry: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit
) {
    var selectedTab by remember { mutableStateOf(MainTab.HOME) }
    val ocupado by viewModel.ocupado.collectAsState()

    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp
            ) {
                val navItemColors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Color(0xFF2DD4BF),
                    selectedTextColor = Color(0xFF2DD4BF),
                    indicatorColor = Color(0xFF133833),
                    unselectedIconColor = Color(0xFF6B7E96),
                    unselectedTextColor = Color(0xFF6B7E96)
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Home, contentDescription = "Inicio") },
                    label = { Text("Inicio") },
                    selected = selectedTab == MainTab.HOME,
                    onClick = { selectedTab = MainTab.HOME },
                    colors = navItemColors
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.AccountBalance, contentDescription = "Cuentas") },
                    label = { Text("Cuentas") },
                    selected = selectedTab == MainTab.ACCOUNTS,
                    onClick = { selectedTab = MainTab.ACCOUNTS },
                    colors = navItemColors
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.CalendarMonth, contentDescription = "Presupuesto") },
                    label = { Text("Presupuesto") },
                    selected = selectedTab == MainTab.BUDGET,
                    onClick = { selectedTab = MainTab.BUDGET },
                    colors = navItemColors
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.AutoAwesome, contentDescription = "Copiloto IA") },
                    label = { Text("Copiloto IA") },
                    selected = selectedTab == MainTab.ANALYTICS,
                    onClick = { selectedTab = MainTab.ANALYTICS },
                    colors = navItemColors
                )
            }
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (ocupado) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            when (selectedTab) {
                MainTab.HOME -> HomeScreen(
                    viewModel = viewModel,
                    format = format,
                    onOpenHistory = onOpenHistory,
                    onOpenSettings = onOpenSettings
                )
                MainTab.ACCOUNTS -> AccountsScreen(
                    viewModel = viewModel,
                    format = format,
                    onOpenSettings = onOpenSettings
                )
                MainTab.BUDGET -> BudgetScreen(
                    viewModel = viewModel,
                    format = format,
                    onOpenSettings = onOpenSettings
                )
                MainTab.ANALYTICS -> AnalyticsAiScreen(
                    viewModel = viewModel,
                    format = format,
                    onOpenSettings = onOpenSettings
                )
            }
        }
    }
}

// -------------------------------------------------------------------------
// PESTAÑA 1: INICIO / DASHBOARD (HOME SCREEN - REDISEÑO STITCH)
// -------------------------------------------------------------------------
@Composable
fun HomeScreen(
    viewModel: GastosViewModel,
    format: NumberFormat,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val saldoLibrePorDia by viewModel.saldoLibrePorDia.collectAsState()
    val saldoDisponibleTotal by viewModel.saldoDisponibleTotal.collectAsState()
    val pagosFijosPendientesMes by viewModel.pagosFijosPendientesMes.collectAsState()
    val metaAhorroMes by viewModel.metaAhorroMes.collectAsState()
    val gastos by viewModel.gastos.collectAsState()
    val ingresosProgramados by viewModel.ingresosProgramados.collectAsState()
    var textoBarra by remember { mutableStateOf("") }

    val calHoy = remember { Calendar.getInstance() }
    val diaHoy = calHoy.get(Calendar.DAY_OF_MONTH)
    val anioHoy = calHoy.get(Calendar.YEAR)
    val mesIndexHoy = calHoy.get(Calendar.MONTH)
    val diasEnMes = calHoy.getActualMaximum(Calendar.DAY_OF_MONTH)

    val inicioHoy = remember(diaHoy, mesIndexHoy, anioHoy) {
        Calendar.getInstance().apply {
            set(Calendar.YEAR, anioHoy)
            set(Calendar.MONTH, mesIndexHoy)
            set(Calendar.DAY_OF_MONTH, diaHoy)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
    val finHoy = remember(diaHoy, mesIndexHoy, anioHoy) {
        Calendar.getInstance().apply {
            set(Calendar.YEAR, anioHoy)
            set(Calendar.MONTH, mesIndexHoy)
            set(Calendar.DAY_OF_MONTH, diaHoy)
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
            set(Calendar.MILLISECOND, 999)
        }.timeInMillis
    }
    val ultimos = remember(gastos, inicioHoy, finHoy) {
        gastos.filter { !it.esTransferencia && it.fecha in inicioHoy..finHoy }
    }

    val proximoCobroInfo = remember(ingresosProgramados, diaHoy, mesIndexHoy, anioHoy) {
        val pendientesConDia = mutableListOf<Pair<Int, String>>()
        for (ing in ingresosProgramados) {
            val dias = GastosViewModel.obtenerDiasCobroMes(ing, anioHoy, mesIndexHoy)
            for (d in dias) {
                val fKey = String.format(Locale.US, "%04d-%02d-%02d", anioHoy, mesIndexHoy + 1, d)
                if (GastosViewModel.estaFechaCobrada(ing, fKey) == null) {
                    pendientesConDia.add(Pair(d, ing.nombre))
                }
            }
        }
        val futuros = pendientesConDia.filter { it.first >= diaHoy }.sortedBy { it.first }
        if (futuros.isNotEmpty()) {
            futuros.first()
        } else {
            pendientesConDia.minByOrNull { it.first }
        }
    }

    val textoSubIngreso = when {
        proximoCobroInfo != null && proximoCobroInfo.first == diaHoy ->
            "(¡Hoy toca cobrar ${proximoCobroInfo.second}!)"
        proximoCobroInfo != null && proximoCobroInfo.first == diaHoy + 1 ->
            "(Falta 1 día para cobrar ${proximoCobroInfo.second})"
        proximoCobroInfo != null && proximoCobroInfo.first > diaHoy ->
            "(Faltan ${proximoCobroInfo.first - diaHoy} días para ${proximoCobroInfo.second})"
        else ->
            "(${diasEnMes - diaHoy + 1} días restantes del mes)"
    }

    val voiceLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val spokenText = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!spokenText.isNullOrBlank()) {
                textoBarra = spokenText
                viewModel.procesarTextoInteligente(spokenText)
            }
        }
    }

    val voucherPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.procesarVoucherImagen(uri)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        // ── ENCABEZADO CON PERFIL ─────────────────────────────────────────
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        "Mis Gastos AI",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(Color(0xFF22C55E), CircleShape)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            "Gestión Inteligente en Tiempo Real",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Surface(
                    onClick = onOpenSettings,
                    shape = CircleShape,
                    color = Color(0xFF16253B),
                    border = BorderStroke(1.5.dp, Color(0xFF38BDF8)),
                    modifier = Modifier.size(42.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.Person,
                            contentDescription = "Perfil y Ajustes",
                            modifier = Modifier.size(24.dp),
                            tint = Color(0xFF76D1FF)
                        )
                    }
                }
            }
        }

        // ── 1. TARJETA "DISPONIBLE HOY" ──────────────────────────────────
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF132034)),
                border = BorderStroke(1.dp, Color(0xFF1D324E))
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(
                        "DISPONIBLE HOY",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color(0xFF70B4E8),
                        letterSpacing = 0.8.sp
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        format.format(saldoLibrePorDia),
                        fontSize = 38.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color(0xFF76D1FF)
                    )

                    Spacer(modifier = Modifier.height(2.dp))

                    Text(
                        textoSubIngreso,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF8E9FB8),
                        fontSize = 12.sp
                    )

                    Spacer(modifier = Modifier.height(18.dp))

                    // 3 Métricas en fila
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Subtarjeta: Liquidez Total
                        Surface(
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp),
                            color = Color(0xFF0F1B2C),
                            border = BorderStroke(1.dp, Color(0xFF192C45))
                        ) {
                            Column(
                                modifier = Modifier.padding(vertical = 10.dp, horizontal = 4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    "LIQUIDEZ TO...",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF8E9FB8),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    format.format(saldoDisponibleTotal),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        // Subtarjeta: Fijos Pendientes
                        Surface(
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp),
                            color = Color(0xFF0F1B2C),
                            border = BorderStroke(1.dp, Color(0xFF192C45))
                        ) {
                            Column(
                                modifier = Modifier.padding(vertical = 10.dp, horizontal = 4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    "FIJOS PENDI...",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF8E9FB8),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    "-${format.format(pagosFijosPendientesMes)}",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = Color(0xFFFF8DA1),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        // Subtarjeta: Ahorro del Mes
                        Surface(
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp),
                            color = Color(0xFF0F1B2C),
                            border = BorderStroke(1.dp, Color(0xFF192C45))
                        ) {
                            Column(
                                modifier = Modifier.padding(vertical = 10.dp, horizontal = 4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    "AHORRO DEL...",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF8E9FB8),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    "-${format.format(metaAhorroMes)}",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = Color(0xFF76D1FF),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(14.dp))
        }

        // ── 2. BARRA MULTIMODO CON IA ─────────────────────────────────────
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF131F33)),
                border = BorderStroke(1.dp, Color(0xFF1D2F4B))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Botón Voucher / Recibo
                    Surface(
                        onClick = { voucherPickerLauncher.launch("image/*") },
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFF192B45),
                        modifier = Modifier.size(38.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.Description,
                                contentDescription = "Subir voucher",
                                tint = Color(0xFF76D1FF),
                                modifier = Modifier.size(19.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    // Campo de entrada de texto
                    BasicTextField(
                        value = textoBarra,
                        onValueChange = { textoBarra = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 13.sp
                        ),
                        decorationBox = { inner ->
                            if (textoBarra.isEmpty()) {
                                Text(
                                    "Escribe o dicta: 'Cena 15 con Yape ...'",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFF8294A8),
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            inner()
                        }
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    // Botón Enviar o Micrófono
                    if (textoBarra.isNotBlank()) {
                        Surface(
                            onClick = {
                                viewModel.procesarTextoInteligente(textoBarra)
                                textoBarra = ""
                            },
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFF76D1FF),
                            modifier = Modifier.size(38.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.AutoMirrored.Filled.Send,
                                    contentDescription = "Enviar",
                                    tint = Color(0xFF0F1A2A),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    } else {
                        Surface(
                            onClick = {
                                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-PE")
                                }
                                voiceLauncher.launch(intent)
                            },
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFF192B45),
                            modifier = Modifier.size(38.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.Mic,
                                    contentDescription = "Dictar",
                                    tint = Color(0xFF76D1FF),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(14.dp))
        }

        // ── 3. ENCABEZADO "GASTOS DE HOY" ────────────────────────────────────
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Gastos de Hoy",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        if (ultimos.isNotEmpty()) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                shape = CircleShape,
                                color = Color(0xFF1E2E48),
                                modifier = Modifier.padding(top = 1.dp)
                            ) {
                                Text(
                                    text = "${ultimos.size}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF76D1FF),
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                                )
                            }
                        }
                    }
                    val totalGastadoHoy = ultimos.filter { !it.esIngreso }.sumOf { it.montoRealUsuario }
                    if (ultimos.isNotEmpty()) {
                        Text(
                            text = "Total gastado hoy: -${format.format(totalGastadoHoy)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFFF8DA1),
                            fontWeight = FontWeight.SemiBold
                        )
                    } else {
                        Text(
                            text = "Solo movimientos de la fecha actual",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF8294A8)
                        )
                    }
                }
                TextButton(onClick = onOpenHistory) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            "Ver Historial",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF38BDF8)
                        )
                        Text(
                            "Completo",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF8294A8)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
        }

        if (ultimos.isEmpty()) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0xFF131F33).copy(alpha = 0.5f)
                ) {
                    Text(
                        "Aún no tienes gastos registrados hoy.\nPresiona \"Ver Historial\" para consultar días anteriores.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(16.dp),
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            items(ultimos, key = { it.id }) { g ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF131F33)),
                    border = BorderStroke(1.dp, Color(0xFF1E2E48).copy(alpha = 0.5f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Emoji/Icono según categoría
                        val emoji = when (g.categoria) {
                            "Comida", "Alimentación" -> "🍱"
                            "Hogar" -> "🏠"
                            "Transporte" -> "🚗"
                            "Entretenimiento" -> "🎮"
                            "Salud" -> "💊"
                            "Educación" -> "📚"
                            "Ropa" -> "👕"
                            "Servicios" -> "💡"
                            else -> if (g.esIngreso) "💰" else "📦"
                        }
                        Box(
                            modifier = Modifier
                                .size(46.dp)
                                .background(Color(0xFF1B283E), RoundedCornerShape(14.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(emoji, fontSize = 22.sp)
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                g.categoria,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            val horaStr = SimpleDateFormat("HH:mm", Locale("es", "PE")).format(Date(g.fecha))
                            val subtitulo = if (g.nota.isNotBlank()) "Hoy $horaStr • ${g.nota}" else "Hoy $horaStr"
                            Text(
                                subtitulo,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF8294A8),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        val signo = if (g.esIngreso) "+" else "-"
                        val colorMonto = if (g.esIngreso) Color(0xFF4ADE80) else Color(0xFFFF8DA1)
                        Text(
                            "$signo${format.format(g.montoRealUsuario)}",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = colorMonto
                        )

                        Spacer(modifier = Modifier.width(4.dp))

                        IconButton(
                            onClick = { viewModel.eliminar(g) },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Eliminar",
                                tint = Color(0xFF6B7E96),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

// -------------------------------------------------------------------------
// PESTAÑA 2: CUENTAS, BOLSILLOS Y DEUDAS (ACCOUNTS SCREEN - REDISEÑO STITCH)
// -------------------------------------------------------------------------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountsScreen(
    viewModel: GastosViewModel,
    format: NumberFormat,
    onOpenSettings: () -> Unit = {}
) {
    var subTabState by remember { mutableStateOf(0) }
    val cuentas by viewModel.cuentas.collectAsState()
    val porCobrar by viewModel.porCobrar.collectAsState()
    val deudasPrestamos by viewModel.deudasPrestamos.collectAsState()
    val ingresosProgramados by viewModel.ingresosProgramados.collectAsState()
    val pagosFijos by viewModel.pagosFijos.collectAsState()
    val metaAhorro by viewModel.metaAhorroMes.collectAsState()
    val saldoAhorro by viewModel.saldoAhorroTotal.collectAsState()
    var showTransferenciaModal by remember { mutableStateOf(false) }
    var showNuevaCuentaModal by remember { mutableStateOf(false) }
    var showNuevaDeudaModal by remember { mutableStateOf(false) }
    var subTabDeudas by remember { mutableIntStateOf(0) } // 0: Nos deben, 1: Debemos
    var mostrarCalendarioDeudas by remember { mutableStateOf(false) }
    var diaSeleccionadoCalDeudas by remember { mutableStateOf<Int?>(null) }

    val ahoraAccounts = remember { Calendar.getInstance() }
    val anioAccounts = ahoraAccounts.get(Calendar.YEAR)
    val mesIndexAccounts = ahoraAccounts.get(Calendar.MONTH)
    val mesNombreAccounts = remember { SimpleDateFormat("MMMM yyyy", Locale("es", "PE")).format(ahoraAccounts.time).replaceFirstChar { it.uppercase() } }
    val mesActualStrAccounts = remember { SimpleDateFormat("yyyy-MM", Locale.US).format(Date()) }

    // Estado para editar cuenta
    var cuentaEditando by remember { mutableStateOf<Cuenta?>(null) }
    var nombreEdit by remember { mutableStateOf("") }
    var saldoEdit by remember { mutableStateOf("") }

    // Clasificación de cuentas
    val cuentasEfectivo = cuentas.filter {
        it.tipo == Cuenta.TIPO_DISPONIBLE && (it.nombre.contains("efectivo", ignoreCase = true) || it.nombre.contains("billetera", ignoreCase = true) || it.id == "cuenta_efectivo")
    }
    val cuentasBancos = cuentas.filter {
        it.tipo == Cuenta.TIPO_DISPONIBLE && !cuentasEfectivo.contains(it)
    }
    val cuentasAhorroEInversion = cuentas.filter {
        it.tipo == Cuenta.TIPO_AHORRO || it.tipo == Cuenta.TIPO_INVERSION
    }

    val totalEfectivo = cuentasEfectivo.sumOf { it.saldo }
    val totalBancos = cuentasBancos.sumOf { it.saldo }
    val totalAhorro = cuentasAhorroEInversion.sumOf { it.saldo }
    val patrimonioTotal = cuentas.sumOf { it.saldo }

    @Composable
    fun CuentaItemCard(c: Cuenta) {
        val isCash = cuentasEfectivo.contains(c)
        val isAhorro = cuentasAhorroEInversion.contains(c)

        val iconBgColor = when {
            isCash -> Color(0xFF0F322B)
            isAhorro -> Color(0xFF2E1A47)
            c.nombre.contains("diario", ignoreCase = true) || c.nombre.contains("tarjeta", ignoreCase = true) -> Color(0xFF1E2445)
            c.nombre.contains("pichinga", ignoreCase = true) -> Color(0xFF132F3E)
            else -> Color(0xFF142B47)
        }
        val iconTint = when {
            isCash -> Color(0xFF34D399)
            isAhorro -> Color(0xFFC084FC)
            c.nombre.contains("diario", ignoreCase = true) || c.nombre.contains("tarjeta", ignoreCase = true) -> Color(0xFF818CF8)
            c.nombre.contains("pichinga", ignoreCase = true) -> Color(0xFF22D3EE)
            else -> Color(0xFF38BDF8)
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF131F33)),
            border = BorderStroke(1.dp, Color(0xFF1E2E48).copy(alpha = 0.5f))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Icon Box
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(iconBgColor, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    when {
                        isCash -> Icon(Icons.Default.AccountBalanceWallet, contentDescription = null, tint = iconTint, modifier = Modifier.size(22.dp))
                        isAhorro -> Text("🐷", fontSize = 20.sp)
                        c.nombre.contains("diario", ignoreCase = true) || c.nombre.contains("tarjeta", ignoreCase = true) -> Icon(Icons.Default.CreditCard, contentDescription = null, tint = iconTint, modifier = Modifier.size(22.dp))
                        c.nombre.contains("pichinga", ignoreCase = true) -> Text("⚽", fontSize = 20.sp)
                        else -> Icon(Icons.Default.AccountBalance, contentDescription = null, tint = iconTint, modifier = Modifier.size(22.dp))
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            c.nombre,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        if (c.nombre.contains("BCP", ignoreCase = true) || c.nombre.contains("Yape", ignoreCase = true)) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                color = Color(0xFF381F4B),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    "Frecuente",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFC084FC),
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    val subtitulo = when {
                        isAhorro -> "Fondo de reserva"
                        c.nombre.contains("efectivo", ignoreCase = true) || c.nombre.contains("billetera", ignoreCase = true) -> "Billetera física"
                        c.nombre.contains("yape", ignoreCase = true) || c.nombre.contains("bcp", ignoreCase = true) -> "Débito principal"
                        c.nombre.contains("diario", ignoreCase = true) -> "Gastos corrientes"
                        c.nombre.contains("pichinga", ignoreCase = true) -> "Fondo deportivo & ocio"
                        else -> "Cuenta activa"
                    }
                    Text(
                        subtitulo,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF8E9FB8)
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                Text(
                    format.format(c.saldo),
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = Color.White
                )

                Spacer(modifier = Modifier.width(8.dp))

                Surface(
                    onClick = {
                        cuentaEditando = c
                        nombreEdit = c.nombre
                        saldoEdit = c.saldo.toString()
                    },
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0xFF192A40),
                    modifier = Modifier.size(32.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = "Editar",
                            tint = Color(0xFF8E9FB8),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        // ── ENCABEZADO ────────────────────────────────────────────────────
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFF14243A),
                        border = BorderStroke(1.dp, Color(0xFF1E3553)),
                        modifier = Modifier.size(42.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.AccountBalanceWallet,
                                contentDescription = null,
                                tint = Color(0xFF76D1FF),
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            "MIS GASTOS",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF70B4E8),
                            letterSpacing = 0.5.sp,
                            fontSize = 10.sp
                        )
                        Text(
                            "Cuentas y Saldos",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = CircleShape,
                        color = Color(0xFF14243A),
                        border = BorderStroke(1.dp, Color(0xFF1E3553)),
                        modifier = Modifier.size(38.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.Notifications,
                                contentDescription = "Notificaciones",
                                tint = Color(0xFF76D1FF),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    Surface(
                        onClick = onOpenSettings,
                        shape = CircleShape,
                        color = Color(0xFF16253B),
                        border = BorderStroke(1.5.dp, Color(0xFF38BDF8)),
                        modifier = Modifier.size(38.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.Person,
                                contentDescription = "Perfil y Ajustes",
                                tint = Color(0xFF76D1FF),
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
            }
        }

        // ── SELECTOR SEGMENTADO (Mis Cuentas / Por Cobrar) ───────────────
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = Color(0xFF0F1A2B),
                border = BorderStroke(1.dp, Color(0xFF1B2B42))
            ) {
                Row(
                    modifier = Modifier.padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Tab 0: Mis Cuentas
                    Surface(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        color = if (subTabState == 0) Color(0xFF172B45) else Color.Transparent,
                        border = if (subTabState == 0) BorderStroke(1.dp, Color(0xFF264974)) else null,
                        onClick = { subTabState = 0 }
                    ) {
                        Row(
                            modifier = Modifier.padding(vertical = 10.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.AccountBalanceWallet,
                                contentDescription = null,
                                tint = if (subTabState == 0) Color(0xFF38BDF8) else Color(0xFF6B7E96),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                "Mis Cuentas",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (subTabState == 0) Color(0xFF38BDF8) else Color(0xFF8E9FB8)
                            )
                        }
                    }

                    // Tab 1: Deudas y Préstamos
                    val totalPendientes = deudasPrestamos.count { it.estado == DeudaPrestamo.ESTADO_PENDIENTE } + porCobrar.size
                    Surface(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        color = if (subTabState == 1) Color(0xFF172B45) else Color.Transparent,
                        border = if (subTabState == 1) BorderStroke(1.dp, Color(0xFF264974)) else null,
                        onClick = { subTabState = 1 }
                    ) {
                        Row(
                            modifier = Modifier.padding(vertical = 10.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.SwapHoriz,
                                contentDescription = null,
                                tint = if (subTabState == 1) Color(0xFF38BDF8) else Color(0xFF6B7E96),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                "Deudas & Préstamos",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (subTabState == 1) Color(0xFF38BDF8) else Color(0xFF8E9FB8)
                            )
                            if (totalPendientes > 0) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Surface(
                                    shape = RoundedCornerShape(50),
                                    color = if (subTabState == 1) Color(0xFF0369A1) else Color(0xFF19283E)
                                ) {
                                    Text(
                                        "$totalPendientes",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (subTabState == 1) Color.White else Color(0xFF8E9FB8),
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(14.dp))
        }

        if (subTabState == 0) {
            // ── TARJETA PATRIMONIO TOTAL ─────────────────────────────────
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF132034)),
                    border = BorderStroke(1.dp, Color(0xFF1D324E))
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                color = Color(0xFF10333E),
                                shape = RoundedCornerShape(50)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .background(Color(0xFF22C55E), CircleShape)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        "PATRIMONIO TOTAL",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = Color(0xFF38BDF8),
                                        fontSize = 10.sp,
                                        letterSpacing = 0.5.sp
                                    )
                                }
                            }
                            Text(
                                "Actualizado hoy",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF8E9FB8),
                                fontSize = 11.sp
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                "S/ ",
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF76D1FF),
                                modifier = Modifier.padding(bottom = 2.dp)
                            )
                            Text(
                                "%.2f".format(patrimonioTotal),
                                fontSize = 36.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color.White
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // Botón + Cuenta
                            Surface(
                                onClick = { showNuevaCuentaModal = true },
                                shape = RoundedCornerShape(12.dp),
                                color = Color(0xFF16253B),
                                border = BorderStroke(1.dp, Color(0xFF223856)),
                                modifier = Modifier.weight(1f).height(42.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxSize(),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.AddCircleOutline,
                                        contentDescription = null,
                                        tint = Color(0xFF76D1FF),
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        "+ Cuenta",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        color = Color.White
                                    )
                                }
                            }

                            // Botón Transferir
                            Surface(
                                onClick = { showTransferenciaModal = true },
                                shape = RoundedCornerShape(12.dp),
                                color = Color(0xFF38BDF8),
                                modifier = Modifier.weight(1f).height(42.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxSize(),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.SwapHoriz,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        "Transferir",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        color = Color.White
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
            }

            // ── SECCIÓN 1: EFECTIVO ───────────────────────────────────────
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp, bottom = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("💵", fontSize = 13.sp)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            "EFECTIVO",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color(0xFF8E9FB8),
                            letterSpacing = 0.8.sp
                        )
                    }
                    Text(
                        format.format(totalEfectivo),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF34D399)
                    )
                }
            }

            if (cuentasEfectivo.isNotEmpty()) {
                items(cuentasEfectivo, key = { it.id }) { c -> CuentaItemCard(c) }
            } else {
                item {
                    Text(
                        "No tienes cuentas de efectivo agregadas.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
            }

            // ── SECCIÓN 2: BANCOS & DIGITALES ────────────────────────────
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 14.dp, bottom = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("🏦", fontSize = 13.sp)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            "BANCOS & DIGITALES",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color(0xFF8E9FB8),
                            letterSpacing = 0.8.sp
                        )
                    }
                    Text(
                        format.format(totalBancos),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF38BDF8)
                    )
                }
            }

            if (cuentasBancos.isNotEmpty()) {
                items(cuentasBancos, key = { it.id }) { c -> CuentaItemCard(c) }
            } else {
                item {
                    Text(
                        "No tienes bancos configurados.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
            }

            // ── SECCIÓN 3: AHORRO & METAS ────────────────────────────────
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 14.dp, bottom = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("🐷", fontSize = 13.sp)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            "AHORRO & METAS",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color(0xFF8E9FB8),
                            letterSpacing = 0.8.sp
                        )
                    }
                    Text(
                        format.format(totalAhorro),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFC084FC)
                    )
                }
                if (metaAhorro > 0) {
                    val porc = (saldoAhorro / metaAhorro).toFloat().coerceIn(0f, 1f)
                    LinearProgressIndicator(
                        progress = { porc },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp)
                            .clip(RoundedCornerShape(50)),
                        color = Color(0xFFC084FC),
                        trackColor = Color(0xFF2E1A47)
                    )
                    Text(
                        "Meta: ${format.format(saldoAhorro)} de ${format.format(metaAhorro)} (${(porc * 100).toInt()}%)",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF8E9FB8),
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                }
            }

            if (cuentasAhorroEInversion.isNotEmpty()) {
                items(cuentasAhorroEInversion, key = { it.id }) { c -> CuentaItemCard(c) }
            } else {
                item {
                    Text(
                        "No tienes cuentas de ahorro o inversión configuradas.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
            }

        } else {
            // ── SUBPESTAÑA DEUDAS Y PRÉSTAMOS (NOS DEBEN / DEBEMOS) ─────────
            // 1. Selector segmentado interno: [ 📥 Nos deben ] vs [ 📤 Debemos ]
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    shape = RoundedCornerShape(14.dp),
                    color = Color(0xFF0F1A2B),
                    border = BorderStroke(1.dp, Color(0xFF1B2B42))
                ) {
                    Row(
                        modifier = Modifier.padding(3.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        val countNosDeben = deudasPrestamos.count { it.tipo == DeudaPrestamo.TIPO_POR_COBRAR && it.estado == DeudaPrestamo.ESTADO_PENDIENTE } + porCobrar.size
                        Surface(
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(11.dp),
                            color = if (subTabDeudas == 0) Color(0xFF172B45) else Color.Transparent,
                            border = if (subTabDeudas == 0) BorderStroke(1.dp, Color(0xFF0284C7)) else null,
                            onClick = { subTabDeudas = 0 }
                        ) {
                            Row(
                                modifier = Modifier.padding(vertical = 10.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "📥 Nos deben",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = if (subTabDeudas == 0) Color(0xFF38BDF8) else Color(0xFF8E9FB8)
                                )
                                if (countNosDeben > 0) {
                                    Spacer(Modifier.width(6.dp))
                                    Surface(shape = CircleShape, color = Color(0xFF0369A1)) {
                                        Text(
                                            "$countNosDeben",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                        }

                        val countDebemos = deudasPrestamos.count { it.tipo == DeudaPrestamo.TIPO_POR_PAGAR && it.estado == DeudaPrestamo.ESTADO_PENDIENTE }
                        Surface(
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(11.dp),
                            color = if (subTabDeudas == 1) Color(0xFF2E1922) else Color.Transparent,
                            border = if (subTabDeudas == 1) BorderStroke(1.dp, Color(0xFFEF4444)) else null,
                            onClick = { subTabDeudas = 1 }
                        ) {
                            Row(
                                modifier = Modifier.padding(vertical = 10.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "📤 Debemos",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = if (subTabDeudas == 1) Color(0xFFFCA5A5) else Color(0xFF8E9FB8)
                                )
                                if (countDebemos > 0) {
                                    Spacer(Modifier.width(6.dp))
                                    Surface(shape = CircleShape, color = Color(0xFF991B1B)) {
                                        Text(
                                            "$countDebemos",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 2. Tarjeta Resumen Total (Capital + Intereses)
            val listaActualDeudas = deudasPrestamos.filter { it.tipo == subTabDeudas }
            val deudasPendientes = listaActualDeudas.filter { it.estado == DeudaPrestamo.ESTADO_PENDIENTE }
            val totalBase = deudasPendientes.sumOf { it.montoBase } + (if (subTabDeudas == 0) porCobrar.sumOf { it.montoPorCobrar } else 0.0)
            val totalIntereses = deudasPendientes.sumOf { it.montoInteresCalculado }
            val totalFinal = totalBase + totalIntereses

            item {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (subTabDeudas == 0) Color(0xFF10233B) else Color(0xFF28151D)
                    ),
                    border = BorderStroke(
                        1.dp,
                        if (subTabDeudas == 0) Color(0xFF1E3A5F) else Color(0xFF4C1D26)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                if (subTabDeudas == 0) "TOTAL POR RECUPERAR" else "TOTAL PENDIENTE DE PAGO",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp,
                                color = if (subTabDeudas == 0) Color(0xFF38BDF8) else Color(0xFFFCA5A5)
                            )
                            Text(
                                "${deudasPendientes.size} compromisos",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF8E9FB8),
                                fontSize = 11.sp
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            format.format(totalFinal),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = if (subTabDeudas == 0) Color(0xFF76D1FF) else Color(0xFFF87171)
                        )
                        Spacer(Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "Capital: ${format.format(totalBase)}",
                                fontSize = 11.sp,
                                color = Color(0xFF94A3B8)
                            )
                            if (totalIntereses > 0) {
                                Text(
                                    "Interés: +${format.format(totalIntereses)}",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (subTabDeudas == 0) Color(0xFF34D399) else Color(0xFFFB923C)
                                )
                            }
                        }
                    }
                }
            }

            // 3. Botones de acción: [+ Registrar Préstamo/Deuda] y [🗓️ Calendario]
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { showNuevaDeudaModal = true },
                        modifier = Modifier.weight(1.3f).height(44.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (subTabDeudas == 0) Color(0xFF0284C7) else Color(0xFFDC2626)
                        )
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (subTabDeudas == 0) "Nuevo Préstamo" else "Nueva Deuda",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }

                    OutlinedButton(
                        onClick = { mostrarCalendarioDeudas = !mostrarCalendarioDeudas },
                        modifier = Modifier.weight(1f).height(44.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (mostrarCalendarioDeudas) Color(0xFF1E2F48) else Color.Transparent,
                            contentColor = Color(0xFF76D1FF)
                        ),
                        border = BorderStroke(1.dp, Color(0xFF264974))
                    ) {
                        Icon(Icons.Default.CalendarMonth, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            if (mostrarCalendarioDeudas) "Ocultar" else "Calendario",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp
                        )
                    }
                }
            }

            // 4. Calendario financiero opcional si el usuario lo activa
            if (mostrarCalendarioDeudas) {
                item {
                    CalendarioFinancieroDelMes(
                        anio = anioAccounts,
                        mesIndex = mesIndexAccounts,
                        mesNombre = mesNombreAccounts,
                        mesActualStr = mesActualStrAccounts,
                        ingresos = ingresosProgramados,
                        pagosFijos = pagosFijos,
                        deudas = deudasPrestamos,
                        diaSeleccionado = diaSeleccionadoCalDeudas,
                        onDiaSeleccionado = { dia ->
                            diaSeleccionadoCalDeudas = if (diaSeleccionadoCalDeudas == dia) null else dia
                        },
                        onCobrarFecha = { _, _, _ -> },
                        onPagarGastoFijo = { pago -> viewModel.marcarPagoFijoPagado(pago) },
                        onLiquidarDeuda = { deuda -> viewModel.liquidarDeudaPrestamo(deuda.id) },
                        format = format
                    )
                }
            }

            // 5. Lista de deudas / préstamos registrados
            if (listaActualDeudas.isEmpty() && (subTabDeudas != 0 || porCobrar.isEmpty())) {
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
                        shape = RoundedCornerShape(16.dp),
                        color = Color(0xFF131F33).copy(alpha = 0.5f)
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(24.dp)
                        ) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = Color(0xFF22C55E),
                                modifier = Modifier.size(44.dp)
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                if (subTabDeudas == 0) "¡No tienes préstamos otorgados pendientes!"
                                else "¡No tienes deudas por pagar pendientes!",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                textAlign = TextAlign.Center
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                if (subTabDeudas == 0) "Toca '+ Nuevo Préstamo' para registrar dinero que diste a alguien."
                                else "Toca '+ Nueva Deuda' para registrar dinero que recibiste y debes devolver.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF8E9FB8),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            } else {
                items(listaActualDeudas, key = { it.id }) { item ->
                    val esCompletado = item.estado == DeudaPrestamo.ESTADO_COMPLETADO
                    val fechaRegStr = SimpleDateFormat("dd MMM yyyy", Locale("es", "PE")).format(Date(item.fechaRegistro))
                    val fechaLimiteStr = if (item.fechaLimitePago > 0L) {
                        SimpleDateFormat("dd MMM yyyy", Locale("es", "PE")).format(Date(item.fechaLimitePago))
                    } else "Sin fecha límite"

                    val diasRestantes = if (item.fechaLimitePago > 0L) {
                        val hoyCal = Calendar.getInstance().apply {
                            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                        }
                        val limCal = Calendar.getInstance().apply {
                            timeInMillis = item.fechaLimitePago
                            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                        }
                        val diffDays = ((limCal.timeInMillis - hoyCal.timeInMillis) / (1000 * 60 * 60 * 24)).toInt()
                        diffDays
                    } else null

                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (esCompletado) Color(0xFF101B2B) else Color(0xFF132034)
                        ),
                        border = BorderStroke(
                            1.dp,
                            if (esCompletado) Color(0xFF192A40)
                            else if (subTabDeudas == 0) Color(0xFF1F3554) else Color(0xFF3E1F28)
                        )
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            // Fila Superior: Persona + Estado/Acción
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                    Surface(
                                        shape = CircleShape,
                                        color = if (subTabDeudas == 0) Color(0xFF0F322B) else Color(0xFF331D24),
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                if (subTabDeudas == 0) Icons.Default.CallReceived else Icons.Default.CallMade,
                                                contentDescription = null,
                                                tint = if (subTabDeudas == 0) Color(0xFF34D399) else Color(0xFFFB923C),
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                    Spacer(Modifier.width(10.dp))
                                    Column {
                                        Text(
                                            item.persona.ifBlank { if (subTabDeudas == 0) "Deudor" else "Acreedor" },
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White
                                        )
                                        Text(
                                            "${if (subTabDeudas == 0) "Prestado el" else "Recibido el"}: $fechaRegStr",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color(0xFF8E9FB8),
                                            fontSize = 11.sp
                                        )
                                    }
                                }

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (esCompletado) {
                                        Surface(
                                            shape = RoundedCornerShape(50),
                                            color = Color(0xFF10332D)
                                        ) {
                                            Text(
                                                if (subTabDeudas == 0) "Cobrado ✓" else "Pagado ✓",
                                                color = Color(0xFF34D399),
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 11.sp,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                            )
                                        }
                                    } else {
                                        Button(
                                            onClick = { viewModel.liquidarDeudaPrestamo(item.id) },
                                            shape = RoundedCornerShape(10.dp),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = if (subTabDeudas == 0) Color(0xFF22C55E) else Color(0xFFEF4444)
                                            ),
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                        ) {
                                            Text(
                                                if (subTabDeudas == 0) "Cobré" else "Pagué",
                                                fontWeight = FontWeight.Bold,
                                                color = Color.White,
                                                fontSize = 12.sp
                                            )
                                        }
                                    }
                                    Spacer(Modifier.width(4.dp))
                                    IconButton(
                                        onClick = { viewModel.eliminarDeudaPrestamo(item.id) },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = "Eliminar",
                                            tint = Color(0xFF64748B),
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }

                            Spacer(Modifier.height(10.dp))

                            // Desglose de Montos y Vencimiento
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = Color(0xFF0F1A2B),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp).fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(
                                            "Monto total ${if (subTabDeudas == 0) "a cobrar" else "a pagar"}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color(0xFF8E9FB8),
                                            fontSize = 10.sp
                                        )
                                        Text(
                                            format.format(item.montoTotalFinal),
                                            style = MaterialTheme.typography.titleLarge,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = if (subTabDeudas == 0) Color(0xFF76D1FF) else Color(0xFFFCA5A5)
                                        )
                                        val detalleInteres = when (item.tipoInteres) {
                                            DeudaPrestamo.INTERES_PORCENTAJE -> "Capital ${format.format(item.montoBase)} + ${item.valorInteres.toInt()}% interés (+${format.format(item.montoInteresCalculado)})"
                                            DeudaPrestamo.INTERES_MONTO_FIJO -> "Capital ${format.format(item.montoBase)} + interés fijo +${format.format(item.valorInteres)}"
                                            else -> "Capital ${format.format(item.montoBase)} (Sin interés)"
                                        }
                                        Text(
                                            detalleInteres,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color(0xFF94A3B8),
                                            fontSize = 10.sp
                                        )
                                    }

                                    Column(horizontalAlignment = Alignment.End) {
                                        Text(
                                            "Fecha esperada",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color(0xFF8E9FB8),
                                            fontSize = 10.sp
                                        )
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                Icons.Default.CalendarMonth,
                                                contentDescription = null,
                                                tint = Color(0xFF38BDF8),
                                                modifier = Modifier.size(13.dp)
                                            )
                                            Spacer(Modifier.width(3.dp))
                                            Text(
                                                fechaLimiteStr,
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.SemiBold,
                                                color = Color.White,
                                                fontSize = 11.sp
                                            )
                                        }
                                        if (diasRestantes != null && !esCompletado) {
                                            val badgeColor = when {
                                                diasRestantes < 0 -> Color(0xFFEF4444)
                                                diasRestantes == 0 -> Color(0xFFF59E0B)
                                                else -> Color(0xFF34D399)
                                            }
                                            val textoDias = when {
                                                diasRestantes < 0 -> "Venció hace ${-diasRestantes}d"
                                                diasRestantes == 0 -> "¡Vence hoy!"
                                                else -> "En $diasRestantes días"
                                            }
                                            Text(
                                                textoDias,
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = badgeColor,
                                                fontSize = 10.sp
                                            )
                                        }
                                    }
                                }
                            }

                            if (item.nota.isNotBlank()) {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "Nota: ${item.nota}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFF8E9FB8),
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                }

                // Si hay deudas legacy de porCobrar (gastos divididos antiguos)
                if (subTabDeudas == 0 && porCobrar.isNotEmpty()) {
                    item {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "Gastos compartidos por cobrar (anteriores):",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF76D1FF),
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                    items(porCobrar, key = { it.id }) { item ->
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF131F33)),
                            border = BorderStroke(1.dp, Color(0xFF1E2E48).copy(alpha = 0.5f))
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp).fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        item.deudor.ifBlank { "Amigo" },
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                    Text(
                                        "Te debe: ${format.format(item.montoPorCobrar)}",
                                        color = Color(0xFF76D1FF),
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        "Concepto: ${item.categoria}${if(item.nota.isNotBlank()) " — ${item.nota}" else ""}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color(0xFF8E9FB8)
                                    )
                                }
                                Surface(
                                    onClick = { viewModel.marcarCuentaCobrada(item.id) },
                                    shape = RoundedCornerShape(10.dp),
                                    color = Color(0xFF22C55E)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            Icons.Default.CheckCircle,
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier.size(15.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            "Cobré",
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White,
                                            fontSize = 12.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Dialog editar cuenta
    cuentaEditando?.let { c ->
        AlertDialog(
            onDismissRequest = { cuentaEditando = null },
            title = { Text("Editar cuenta") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = nombreEdit,
                        onValueChange = { nombreEdit = it },
                        label = { Text("Nombre de la cuenta") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = saldoEdit,
                        onValueChange = { saldoEdit = it },
                        label = { Text("Saldo actual (S/)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    val nuevoSaldo = saldoEdit.toDoubleOrNull() ?: c.saldo
                    viewModel.editarCuenta(c.copy(nombre = nombreEdit.trim(), saldo = nuevoSaldo))
                    cuentaEditando = null
                }) { Text("Guardar") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        viewModel.eliminarCuenta(c)
                        cuentaEditando = null
                    }) {
                        Text("Eliminar", color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = { cuentaEditando = null }) { Text("Cancelar") }
                }
            }
        )
    }

    // Dialog crear cuenta nueva con sugerencias peruanas
    if (showNuevaCuentaModal) {
        var nuevoNombre by remember { mutableStateOf("") }
        var nuevoSaldoStr by remember { mutableStateOf("") }
        // Secciones: "Efectivo", "Banco", "Ahorro"
        var seccionSeleccionada by remember { mutableStateOf("Banco") }

        val sugerenciasEfectivo = listOf("Efectivo Billetera", "Caja Chica", "Efectivo Casa", "Monedero")
        val sugerenciasBancos = listOf(
            "BCP / Yape", "Interbank / Plin", "BBVA", "Scotiabank", "Banco de la Nación",
            "Caja Arequipa", "Caja Huancayo", "Caja Piura", "Caja Cusco",
            "Cooperativa Pacífico", "Cooperativa Abaco"
        )
        val sugerenciasAhorro = listOf(
            "Bolsillo Ahorro", "Warda (BCP)", "Alcancía Interbank", "Plazo Fijo", "Fondos Mutuos", "Ahorro Casa"
        )

        AlertDialog(
            onDismissRequest = { showNuevaCuentaModal = false },
            title = { Text("Nueva Cuenta") },
            text = {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    item {
                        Text("Tipo de cuenta:", style = MaterialTheme.typography.labelSmall)
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            FilterChip(
                                selected = seccionSeleccionada == "Efectivo",
                                onClick = {
                                    seccionSeleccionada = "Efectivo"
                                    if (nuevoNombre.isBlank()) nuevoNombre = "Efectivo"
                                },
                                label = { Text("💵 Efectivo") },
                                modifier = Modifier.weight(1f)
                            )
                            FilterChip(
                                selected = seccionSeleccionada == "Banco",
                                onClick = { seccionSeleccionada = "Banco" },
                                label = { Text("🏦 Banco") },
                                modifier = Modifier.weight(1f)
                            )
                            FilterChip(
                                selected = seccionSeleccionada == "Ahorro",
                                onClick = { seccionSeleccionada = "Ahorro" },
                                label = { Text("🐷 Ahorro") },
                                modifier = Modifier.weight(1f)
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = nuevoNombre,
                            onValueChange = { nuevoNombre = it },
                            label = { Text("Nombre de la cuenta o entidad") },
                            placeholder = {
                                Text(
                                    when (seccionSeleccionada) {
                                        "Efectivo" -> "Ej: Efectivo, Billetera personal"
                                        "Ahorro" -> "Ej: Bolsillo de Emergencia, Plazo Fijo"
                                        else -> "Ej: BCP, Interbank, Caja Piura, Yape"
                                    }
                                )
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(6.dp))
                        Text("Sugerencias:", style = MaterialTheme.typography.labelSmall)
                        Row(modifier = Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp)) {
                            val listaSugerida = when (seccionSeleccionada) {
                                "Efectivo" -> sugerenciasEfectivo
                                "Ahorro" -> sugerenciasAhorro
                                else -> sugerenciasBancos
                            }
                            listaSugerida.forEach { sug ->
                                SuggestionChip(
                                    onClick = { nuevoNombre = sug },
                                    label = { Text(sug, style = MaterialTheme.typography.labelSmall) },
                                    modifier = Modifier.padding(end = 4.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = nuevoSaldoStr,
                            onValueChange = { nuevoSaldoStr = it },
                            label = { Text("Saldo inicial (S/)") },
                            placeholder = { Text("0.00") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (nuevoNombre.isNotBlank()) {
                            val saldo = nuevoSaldoStr.toDoubleOrNull() ?: 0.0
                            val tipoFinal = when (seccionSeleccionada) {
                                "Ahorro" -> Cuenta.TIPO_AHORRO
                                else -> Cuenta.TIPO_DISPONIBLE
                            }
                            viewModel.crearCuenta(nuevoNombre.trim(), tipoFinal, saldo)
                            showNuevaCuentaModal = false
                        }
                    }
                ) { Text("Crear") }
            },
            dismissButton = {
                TextButton(onClick = { showNuevaCuentaModal = false }) { Text("Cancelar") }
            }
        )
    }

    if (showTransferenciaModal) {
        DialogTransferencia(
            cuentas = cuentas,
            onTransferir = { origen, dest, monto, nota ->
                viewModel.realizarTransferencia(origen, dest, monto, nota)
                showTransferenciaModal = false
            },
            onCerrar = { showTransferenciaModal = false }
        )
    }

    if (showNuevaDeudaModal) {
        DialogNuevaDeudaPrestamo(
            tipoInicial = subTabDeudas,
            cuentas = cuentas,
            onCrear = { persona, tipo, montoBase, fechaReg, tipoInt, valInt, fechaLim, cId, nota, afectarSaldo ->
                viewModel.crearDeudaPrestamo(
                    persona = persona,
                    tipo = tipo,
                    montoBase = montoBase,
                    fechaRegistro = fechaReg,
                    tipoInteres = tipoInt,
                    valorInteres = valInt,
                    fechaLimitePago = fechaLim,
                    cuentaId = cId,
                    nota = nota,
                    afectarSaldoInicial = afectarSaldo
                )
                showNuevaDeudaModal = false
            },
            onDismiss = { showNuevaDeudaModal = false },
            format = format
        )
    }
}

// -------------------------------------------------------------------------
// DIALOG: REGISTRAR PRÉSTAMO O DEUDA (NOS DEBEN / DEBEMOS)
// -------------------------------------------------------------------------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DialogNuevaDeudaPrestamo(
    tipoInicial: Int = DeudaPrestamo.TIPO_POR_COBRAR,
    cuentas: List<Cuenta>,
    onCrear: (
        persona: String,
        tipo: Int,
        montoBase: Double,
        fechaRegistro: Long,
        tipoInteres: Int,
        valorInteres: Double,
        fechaLimitePago: Long,
        cuentaId: String,
        nota: String,
        afectarSaldoInicial: Boolean
    ) -> Unit,
    onDismiss: () -> Unit,
    format: NumberFormat
) {
    var tipo by remember { mutableIntStateOf(tipoInicial) }
    var persona by remember { mutableStateOf("") }
    var montoBaseStr by remember { mutableStateOf("") }
    val montoBase = montoBaseStr.toDoubleOrNull() ?: 0.0

    // Fecha en que dimos o recibimos el dinero
    var diasAtrasRegistro by remember { mutableIntStateOf(0) }
    val fechaRegistroCalculada = remember(diasAtrasRegistro) {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -diasAtrasRegistro)
        cal.timeInMillis
    }
    val fechaRegistroStr = remember(fechaRegistroCalculada) {
        SimpleDateFormat("dd 'de' MMMM, yyyy", Locale("es", "PE")).format(Date(fechaRegistroCalculada))
    }

    // Interés opcional: Ninguno, % Porcentaje, S/ Monto Fijo
    var tipoInteres by remember { mutableIntStateOf(DeudaPrestamo.INTERES_NINGUNO) }
    var valorInteresStr by remember { mutableStateOf("") }
    val valorInteres = valorInteresStr.toDoubleOrNull() ?: 0.0

    val interesCalculado = when (tipoInteres) {
        DeudaPrestamo.INTERES_PORCENTAJE -> montoBase * (valorInteres / 100.0)
        DeudaPrestamo.INTERES_MONTO_FIJO -> valorInteres
        else -> 0.0
    }
    val totalFinalCalculado = montoBase + interesCalculado

    // Posible fecha de pago / vencimiento (para agendar en el calendario)
    // Presets: 0: Sin fecha, 1: +7 días, 2: +15 días, 3: Fin de mes, 4: +30 días
    var presetFechaLimite by remember { mutableIntStateOf(2) } // default +15 días
    val fechaLimiteCalculada = remember(presetFechaLimite) {
        val cal = Calendar.getInstance()
        when (presetFechaLimite) {
            0 -> 0L
            1 -> { cal.add(Calendar.DAY_OF_YEAR, 7); cal.timeInMillis }
            2 -> { cal.add(Calendar.DAY_OF_YEAR, 15); cal.timeInMillis }
            3 -> {
                val maxDay = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
                cal.set(Calendar.DAY_OF_MONTH, maxDay)
                cal.timeInMillis
            }
            4 -> { cal.add(Calendar.DAY_OF_YEAR, 30); cal.timeInMillis }
            else -> 0L
        }
    }
    val fechaLimiteStr = remember(fechaLimiteCalculada) {
        if (fechaLimiteCalculada > 0L) {
            SimpleDateFormat("EEEE dd 'de' MMMM", Locale("es", "PE")).format(Date(fechaLimiteCalculada)).replaceFirstChar { it.uppercase() }
        } else "Sin fecha fija de cobro/pago"
    }

    // Cuenta y afectación de saldo
    var cuentaSeleccionadaId by remember {
        mutableStateOf(cuentas.firstOrNull()?.id ?: "cuenta_bcp_yape")
    }
    var afectarSaldoInicial by remember { mutableStateOf(false) }
    var nota by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (tipo == DeudaPrestamo.TIPO_POR_COBRAR) "Registrar Préstamo Otorgado" else "Registrar Deuda Contraída",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Selector de Tipo: Me deben vs Debo
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFF0F1A2B),
                        border = BorderStroke(1.dp, Color(0xFF1B2B42))
                    ) {
                        Row(modifier = Modifier.padding(2.dp)) {
                            Surface(
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp),
                                color = if (tipo == DeudaPrestamo.TIPO_POR_COBRAR) Color(0xFF0284C7) else Color.Transparent,
                                onClick = { tipo = DeudaPrestamo.TIPO_POR_COBRAR }
                            ) {
                                Text(
                                    "📥 Me deben (Presté)",
                                    textAlign = TextAlign.Center,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (tipo == DeudaPrestamo.TIPO_POR_COBRAR) Color.White else Color(0xFF8E9FB8),
                                    modifier = Modifier.padding(vertical = 8.dp)
                                )
                            }
                            Surface(
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp),
                                color = if (tipo == DeudaPrestamo.TIPO_POR_PAGAR) Color(0xFFDC2626) else Color.Transparent,
                                onClick = { tipo = DeudaPrestamo.TIPO_POR_PAGAR }
                            ) {
                                Text(
                                    "📤 Debo (Me prestaron)",
                                    textAlign = TextAlign.Center,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (tipo == DeudaPrestamo.TIPO_POR_PAGAR) Color.White else Color(0xFF8E9FB8),
                                    modifier = Modifier.padding(vertical = 8.dp)
                                )
                            }
                        }
                    }
                }

                // Persona / Contacto
                item {
                    OutlinedTextField(
                        value = persona,
                        onValueChange = { persona = it },
                        label = { Text(if (tipo == DeudaPrestamo.TIPO_POR_COBRAR) "¿Quién te debe? (Persona o contacto)" else "¿A quién le debes? (Prestamista o entidad)") },
                        placeholder = { Text("Ej: Carlos Gómez, Tía Rosa, Banco...") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Monto Base inicial
                item {
                    OutlinedTextField(
                        value = montoBaseStr,
                        onValueChange = { montoBaseStr = it },
                        label = { Text(if (tipo == DeudaPrestamo.TIPO_POR_COBRAR) "Monto prestado (S/)" else "Monto recibido (S/)") },
                        placeholder = { Text("0.00") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Fecha en que dimos o recibimos el dinero
                item {
                    Text(
                        if (tipo == DeudaPrestamo.TIPO_POR_COBRAR) "Fecha en que diste el dinero:" else "Fecha en que recibiste el dinero:",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF8E9FB8)
                    )
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(0 to "Hoy", 1 to "Ayer", 3 to "Hace 3 días", 7 to "Hace 1 sem", 15 to "Hace 15d").forEach { (dias, tag) ->
                            FilterChip(
                                selected = diasAtrasRegistro == dias,
                                onClick = { diasAtrasRegistro = dias },
                                label = { Text(tag, fontSize = 11.sp) }
                            )
                        }
                    }
                    Text(
                        "📅 $fechaRegistroStr",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF38BDF8),
                        fontSize = 11.sp
                    )
                }

                // Interés Opcional (% o Monto Fijo)
                item {
                    Text(
                        "Interés (Opcional):",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF8E9FB8)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        FilterChip(
                            selected = tipoInteres == DeudaPrestamo.INTERES_NINGUNO,
                            onClick = { tipoInteres = DeudaPrestamo.INTERES_NINGUNO; valorInteresStr = "" },
                            label = { Text("Sin interés", fontSize = 11.sp) },
                            modifier = Modifier.weight(1f)
                        )
                        FilterChip(
                            selected = tipoInteres == DeudaPrestamo.INTERES_PORCENTAJE,
                            onClick = {
                                tipoInteres = DeudaPrestamo.INTERES_PORCENTAJE
                                if (valorInteresStr.isBlank()) valorInteresStr = "10"
                            },
                            label = { Text("% Porcentaje", fontSize = 11.sp) },
                            modifier = Modifier.weight(1.1f)
                        )
                        FilterChip(
                            selected = tipoInteres == DeudaPrestamo.INTERES_MONTO_FIJO,
                            onClick = {
                                tipoInteres = DeudaPrestamo.INTERES_MONTO_FIJO
                                if (valorInteresStr.isBlank()) valorInteresStr = "20"
                            },
                            label = { Text("S/ Monto Fijo", fontSize = 11.sp) },
                            modifier = Modifier.weight(1.1f)
                        )
                    }

                    if (tipoInteres == DeudaPrestamo.INTERES_PORCENTAJE) {
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            listOf("5", "10", "15", "20", "25").forEach { pct ->
                                SuggestionChip(
                                    onClick = { valorInteresStr = pct },
                                    label = { Text("$pct%", fontSize = 11.sp) }
                                )
                            }
                        }
                        OutlinedTextField(
                            value = valorInteresStr,
                            onValueChange = { valorInteresStr = it },
                            label = { Text("Tasa de interés (%)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else if (tipoInteres == DeudaPrestamo.INTERES_MONTO_FIJO) {
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            listOf("10", "20", "50", "100").forEach { monto ->
                                SuggestionChip(
                                    onClick = { valorInteresStr = monto },
                                    label = { Text("S/ $monto", fontSize = 11.sp) }
                                )
                            }
                        }
                        OutlinedTextField(
                            value = valorInteresStr,
                            onValueChange = { valorInteresStr = it },
                            label = { Text("Monto de interés fijo (S/)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    // Resumen en vivo de Base + Interés = Total
                    if (montoBase > 0) {
                        Spacer(Modifier.height(4.dp))
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = Color(0xFF14243B),
                            border = BorderStroke(1.dp, Color(0xFF233F66)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("Capital:", fontSize = 11.sp, color = Color(0xFF8E9FB8))
                                    Text(format.format(montoBase), fontSize = 11.sp, color = Color.White)
                                }
                                if (interesCalculado > 0) {
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(
                                            "Interés (${if (tipoInteres == DeudaPrestamo.INTERES_PORCENTAJE) "$valorInteres%" else "Fijo"}):",
                                            fontSize = 11.sp,
                                            color = Color(0xFF34D399)
                                        )
                                        Text("+${format.format(interesCalculado)}", fontSize = 11.sp, color = Color(0xFF34D399), fontWeight = FontWeight.Bold)
                                    }
                                }
                                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = Color(0xFF233F66))
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("Total a ${if (tipo == DeudaPrestamo.TIPO_POR_COBRAR) "cobrar" else "pagar"}:", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF76D1FF))
                                    Text(format.format(totalFinalCalculado), fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF76D1FF))
                                }
                            }
                        }
                    }
                }

                // Posible fecha de pago / Vencimiento (para Calendario)
                item {
                    Text(
                        "Posible fecha de pago (Se incluirá en tu Calendario):",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF8E9FB8)
                    )
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(
                            1 to "+7 días",
                            2 to "+15 días",
                            3 to "Fin de mes",
                            4 to "+30 días",
                            0 to "Sin fecha"
                        ).forEach { (preset, tag) ->
                            FilterChip(
                                selected = presetFechaLimite == preset,
                                onClick = { presetFechaLimite = preset },
                                label = { Text(tag, fontSize = 11.sp) }
                            )
                        }
                    }
                    Text(
                        "🗓️ $fechaLimiteStr",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (fechaLimiteCalculada > 0) Color(0xFF34D399) else Color(0xFF8E9FB8),
                        fontSize = 11.sp
                    )
                }

                // Cuenta asociada
                item {
                    Text("Cuenta para debitar / abonar:", style = MaterialTheme.typography.labelSmall, color = Color(0xFF8E9FB8))
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        cuentas.forEach { c ->
                            FilterChip(
                                selected = cuentaSeleccionadaId == c.id,
                                onClick = { cuentaSeleccionadaId = c.id },
                                label = { Text("${c.nombre} (${format.format(c.saldo)})", fontSize = 11.sp) }
                            )
                        }
                    }

                    // Checkbox para registrar movimiento de salida/entrada ahora
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = afectarSaldoInicial,
                            onCheckedChange = { afectarSaldoInicial = it }
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            if (tipo == DeudaPrestamo.TIPO_POR_COBRAR)
                                "¿Descontar ${format.format(montoBase)} de esta cuenta ahora?"
                            else
                                "¿Sumar ${format.format(montoBase)} a esta cuenta ahora?",
                            fontSize = 11.sp,
                            color = Color(0xFF94A3B8)
                        )
                    }
                }

                // Nota adicional
                item {
                    OutlinedTextField(
                        value = nota,
                        onValueChange = { nota = it },
                        label = { Text("Nota o concepto (opcional)") },
                        placeholder = { Text("Ej: Préstamo para alquiler, cena...") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (persona.isNotBlank() && montoBase > 0) {
                        onCrear(
                            persona.trim(),
                            tipo,
                            montoBase,
                            fechaRegistroCalculada,
                            tipoInteres,
                            valorInteres,
                            fechaLimiteCalculada,
                            cuentaSeleccionadaId,
                            nota.trim(),
                            afectarSaldoInicial
                        )
                    }
                },
                enabled = persona.isNotBlank() && montoBase > 0,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (tipo == DeudaPrestamo.TIPO_POR_COBRAR) Color(0xFF0284C7) else Color(0xFFDC2626)
                )
            ) {
                Text(if (tipo == DeudaPrestamo.TIPO_POR_COBRAR) "Guardar Préstamo" else "Guardar Deuda")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        }
    )
}

// -------------------------------------------------------------------------
// -------------------------------------------------------------------------
// COMPONENTE: CALENDARIO FINANCIERO DEL MES (INGRESOS, GASTOS FIJOS Y DEUDAS/PRÉSTAMOS)
// -------------------------------------------------------------------------
@Composable
fun CalendarioFinancieroDelMes(
    anio: Int,
    mesIndex: Int,
    mesNombre: String,
    mesActualStr: String,
    ingresos: List<IngresoProgramado>,
    pagosFijos: List<PagoFijo>,
    deudas: List<DeudaPrestamo> = emptyList(),
    diaSeleccionado: Int?,
    onDiaSeleccionado: (Int) -> Unit,
    onCobrarFecha: (IngresoProgramado, Int, String) -> Unit,
    onPagarGastoFijo: (PagoFijo) -> Unit,
    onLiquidarDeuda: (DeudaPrestamo) -> Unit = {},
    format: NumberFormat
) {
    val cal = remember(anio, mesIndex) {
        Calendar.getInstance().apply {
            set(Calendar.YEAR, anio)
            set(Calendar.MONTH, mesIndex)
            set(Calendar.DAY_OF_MONTH, 1)
        }
    }
    val maxDiasMes = remember(anio, mesIndex) { cal.getActualMaximum(Calendar.DAY_OF_MONTH) }
    // En Java Calendar: Domingo = 1, Lunes = 2... Sábado = 7
    // Convertimos a: Lunes = 0, Martes = 1, ..., Domingo = 6
    val primerDiaSemana = remember(anio, mesIndex) {
        val raw = cal.get(Calendar.DAY_OF_WEEK)
        (raw + 5) % 7
    }

    val calHoy = remember { Calendar.getInstance() }
    val esEsteMes = calHoy.get(Calendar.YEAR) == anio && calHoy.get(Calendar.MONTH) == mesIndex
    val diaHoy = if (esEsteMes) calHoy.get(Calendar.DAY_OF_MONTH) else -1

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF101B2C)),
        border = BorderStroke(1.dp, Color(0xFF1E2F48))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Header del calendario
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("🗓️", fontSize = 16.sp)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Calendario del Mes • $mesNombre",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(7.dp).background(Color(0xFF34D399), CircleShape))
                        Spacer(Modifier.width(3.dp))
                        Text("Ingreso/Cobro", fontSize = 9.sp, color = Color(0xFF8E9FB8))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(7.dp).background(Color(0xFFF87171), CircleShape))
                        Spacer(Modifier.width(3.dp))
                        Text("Gasto/Deuda", fontSize = 9.sp, color = Color(0xFF8E9FB8))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(7.dp).border(1.dp, Color(0xFFFBBF24), CircleShape))
                        Spacer(Modifier.width(3.dp))
                        Text("Hoy", fontSize = 9.sp, color = Color(0xFF8E9FB8))
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // Días de la semana
            val diasSemana = listOf("Lun", "Mar", "Mié", "Jue", "Vie", "Sáb", "Dom")
            Row(modifier = Modifier.fillMaxWidth()) {
                diasSemana.forEach { dNom ->
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        Text(
                            dNom,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF70B4E8)
                        )
                    }
                }
            }

            Spacer(Modifier.height(6.dp))

            // Grilla de días
            val totalCeldas = primerDiaSemana + maxDiasMes
            val filas = (totalCeldas + 6) / 7

            for (fila in 0 until filas) {
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    for (col in 0..6) {
                        val celdaIndex = fila * 7 + col
                        val dia = celdaIndex - primerDiaSemana + 1

                        if (dia in 1..maxDiasMes) {
                            val fKey = String.format(Locale.US, "%04d-%02d-%02d", anio, mesIndex + 1, dia)

                            val ingresosDelDia = ingresos.filter { ing ->
                                GastosViewModel.obtenerDiasCobroMes(ing, anio, mesIndex).contains(dia)
                            }
                            val tieneIngreso = ingresosDelDia.isNotEmpty()
                            val todosIngresosCobrados = tieneIngreso && ingresosDelDia.all { ing ->
                                GastosViewModel.estaFechaCobrada(ing, fKey) != null
                            }

                            val pagosFijosDelDia = pagosFijos.filter { it.diaPago == dia }
                            val tieneGastoFijo = pagosFijosDelDia.isNotEmpty()
                            val todosGastosPagados = tieneGastoFijo && pagosFijosDelDia.all { it.ultimoMesPagado == mesActualStr }

                            val deudasDelDia = deudas.filter { GastosViewModel.coincideFechaDeuda(it.fechaLimitePago, anio, mesIndex, dia) }
                            val cobrosPrestamoDelDia = deudasDelDia.filter { it.tipo == DeudaPrestamo.TIPO_POR_COBRAR }
                            val pagosDeudaDelDia = deudasDelDia.filter { it.tipo == DeudaPrestamo.TIPO_POR_PAGAR }

                            val tieneCobroPrestamo = cobrosPrestamoDelDia.isNotEmpty()
                            val tienePagoDeuda = pagosDeudaDelDia.isNotEmpty()
                            val todosCobrosPrestamoCompletados = tieneCobroPrestamo && cobrosPrestamoDelDia.all { it.estado == DeudaPrestamo.ESTADO_COMPLETADO }
                            val todosPagosDeudaCompletados = tienePagoDeuda && pagosDeudaDelDia.all { it.estado == DeudaPrestamo.ESTADO_COMPLETADO }

                            val tieneEntrada = tieneIngreso || tieneCobroPrestamo
                            val todasEntradasCompletadas = tieneEntrada &&
                                    (if (tieneIngreso) todosIngresosCobrados else true) &&
                                    (if (tieneCobroPrestamo) todosCobrosPrestamoCompletados else true)

                            val tieneSalida = tieneGastoFijo || tienePagoDeuda
                            val todasSalidasCompletadas = tieneSalida &&
                                    (if (tieneGastoFijo) todosGastosPagados else true) &&
                                    (if (tienePagoDeuda) todosPagosDeudaCompletados else true)

                            val esHoy = dia == diaHoy
                            val estaSeleccionado = dia == diaSeleccionado

                            val cellBg = when {
                                estaSeleccionado -> Color(0xFF253E66)
                                tieneEntrada && tieneSalida -> Color(0xFF1E2338)
                                tieneEntrada && todasEntradasCompletadas -> Color(0xFF10332D)
                                tieneEntrada -> Color(0xFF162A43)
                                tieneSalida && todasSalidasCompletadas -> Color(0xFF182230)
                                tieneSalida -> Color(0xFF2C1C24)
                                esHoy -> Color(0xFF1A2638)
                                else -> Color.Transparent
                            }

                            val cellBorder = when {
                                estaSeleccionado -> Modifier.border(1.dp, Color(0xFF38BDF8), RoundedCornerShape(8.dp))
                                esHoy -> Modifier.border(1.dp, Color(0xFFFBBF24), RoundedCornerShape(8.dp))
                                else -> Modifier
                            }

                            val dayTextColor = when {
                                estaSeleccionado -> Color.White
                                esHoy -> Color(0xFFFBBF24)
                                tieneEntrada && tieneSalida -> Color.White
                                tieneEntrada -> if (todasEntradasCompletadas) Color(0xFF34D399) else Color(0xFF76D1FF)
                                tieneSalida -> if (todasSalidasCompletadas) Color(0xFF8E9FB8) else Color(0xFFFCA5A5)
                                else -> Color(0xFF8E9FB8)
                            }

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .aspectRatio(1f)
                                    .padding(2.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(cellBg)
                                    .then(cellBorder)
                                    .clickable { onDiaSeleccionado(dia) },
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = dia.toString(),
                                        fontSize = 11.sp,
                                        fontWeight = if (tieneEntrada || tieneSalida || esHoy) FontWeight.Bold else FontWeight.Normal,
                                        color = dayTextColor
                                    )
                                    // Puntos indicadores
                                    if (tieneEntrada || tieneSalida) {
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.padding(top = 1.dp)
                                        ) {
                                            if (tieneEntrada) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(4.dp)
                                                        .background(
                                                            if (todasEntradasCompletadas) Color(0xFF34D399) else Color(0xFF38BDF8),
                                                            CircleShape
                                                        )
                                                )
                                            }
                                            if (tieneSalida) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(4.dp)
                                                        .background(
                                                            if (todasSalidasCompletadas) Color(0xFF64748B) else Color(0xFFF87171),
                                                            CircleShape
                                                        )
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            Box(modifier = Modifier.weight(1f).aspectRatio(1f))
                        }
                    }
                }
            }

            // Panel de detalle interactivo para el día seleccionado o próximo día con eventos
            val diaParaMostrar = diaSeleccionado ?: run {
                val diasConEvento = mutableSetOf<Int>()
                ingresos.forEach { diasConEvento.addAll(GastosViewModel.obtenerDiasCobroMes(it, anio, mesIndex)) }
                pagosFijos.forEach { diasConEvento.add(it.diaPago) }
                deudas.forEach { d ->
                    if (d.fechaLimitePago > 0L) {
                        val c = Calendar.getInstance().apply { timeInMillis = d.fechaLimitePago }
                        if (c.get(Calendar.YEAR) == anio && c.get(Calendar.MONTH) == mesIndex) {
                            diasConEvento.add(c.get(Calendar.DAY_OF_MONTH))
                        }
                    }
                }

                if (esEsteMes && diasConEvento.contains(diaHoy)) {
                    diaHoy
                } else {
                    val futuros = diasConEvento.filter { it >= diaHoy }.sorted()
                    futuros.firstOrNull() ?: diasConEvento.sorted().firstOrNull()
                }
            }

            diaParaMostrar?.let { dia ->
                val fKey = String.format(Locale.US, "%04d-%02d-%02d", anio, mesIndex + 1, dia)
                val ingresosEnEseDia = ingresos.filter { ing ->
                    GastosViewModel.obtenerDiasCobroMes(ing, anio, mesIndex).contains(dia)
                }
                val pagosFijosEnEseDia = pagosFijos.filter { it.diaPago == dia }
                val deudasEnEseDia = deudas.filter { GastosViewModel.coincideFechaDeuda(it.fechaLimitePago, anio, mesIndex, dia) }
                val cobrosPrestamoEnEseDia = deudasEnEseDia.filter { it.tipo == DeudaPrestamo.TIPO_POR_COBRAR }
                val pagosDeudaEnEseDia = deudasEnEseDia.filter { it.tipo == DeudaPrestamo.TIPO_POR_PAGAR }

                val hayMovimientos = ingresosEnEseDia.isNotEmpty() || pagosFijosEnEseDia.isNotEmpty() || deudasEnEseDia.isNotEmpty()

                if (hayMovimientos) {
                    val totalIngDia = ingresosEnEseDia.sumOf { ing -> GastosViewModel.estaFechaCobrada(ing, fKey) ?: ing.montoEsperado } +
                            cobrosPrestamoEnEseDia.sumOf { it.montoTotalFinal }
                    val totalGastoDia = pagosFijosEnEseDia.sumOf { it.monto } +
                            pagosDeudaEnEseDia.sumOf { it.montoTotalFinal }
                    val balanceDia = totalIngDia - totalGastoDia

                    Spacer(Modifier.height(10.dp))
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = Color(0xFF16253B),
                        border = BorderStroke(1.dp, Color(0xFF223A5B)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "Día $dia de $mesNombre:",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                if (totalIngDia > 0 && totalGastoDia > 0) {
                                    Surface(
                                        shape = RoundedCornerShape(50),
                                        color = if (balanceDia >= 0) Color(0xFF10332D) else Color(0xFF2E1A22)
                                    ) {
                                        Text(
                                            "Flujo neto: ${if (balanceDia >= 0) "+" else ""}${format.format(balanceDia)}",
                                            color = if (balanceDia >= 0) Color(0xFF34D399) else Color(0xFFF87171),
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 10.sp,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                        )
                                    }
                                }
                            }

                            // 1. INGRESOS PROGRAMADOS EN ESTE DÍA
                            if (ingresosEnEseDia.isNotEmpty()) {
                                Spacer(Modifier.height(8.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(6.dp).background(Color(0xFF34D399), CircleShape))
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        "INGRESOS PROGRAMADOS",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 9.sp,
                                        color = Color(0xFF34D399)
                                    )
                                }
                                Spacer(Modifier.height(4.dp))
                                ingresosEnEseDia.forEach { ing ->
                                    val cobrado = GastosViewModel.estaFechaCobrada(ing, fKey)
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(ing.nombre, fontWeight = FontWeight.Bold, color = Color.White, fontSize = 13.sp)
                                            Text(
                                                if (cobrado != null) "Cobrado: ${format.format(cobrado)} ✓"
                                                else "Proyectado: ${format.format(ing.montoEsperado)}",
                                                fontSize = 11.sp,
                                                color = if (cobrado != null) Color(0xFF34D399) else Color(0xFF8E9FB8)
                                            )
                                        }
                                        if (cobrado != null) {
                                            Surface(
                                                shape = RoundedCornerShape(50),
                                                color = Color(0xFF10332D)
                                            ) {
                                                Text(
                                                    "Cobrado ✓",
                                                    color = Color(0xFF34D399),
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 11.sp,
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                                )
                                            }
                                        } else {
                                            Surface(
                                                onClick = { onCobrarFecha(ing, dia, fKey) },
                                                shape = RoundedCornerShape(8.dp),
                                                color = Color(0xFF34D399)
                                            ) {
                                                Text(
                                                    "Cobrar",
                                                    color = Color(0xFF0F1A2A),
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 11.sp,
                                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            // 2. GASTOS FIJOS EN ESTE DÍA
                            if (pagosFijosEnEseDia.isNotEmpty()) {
                                Spacer(Modifier.height(8.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(6.dp).background(Color(0xFFF87171), CircleShape))
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        "GASTOS FIJOS / COMPROMISOS",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 9.sp,
                                        color = Color(0xFFF87171)
                                    )
                                }
                                Spacer(Modifier.height(4.dp))
                                pagosFijosEnEseDia.forEach { pago ->
                                    val pagado = pago.ultimoMesPagado == mesActualStr
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(pago.nombre, fontWeight = FontWeight.Bold, color = Color.White, fontSize = 13.sp)
                                            Text(
                                                "Monto: ${format.format(pago.monto)} • ${pago.categoria}",
                                                fontSize = 11.sp,
                                                color = if (pagado) Color(0xFF8E9FB8) else Color(0xFFFCA5A5)
                                            )
                                        }
                                        if (pagado) {
                                            Surface(
                                                shape = RoundedCornerShape(50),
                                                color = Color(0xFF10332D)
                                            ) {
                                                Text(
                                                    "Pagado ✓",
                                                    color = Color(0xFF34D399),
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 11.sp,
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                                )
                                            }
                                        } else {
                                            Surface(
                                                onClick = { onPagarGastoFijo(pago) },
                                                shape = RoundedCornerShape(8.dp),
                                                color = Color(0xFFF87171)
                                            ) {
                                                Text(
                                                    "Pagar",
                                                    color = Color.White,
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 11.sp,
                                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            // 3. PRÉSTAMOS POR COBRAR (NOS DEBEN) EN ESTE DÍA
                            if (cobrosPrestamoEnEseDia.isNotEmpty()) {
                                Spacer(Modifier.height(8.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(6.dp).background(Color(0xFF38BDF8), CircleShape))
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        "PRÉSTAMOS POR COBRAR (TE DEBEN)",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 9.sp,
                                        color = Color(0xFF38BDF8)
                                    )
                                }
                                Spacer(Modifier.height(4.dp))
                                cobrosPrestamoEnEseDia.forEach { prestamo ->
                                    val cobrado = prestamo.estado == DeudaPrestamo.ESTADO_COMPLETADO
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(prestamo.persona, fontWeight = FontWeight.Bold, color = Color.White, fontSize = 13.sp)
                                            val detalleInteres = when (prestamo.tipoInteres) {
                                                DeudaPrestamo.INTERES_PORCENTAJE -> " (base ${format.format(prestamo.montoBase)} + ${prestamo.valorInteres.toInt()}% int)"
                                                DeudaPrestamo.INTERES_MONTO_FIJO -> " (base ${format.format(prestamo.montoBase)} + S/ ${prestamo.valorInteres.toInt()} int)"
                                                else -> ""
                                            }
                                            Text(
                                                "Total: ${format.format(prestamo.montoTotalFinal)}$detalleInteres",
                                                fontSize = 11.sp,
                                                color = if (cobrado) Color(0xFF34D399) else Color(0xFF76D1FF)
                                            )
                                            if (prestamo.nota.isNotBlank()) {
                                                Text(prestamo.nota, fontSize = 10.sp, color = Color(0xFF8E9FB8))
                                            }
                                        }
                                        if (cobrado) {
                                            Surface(
                                                shape = RoundedCornerShape(50),
                                                color = Color(0xFF10332D)
                                            ) {
                                                Text(
                                                    "Cobrado ✓",
                                                    color = Color(0xFF34D399),
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 11.sp,
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                                )
                                            }
                                        } else {
                                            Surface(
                                                onClick = { onLiquidarDeuda(prestamo) },
                                                shape = RoundedCornerShape(8.dp),
                                                color = Color(0xFF0284C7)
                                            ) {
                                                Text(
                                                    "Cobrar",
                                                    color = Color.White,
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 11.sp,
                                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            // 4. DEUDAS POR PAGAR (DEBEMOS) EN ESTE DÍA
                            if (pagosDeudaEnEseDia.isNotEmpty()) {
                                Spacer(Modifier.height(8.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(6.dp).background(Color(0xFFFB923C), CircleShape))
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        "DEUDAS POR PAGAR (DEBES)",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 9.sp,
                                        color = Color(0xFFFB923C)
                                    )
                                }
                                Spacer(Modifier.height(4.dp))
                                pagosDeudaEnEseDia.forEach { deuda ->
                                    val pagado = deuda.estado == DeudaPrestamo.ESTADO_COMPLETADO
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(deuda.persona, fontWeight = FontWeight.Bold, color = Color.White, fontSize = 13.sp)
                                            val detalleInteres = when (deuda.tipoInteres) {
                                                DeudaPrestamo.INTERES_PORCENTAJE -> " (base ${format.format(deuda.montoBase)} + ${deuda.valorInteres.toInt()}% int)"
                                                DeudaPrestamo.INTERES_MONTO_FIJO -> " (base ${format.format(deuda.montoBase)} + S/ ${deuda.valorInteres.toInt()} int)"
                                                else -> ""
                                            }
                                            Text(
                                                "Total: ${format.format(deuda.montoTotalFinal)}$detalleInteres",
                                                fontSize = 11.sp,
                                                color = if (pagado) Color(0xFF8E9FB8) else Color(0xFFFDBA74)
                                            )
                                            if (deuda.nota.isNotBlank()) {
                                                Text(deuda.nota, fontSize = 10.sp, color = Color(0xFF8E9FB8))
                                            }
                                        }
                                        if (pagado) {
                                            Surface(
                                                shape = RoundedCornerShape(50),
                                                color = Color(0xFF10332D)
                                            ) {
                                                Text(
                                                    "Pagado ✓",
                                                    color = Color(0xFF34D399),
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 11.sp,
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                                )
                                            }
                                        } else {
                                            Surface(
                                                onClick = { onLiquidarDeuda(deuda) },
                                                shape = RoundedCornerShape(8.dp),
                                                color = Color(0xFFEA580C)
                                            ) {
                                                Text(
                                                    "Pagar",
                                                    color = Color.White,
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 11.sp,
                                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else if (diaSeleccionado != null) {
                    Spacer(Modifier.height(10.dp))
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFF16253B),
                        border = BorderStroke(1.dp, Color(0xFF223A5B)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "Sin compromisos programados para el día $dia de $mesNombre.",
                            color = Color(0xFF8E9FB8),
                            fontSize = 12.sp,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            }
        }
    }
}

// -------------------------------------------------------------------------
// PESTAÑA 3: COMPROMISOS Y PRESUPUESTOS (BUDGET SCREEN - REDISEÑO STITCH)
// -------------------------------------------------------------------------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BudgetScreen(
    viewModel: GastosViewModel,
    format: NumberFormat,
    onOpenSettings: () -> Unit = {}
) {
    val pagosFijos by viewModel.pagosFijos.collectAsState()
    val topes by viewModel.topesCategorias.collectAsState()
    val gastos by viewModel.gastos.collectAsState()
    val presupuesto by viewModel.presupuesto.collectAsState()
    val ingresosProgramados by viewModel.ingresosProgramados.collectAsState()
    val deudasPrestamos by viewModel.deudasPrestamos.collectAsState()
    val cuentas by viewModel.cuentas.collectAsState()

    val ahora = remember { Calendar.getInstance() }
    val mesActualIndex = ahora.get(Calendar.MONTH)
    val anioActual = ahora.get(Calendar.YEAR)
    val mesNombre = remember { SimpleDateFormat("MMMM yyyy", Locale("es", "PE")).format(ahora.time).replaceFirstChar { it.uppercase() } }
    val mesNombreCorto = remember { SimpleDateFormat("MMM", Locale("es", "PE")).format(ahora.time) }
    val mesActualStr = remember { SimpleDateFormat("yyyy-MM", Locale.US).format(Date()) }

    var showNuevoPagoModal by remember { mutableStateOf(false) }

    // Estados para edición de pago fijo
    var pagoEditando by remember { mutableStateOf<PagoFijo?>(null) }
    var nombrePagoEdit by remember { mutableStateOf("") }
    var montoPagoEdit by remember { mutableStateOf("") }
    var diaPagoEdit by remember { mutableStateOf("") }

    // Estados para ingresos programados
    var showNuevoIngresoModal by remember { mutableStateOf(false) }
    var ingresoEditando by remember { mutableStateOf<IngresoProgramado?>(null) }
    var ingresoCobrando by remember { mutableStateOf<IngresoProgramado?>(null) }
    var fechaCobrandoStr by remember { mutableStateOf("") }
    var fechaCobrandoDia by remember { mutableIntStateOf(1) }
    var diaSeleccionadoCalendario by remember { mutableStateOf<Int?>(null) }

    var montoRealCobradoStr by remember { mutableStateOf("") }
    var notaCobroStr by remember { mutableStateOf("") }
    var cuentaDestinoCobroId by remember { mutableStateOf("cuenta_bcp_yape") }

    var nombreIngresoEdit by remember { mutableStateOf("") }
    var montoEsperadoEdit by remember { mutableStateOf("") }
    var diaCobroEdit by remember { mutableStateOf("15") }
    var diaCobro2Edit by remember { mutableStateOf("30") }
    var frecuenciaIngresoEdit by remember { mutableStateOf(0) }
    var categoriaIngresoEdit by remember { mutableStateOf("Sueldo") }
    var cuentaIngresoEdit by remember { mutableStateOf("cuenta_bcp_yape") }

    // Estado para editar tope de categoría
    var categoriaEditandoTope by remember { mutableStateOf<String?>(null) }
    var nuevoTopeStr by remember { mutableStateOf("") }

    // Gastos reales del mes actual
    val soloGastosMes = gastos.filter {
        !it.esTransferencia && !it.esIngreso && run {
            val cal = Calendar.getInstance()
            cal.time = Date(it.fecha)
            cal.get(Calendar.MONTH) == mesActualIndex && cal.get(Calendar.YEAR) == anioActual
        }
    }
    val totalGastadoMes = soloGastosMes.sumOf { it.montoRealUsuario }
    val limitePresupuesto = if (presupuesto > 0) presupuesto else (topes.values.sum().takeIf { it > 0 } ?: 1200.0)
    val pctUtilizado = if (limitePresupuesto > 0) ((totalGastadoMes / limitePresupuesto) * 100.0).coerceIn(0.0, 100.0) else 0.0
    val pctDisponible = (100.0 - pctUtilizado).coerceAtLeast(0.0)

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        // ── ENCABEZADO SUPERIOR ───────────────────────────────────────────
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFF14243A),
                        border = BorderStroke(1.dp, Color(0xFF1E3553)),
                        modifier = Modifier.size(42.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.PieChart,
                                contentDescription = null,
                                tint = Color(0xFF76D1FF),
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            "MIS GASTOS",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF70B4E8),
                            letterSpacing = 0.5.sp,
                            fontSize = 10.sp
                        )
                        Text(
                            "Presupuesto Mensual",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = CircleShape,
                        color = Color(0xFF14243A),
                        border = BorderStroke(1.dp, Color(0xFF1E3553)),
                        modifier = Modifier.size(38.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.Notifications,
                                contentDescription = "Notificaciones",
                                tint = Color(0xFF76D1FF),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    Surface(
                        onClick = onOpenSettings,
                        shape = CircleShape,
                        color = Color(0xFF16253B),
                        border = BorderStroke(1.5.dp, Color(0xFF38BDF8)),
                        modifier = Modifier.size(38.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.Person,
                                contentDescription = "Perfil y Ajustes",
                                tint = Color(0xFF76D1FF),
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
            }
        }

        // ── 1. TARJETA CONTROL GENERAL / PRESUPUESTO MENSUAL ─────────────
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF132034)),
                border = BorderStroke(1.dp, Color(0xFF1D324E))
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    // Header de la tarjeta
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .background(Color(0xFF152A42), RoundedCornerShape(10.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.AccountBalance,
                                    contentDescription = null,
                                    tint = Color(0xFF76D1FF),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    "CONTROL GENERAL",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF70B4E8),
                                    fontSize = 9.sp,
                                    letterSpacing = 0.5.sp
                                )
                                Text(
                                    "Presupuesto Mensual",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                        }
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = Color(0xFF162A43)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.CalendarMonth,
                                    contentDescription = null,
                                    tint = Color(0xFF76D1FF),
                                    modifier = Modifier.size(13.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    mesNombre,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFF76D1FF),
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Monto y porcentaje utilizado
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                format.format(totalGastadoMes),
                                fontSize = 32.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color.White
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                "Gastado de un límite de S/ ${"%,.2f".format(limitePresupuesto)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF8E9FB8),
                                fontSize = 12.sp
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(50),
                            color = Color(0xFF10332D)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .background(Color(0xFF22C55E), CircleShape)
                                )
                                Spacer(Modifier.width(6.dp))
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(
                                        "${"%.1f".format(pctUtilizado)}%",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF34D399),
                                        fontSize = 11.sp
                                    )
                                    Text(
                                        "Utilizado",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF34D399),
                                        fontSize = 9.sp
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Barra de progreso
                    LinearProgressIndicator(
                        progress = { (totalGastadoMes / limitePresupuesto).toFloat().coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(50)),
                        color = Color(0xFF2DD4BF),
                        trackColor = Color(0xFF192C45)
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Mensaje de estado
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .background(Color(0xFF10332D), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                tint = Color(0xFF34D399),
                                modifier = Modifier.size(12.dp)
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "¡Excelente inicio de mes! Mantienes el ${"%.1f".format(pctDisponible)}% de tu presupuesto disponible.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF8E9FB8),
                            fontSize = 11.sp
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        // ── CALENDARIO FINANCIERO DEL MES (INGRESOS, GASTOS FIJOS Y DEUDAS/PRÉSTAMOS) ───────
        if (ingresosProgramados.isNotEmpty() || pagosFijos.isNotEmpty() || deudasPrestamos.isNotEmpty()) {
            item {
                CalendarioFinancieroDelMes(
                    anio = anioActual,
                    mesIndex = mesActualIndex,
                    mesNombre = mesNombre,
                    mesActualStr = mesActualStr,
                    ingresos = ingresosProgramados,
                    pagosFijos = pagosFijos,
                    deudas = deudasPrestamos,
                    diaSeleccionado = diaSeleccionadoCalendario,
                    onDiaSeleccionado = { dia ->
                        diaSeleccionadoCalendario = if (diaSeleccionadoCalendario == dia) null else dia
                    },
                    onCobrarFecha = { ing, dia, fKey ->
                        ingresoCobrando = ing
                        fechaCobrandoDia = dia
                        fechaCobrandoStr = fKey
                        montoRealCobradoStr = ing.montoEsperado.toString()
                        notaCobroStr = ""
                        cuentaDestinoCobroId = ing.cuentaId
                    },
                    onPagarGastoFijo = { pago ->
                        viewModel.marcarPagoFijoPagado(pago)
                    },
                    onLiquidarDeuda = { deuda ->
                        viewModel.liquidarDeudaPrestamo(deuda.id)
                    },
                    format = format
                )
            }
        }

        // ── SECCIÓN: INGRESOS PROGRAMADOS / PROYECTADOS ───────────────────
        val totalEsperado = ingresosProgramados.sumOf { GastosViewModel.totalEsperadoMes(it, anioActual, mesActualIndex) }
        val totalCobrado = ingresosProgramados.sumOf { GastosViewModel.totalCobradoMes(it, anioActual, mesActualIndex) }
        val pctCobrado = if (totalEsperado > 0) ((totalCobrado / totalEsperado) * 100).toInt() else 0

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("💰", fontSize = 15.sp)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "Ingresos Programados",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }
                    Text(
                        "Sueldo, propinas, extras y negocio",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF8E9FB8),
                        fontSize = 11.sp
                    )
                }
                Surface(
                    onClick = {
                        nombreIngresoEdit = ""
                        montoEsperadoEdit = ""
                        diaCobroEdit = "15"
                        diaCobro2Edit = "30"
                        frecuenciaIngresoEdit = 0
                        categoriaIngresoEdit = "Sueldo"
                        cuentaIngresoEdit = cuentas.firstOrNull()?.id ?: "cuenta_bcp_yape"
                        showNuevoIngresoModal = true
                    },
                    shape = RoundedCornerShape(50),
                    color = Color(0xFF16253B),
                    border = BorderStroke(1.dp, Color(0xFF223856))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = null,
                            tint = Color(0xFF34D399),
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "Agregar",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF34D399)
                        )
                    }
                }
            }
        }

        if (ingresosProgramados.isNotEmpty()) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    shape = RoundedCornerShape(14.dp),
                    color = Color(0xFF0F1B2C),
                    border = BorderStroke(1.dp, Color(0xFF1A2A40))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                "PROYECCIÓN MES ($mesNombre)",
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF8E9FB8)
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "Esperado: ${format.format(totalEsperado)}",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                "RECIBIDO / COBRADO",
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF8E9FB8)
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "Cobrado: ${format.format(totalCobrado)} ($pctCobrado%)",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF34D399)
                            )
                        }
                    }
                }
            }
        }

        if (ingresosProgramados.isEmpty()) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF131F33)),
                    border = BorderStroke(1.dp, Color(0xFF1E2E48).copy(alpha = 0.5f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .background(Color(0xFF152A42), RoundedCornerShape(14.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("💵", fontSize = 22.sp)
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            "Sin ingresos programados configurados",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Registra tu sueldo (quincenal o mensual), propinas semanales, extras o negocio para proyectar tu flujo mensual.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF8E9FB8),
                            textAlign = TextAlign.Center,
                            fontSize = 12.sp
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Surface(
                            onClick = {
                                nombreIngresoEdit = ""
                                montoEsperadoEdit = ""
                                diaCobroEdit = "15"
                                diaCobro2Edit = "30"
                                frecuenciaIngresoEdit = 0
                                categoriaIngresoEdit = "Sueldo"
                                cuentaIngresoEdit = cuentas.firstOrNull()?.id ?: "cuenta_bcp_yape"
                                showNuevoIngresoModal = true
                            },
                            shape = RoundedCornerShape(50),
                            color = Color(0xFF34D399)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.AddCircleOutline,
                                    contentDescription = null,
                                    tint = Color(0xFF0F1A2A),
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "Configurar primer ingreso",
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF0F1A2A),
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }
        } else {
            items(ingresosProgramados, key = { it.id }) { ing ->
                val (catEmoji, catBg) = when (ing.categoria) {
                    "Sueldo" -> Pair("💼", Color(0xFF152A42))
                    "Propinas" -> Pair("💰", Color(0xFF10332D))
                    "Extras" -> Pair("⚡", Color(0xFF352516))
                    "Negocio" -> Pair("🏪", Color(0xFF2E1A47))
                    "Freelance" -> Pair("💻", Color(0xFF132F3E))
                    else -> Pair("🎁", Color(0xFF242C38))
                }
                val frecNombre = when (ing.frecuenciaTipo) {
                    GastosViewModel.FRECUENCIA_SEMANAL -> {
                        val nom = GastosViewModel.diasSemanaNombres().getOrElse(ing.diaCobro - 1) { "Viernes" }
                        "Semanal ($nom)"
                    }
                    GastosViewModel.FRECUENCIA_QUINCENAL -> "Quincenal (${ing.diaCobro} y ${ing.diaCobro2})"
                    else -> "Mensual (Día ${ing.diaCobro})"
                }
                val fechasMes = GastosViewModel.obtenerDiasCobroMes(ing, anioActual, mesActualIndex)

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF131F33)),
                    border = BorderStroke(1.dp, Color(0xFF1E2E48).copy(alpha = 0.5f))
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(42.dp)
                                        .background(catBg, RoundedCornerShape(12.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(catEmoji, fontSize = 20.sp)
                                }
                                Spacer(Modifier.width(10.dp))
                                Column {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            ing.nombre,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Surface(
                                            shape = RoundedCornerShape(50),
                                            color = Color(0xFF162A43)
                                        ) {
                                            Text(
                                                frecNombre,
                                                style = MaterialTheme.typography.labelSmall,
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFF76D1FF),
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        "Monto base: ${format.format(ing.montoEsperado)} por cobro",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color(0xFF8E9FB8),
                                        fontSize = 11.sp
                                    )
                                }
                            }

                            Surface(
                                onClick = {
                                    ingresoEditando = ing
                                    nombreIngresoEdit = ing.nombre
                                    montoEsperadoEdit = ing.montoEsperado.toString()
                                    diaCobroEdit = ing.diaCobro.toString()
                                    diaCobro2Edit = ing.diaCobro2.toString()
                                    frecuenciaIngresoEdit = ing.frecuenciaTipo
                                    categoriaIngresoEdit = ing.categoria
                                    cuentaIngresoEdit = ing.cuentaId
                                },
                                shape = RoundedCornerShape(8.dp),
                                color = Color(0xFF192A40),
                                modifier = Modifier.size(32.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Default.Edit,
                                        contentDescription = "Editar",
                                        tint = Color(0xFF8E9FB8),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(10.dp))

                        // Desglose de fechas en el calendario real para este mes
                        Text(
                            "Fechas del mes en calendario:",
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF70B4E8)
                        )
                        Spacer(Modifier.height(4.dp))

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            fechasMes.forEach { d ->
                                val fKey = String.format(Locale.US, "%04d-%02d-%02d", anioActual, mesActualIndex + 1, d)
                                val cobrado = GastosViewModel.estaFechaCobrada(ing, fKey)

                                if (cobrado != null) {
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = Color(0xFF10332D),
                                        border = BorderStroke(1.dp, Color(0xFF1B5E4A))
                                    ) {
                                        Text(
                                            "Día $d: ${format.format(cobrado)} ✓",
                                            color = Color(0xFF34D399),
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 11.sp,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                        )
                                    }
                                } else {
                                    Surface(
                                        onClick = {
                                            ingresoCobrando = ing
                                            fechaCobrandoDia = d
                                            fechaCobrandoStr = fKey
                                            montoRealCobradoStr = ing.montoEsperado.toString()
                                            notaCobroStr = ""
                                            cuentaDestinoCobroId = ing.cuentaId
                                        },
                                        shape = RoundedCornerShape(8.dp),
                                        color = Color(0xFF16253B),
                                        border = BorderStroke(1.dp, Color(0xFF233B5D))
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                "Día $d: ${format.format(ing.montoEsperado)}",
                                                color = Color(0xFF8E9FB8),
                                                fontSize = 11.sp
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            Surface(
                                                shape = RoundedCornerShape(4.dp),
                                                color = Color(0xFF34D399)
                                            ) {
                                                Text(
                                                    "Cobrar",
                                                    color = Color(0xFF0F1A2A),
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 10.sp,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            item { Spacer(modifier = Modifier.height(14.dp)) }
        }

        // ── 2. SECCIÓN: CHECKLIST DE GASTOS FIJOS ─────────────────────────
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        "Checklist de Gastos Fijos",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        "Mantén presionado para editar un pago fijo.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF8E9FB8),
                        fontSize = 11.sp
                    )
                }
                Surface(
                    onClick = { showNuevoPagoModal = true },
                    shape = RoundedCornerShape(50),
                    color = Color(0xFF16253B),
                    border = BorderStroke(1.dp, Color(0xFF223856))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = null,
                            tint = Color(0xFF38BDF8),
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "Agregar",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF38BDF8)
                        )
                    }
                }
            }
        }

        if (pagosFijos.isEmpty()) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF131F33)),
                    border = BorderStroke(1.dp, Color(0xFF1E2E48).copy(alpha = 0.5f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .size(50.dp)
                                .background(Color(0xFF152A42), RoundedCornerShape(14.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.DoneAll,
                                contentDescription = null,
                                tint = Color(0xFF38BDF8),
                                modifier = Modifier.size(26.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            "Sin compromisos fijos configurados",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            "Registra tu alquiler, servicios o suscripciones para tener un control anticipado de tus salidas.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF8E9FB8),
                            textAlign = TextAlign.Center,
                            fontSize = 12.sp
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Surface(
                            onClick = { showNuevoPagoModal = true },
                            shape = RoundedCornerShape(50),
                            color = Color(0xFF2DD4BF)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.AddCircleOutline,
                                    contentDescription = null,
                                    tint = Color(0xFF0F1A2A),
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "Configurar primer pago",
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF0F1A2A),
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }
                }
            }
        } else {
            items(pagosFijos.sortedBy { it.diaPago }, key = { it.id }) { pago ->
                val pagado = pago.ultimoMesPagado == mesActualStr
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF131F33)),
                    border = BorderStroke(1.dp, Color(0xFF1E2E48).copy(alpha = 0.5f))
                ) {
                    Row(
                        modifier = Modifier
                            .padding(14.dp)
                            .fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                pago.nombre,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "Vence día ${pago.diaPago} • ${format.format(pago.monto)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF8E9FB8)
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (pagado) {
                                Surface(
                                    shape = RoundedCornerShape(50),
                                    color = Color(0xFF10332D)
                                ) {
                                    Text(
                                        "Pagado ✓",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF34D399),
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                    )
                                }
                            } else {
                                Surface(
                                    onClick = { viewModel.marcarPagoFijoPagado(pago) },
                                    shape = RoundedCornerShape(10.dp),
                                    color = Color(0xFF38BDF8)
                                ) {
                                    Text(
                                        "Pagar",
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White,
                                        fontSize = 12.sp,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(8.dp))

                            Surface(
                                onClick = {
                                    pagoEditando = pago
                                    nombrePagoEdit = pago.nombre
                                    montoPagoEdit = pago.monto.toString()
                                    diaPagoEdit = pago.diaPago.toString()
                                },
                                shape = RoundedCornerShape(8.dp),
                                color = Color(0xFF192A40),
                                modifier = Modifier.size(28.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Default.Edit,
                                        contentDescription = "Editar",
                                        tint = Color(0xFF8E9FB8),
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // ── 3. SECCIÓN: TOPES POR CATEGORÍA ──────────────────────────────
        item {
            Spacer(modifier = Modifier.height(18.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        "Topes por Categoría",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        "Controla tu ritmo diario de gastos",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF8E9FB8),
                        fontSize = 11.sp
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Toca para editar",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF38BDF8),
                        fontSize = 11.sp
                    )
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        Icons.Default.Tune,
                        contentDescription = null,
                        tint = Color(0xFF38BDF8),
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }

        items(topes.toList(), key = { it.first }) { (cat, tope) ->
            val consumido = soloGastosMes.filter { it.categoria == cat }.sumOf { it.montoRealUsuario }
            val ratio = if (tope > 0) (consumido / tope).toFloat() else 0f
            val restante = (tope - consumido).coerceAtLeast(0.0)

            val (iconBgColor, iconTint, emojiStr) = when (cat) {
                "Comida", "Alimentación" -> Triple(Color(0xFF352516), Color(0xFFFBBF24), "🍴")
                "Ocio", "Entretenimiento" -> Triple(Color(0xFF2E1A47), Color(0xFFC084FC), "🎮")
                "Transporte" -> Triple(Color(0xFF132F3E), Color(0xFF22D3EE), "🚗")
                "Ropa" -> Triple(Color(0xFF351A2E), Color(0xFFF472B6), "👕")
                "Hogar" -> Triple(Color(0xFF1C2C42), Color(0xFF60A5FA), "🏠")
                "Salud" -> Triple(Color(0xFF35161A), Color(0xFFF87171), "💊")
                "Educación" -> Triple(Color(0xFF1A332B), Color(0xFF34D399), "📚")
                else -> Triple(Color(0xFF242C38), Color(0xFF94A3B8), "📦")
            }

            val barraColor = when {
                ratio >= 1.0f -> Color(0xFFEF4444)
                ratio >= 0.8f -> Color(0xFFF59E0B)
                else -> Color(0xFF2DD4BF)
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF131F33)),
                border = BorderStroke(1.dp, Color(0xFF1E2E48).copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Icon Box
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .background(iconBgColor, RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(emojiStr, fontSize = 20.sp)
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                cat,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "Restante: ${format.format(restante)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF34D399),
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 12.sp
                            )
                        }

                        Column(horizontalAlignment = Alignment.End) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    format.format(consumido),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = Color.White
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Surface(
                                    onClick = {
                                        categoriaEditandoTope = cat
                                        nuevoTopeStr = tope.toString()
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    color = Color(0xFF192A40),
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            Icons.Default.Edit,
                                            contentDescription = "Editar",
                                            tint = Color(0xFF8E9FB8),
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "/ ${format.format(tope)}",
                                color = Color(0xFF8E9FB8),
                                fontSize = 11.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    LinearProgressIndicator(
                        progress = { ratio.coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(50)),
                        color = barraColor,
                        trackColor = Color(0xFF1A2638)
                    )
                }
            }
        }

        // ── 4. TARJETA COPILOTO FINANCIERO ────────────────────────────────
        item {
            Spacer(modifier = Modifier.height(16.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF132034)),
                border = BorderStroke(1.dp, Color(0xFF1D324E))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = Color(0xFF152A42),
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.Psychology,
                                    contentDescription = null,
                                    tint = Color(0xFF76D1FF),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "Tu copiloto financiero",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = Color(0xFF10332D)
                        ) {
                            Text(
                                "ACTIVO",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF34D399),
                                fontSize = 9.sp,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        "¡Vas a un excelente ritmo! Tu consumo en Comida está un 92% por debajo del umbral diario recomendado. ¡Sigue así! 👏",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF8E9FB8),
                        lineHeight = 18.sp
                    )
                }
            }
        }
    }

    // Dialog cobrar ingreso programado (permite modificar el monto si hubo feriado, bono u horas extras)
    ingresoCobrando?.let { ing ->
        val cuentasDisponibles = cuentas.ifEmpty { Cuenta.cuentasPorDefecto() }
        AlertDialog(
            onDismissRequest = { ingresoCobrando = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("💰", fontSize = 20.sp)
                    Spacer(Modifier.width(8.dp))
                    Text("Registrar Cobro")
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Cobro de: ${ing.nombre} (Día ${if (fechaCobrandoDia > 0) fechaCobrandoDia else ing.diaCobro} de $mesNombre)",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Monto proyectado: ${format.format(ing.montoEsperado)}.\n¿Recibiste un monto diferente por feriado, horas extras o bono? Modifícalo aquí:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = montoRealCobradoStr,
                        onValueChange = { montoRealCobradoStr = it },
                        label = { Text("Monto cobrado real (S/)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = notaCobroStr,
                        onValueChange = { notaCobroStr = it },
                        label = { Text("Nota opcional (ej. Feriado trabajado, horas extras)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("¿A qué cuenta ingresó el dinero?", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        cuentasDisponibles.forEach { c ->
                            FilterChip(
                                selected = cuentaDestinoCobroId == c.id,
                                onClick = { cuentaDestinoCobroId = c.id },
                                label = { Text(c.nombre) }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val mReal = montoRealCobradoStr.toDoubleOrNull() ?: ing.montoEsperado
                        val fKey = if (fechaCobrandoStr.isNotBlank()) fechaCobrandoStr
                        else String.format(Locale.US, "%04d-%02d-%02d", anioActual, mesActualIndex + 1, ing.diaCobro)
                        viewModel.cobrarIngresoProgramadoFecha(
                            ingreso = ing,
                            fechaKey = fKey,
                            montoReal = mReal,
                            cuentaDestinoId = cuentaDestinoCobroId,
                            nota = notaCobroStr
                        )
                        ingresoCobrando = null
                    }
                ) {
                    Text("Confirmar Ingreso")
                }
            },
            dismissButton = {
                TextButton(onClick = { ingresoCobrando = null }) {
                    Text("Cancelar")
                }
            }
        )
    }

    // Dialog crear o editar ingreso programado (con soporte para Quincenal con 2 días y Semanal congruente con calendario real)
    if (showNuevoIngresoModal || ingresoEditando != null) {
        val esEdicion = ingresoEditando != null
        val categoriasIngreso = listOf("Sueldo", "Propinas", "Extras", "Negocio", "Freelance", "Otro")
        val cuentasDisponibles = cuentas.ifEmpty { Cuenta.cuentasPorDefecto() }
        val maxDiasMesActual = remember { Calendar.getInstance().getActualMaximum(Calendar.DAY_OF_MONTH) }

        AlertDialog(
            onDismissRequest = {
                showNuevoIngresoModal = false
                ingresoEditando = null
            },
            title = {
                Text(if (esEdicion) "Editar Ingreso Programado" else "Nuevo Ingreso Programado")
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = nombreIngresoEdit,
                        onValueChange = { nombreIngresoEdit = it },
                        label = { Text("Nombre (ej. Sueldo quincenal, Propinas)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = montoEsperadoEdit,
                        onValueChange = { montoEsperadoEdit = it },
                        label = {
                            Text(
                                when (frecuenciaIngresoEdit) {
                                    GastosViewModel.FRECUENCIA_SEMANAL -> "Monto por cada semana (S/)"
                                    GastosViewModel.FRECUENCIA_QUINCENAL -> "Monto por cada quincena (S/)"
                                    else -> "Monto mensual estimado (S/)"
                                }
                            )
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Text("Frecuencia de cobro:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FilterChip(
                            selected = frecuenciaIngresoEdit == GastosViewModel.FRECUENCIA_MENSUAL,
                            onClick = {
                                frecuenciaIngresoEdit = GastosViewModel.FRECUENCIA_MENSUAL
                                if (diaCobroEdit.toIntOrNull() == null || diaCobroEdit.toInt() !in 1..31) {
                                    diaCobroEdit = "15"
                                }
                            },
                            label = { Text("Mensual") }
                        )
                        FilterChip(
                            selected = frecuenciaIngresoEdit == GastosViewModel.FRECUENCIA_QUINCENAL,
                            onClick = {
                                frecuenciaIngresoEdit = GastosViewModel.FRECUENCIA_QUINCENAL
                                diaCobroEdit = "15"
                                diaCobro2Edit = "30"
                            },
                            label = { Text("Quincenal (2 al mes)") }
                        )
                        FilterChip(
                            selected = frecuenciaIngresoEdit == GastosViewModel.FRECUENCIA_SEMANAL,
                            onClick = {
                                frecuenciaIngresoEdit = GastosViewModel.FRECUENCIA_SEMANAL
                                diaCobroEdit = "5" // 5 = Viernes por defecto
                            },
                            label = { Text("Semanal (por día semana)") }
                        )
                    }

                    // ── CONFIGURACIÓN SEGÚN FRECUENCIA ──
                    when (frecuenciaIngresoEdit) {
                        GastosViewModel.FRECUENCIA_QUINCENAL -> {
                            Text(
                                "Días de cobro en cada quincena:",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF70B4E8)
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                val presets = listOf(
                                    Pair("15 y 30", Pair("15", "30")),
                                    Pair("15 y Fin de mes", Pair("15", maxDiasMesActual.toString())),
                                    Pair("10 y 25", Pair("10", "25")),
                                    Pair("1 y 16", Pair("1", "16"))
                                )
                                presets.forEach { (label, pair) ->
                                    FilterChip(
                                        selected = diaCobroEdit == pair.first && diaCobro2Edit == pair.second,
                                        onClick = {
                                            diaCobroEdit = pair.first
                                            diaCobro2Edit = pair.second
                                        },
                                        label = { Text(label) }
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = diaCobroEdit,
                                    onValueChange = { diaCobroEdit = it },
                                    label = { Text("1er Día (1 a 15)") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                                OutlinedTextField(
                                    value = diaCobro2Edit,
                                    onValueChange = { diaCobro2Edit = it },
                                    label = { Text("2do Día (16 a 31)") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            val d1 = diaCobroEdit.toIntOrNull()?.coerceIn(1, maxDiasMesActual) ?: 15
                            val d2 = diaCobro2Edit.toIntOrNull()?.coerceIn(1, maxDiasMesActual) ?: 30
                            val m = montoEsperadoEdit.toDoubleOrNull() ?: 0.0

                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = Color(0xFF14243A),
                                border = BorderStroke(1.dp, Color(0xFF1E3553)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(8.dp)) {
                                    Text(
                                        "🗓️ En $mesNombre cobrarás en los días $d1 y $d2 ($mesNombreCorto).",
                                        fontSize = 11.sp,
                                        color = Color(0xFF76D1FF),
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    if (m > 0) {
                                        Text(
                                            "Total estimado este mes: 2 pagos × ${format.format(m)} = ${format.format(2 * m)}",
                                            fontSize = 11.sp,
                                            color = Color(0xFF34D399),
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }

                        GastosViewModel.FRECUENCIA_SEMANAL -> {
                            Text(
                                "Día de la semana de cobro:",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF70B4E8)
                            )
                            val diasSemana = listOf(
                                Pair(1, "Lunes"),
                                Pair(2, "Martes"),
                                Pair(3, "Miércoles"),
                                Pair(4, "Jueves"),
                                Pair(5, "Viernes"),
                                Pair(6, "Sábado"),
                                Pair(7, "Domingo")
                            )
                            val diaSemanaSel = diaCobroEdit.toIntOrNull()?.coerceIn(1, 7) ?: 5
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                diasSemana.forEach { (idx, nom) ->
                                    FilterChip(
                                        selected = diaSemanaSel == idx,
                                        onClick = { diaCobroEdit = idx.toString() },
                                        label = { Text(nom) }
                                    )
                                }
                            }

                            val tempIng = IngresoProgramado(
                                nombre = nombreIngresoEdit,
                                montoEsperado = montoEsperadoEdit.toDoubleOrNull() ?: 0.0,
                                frecuenciaTipo = GastosViewModel.FRECUENCIA_SEMANAL,
                                diaCobro = diaSemanaSel
                            )
                            val fechasReales = GastosViewModel.obtenerDiasCobroMes(tempIng, anioActual, mesActualIndex)
                            val nomDiaSel = diasSemana.firstOrNull { it.first == diaSemanaSel }?.second ?: "Viernes"
                            val m = montoEsperadoEdit.toDoubleOrNull() ?: 0.0

                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = Color(0xFF14243A),
                                border = BorderStroke(1.dp, Color(0xFF1E3553)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(8.dp)) {
                                    Text(
                                        "🗓️ Congruente con el calendario de $mesNombre:",
                                        fontSize = 11.sp,
                                        color = Color(0xFF76D1FF),
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        "Los $nomDiaSel caen en ${fechasReales.size} fechas reales: ${fechasReales.joinToString { "$it de $mesNombreCorto" }}",
                                        fontSize = 11.sp,
                                        color = Color.White
                                    )
                                    if (m > 0) {
                                        Text(
                                            "Total estimado este mes: ${fechasReales.size} pagos × ${format.format(m)} = ${format.format(fechasReales.size * m)}",
                                            fontSize = 11.sp,
                                            color = Color(0xFF34D399),
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }

                        else -> {
                            // FRECUENCIA_MENSUAL
                            Text(
                                "Día habitual de cobro en el mes:",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF70B4E8)
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                listOf(
                                    Pair("Fin de mes", maxDiasMesActual.toString()),
                                    Pair("Día 30", "30"),
                                    Pair("Día 15", "15"),
                                    Pair("Día 1", "1")
                                ).forEach { (label, v) ->
                                    FilterChip(
                                        selected = diaCobroEdit == v,
                                        onClick = { diaCobroEdit = v },
                                        label = { Text(label) }
                                    )
                                }
                            }

                            OutlinedTextField(
                                value = diaCobroEdit,
                                onValueChange = { diaCobroEdit = it },
                                label = { Text("Día habitual en el mes (1 a 31)") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )

                            val diaMes = diaCobroEdit.toIntOrNull()?.coerceIn(1, maxDiasMesActual) ?: 15
                            val m = montoEsperadoEdit.toDoubleOrNull() ?: 0.0

                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = Color(0xFF14243A),
                                border = BorderStroke(1.dp, Color(0xFF1E3553)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(8.dp)) {
                                    Text(
                                        "🗓️ En $mesNombre cobrarás 1 vez: el día $diaMes de $mesNombre.",
                                        fontSize = 11.sp,
                                        color = Color(0xFF76D1FF),
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    if (m > 0) {
                                        Text(
                                            "Total estimado este mes: ${format.format(m)}",
                                            fontSize = 11.sp,
                                            color = Color(0xFF34D399),
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Text("Tipo / Categoría:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        categoriasIngreso.forEach { cat ->
                            FilterChip(
                                selected = categoriaIngresoEdit == cat,
                                onClick = { categoriaIngresoEdit = cat },
                                label = { Text(cat) }
                            )
                        }
                    }

                    Text("Cuenta habitual donde ingresa:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        cuentasDisponibles.forEach { c ->
                            FilterChip(
                                selected = cuentaIngresoEdit == c.id,
                                onClick = { cuentaIngresoEdit = c.id },
                                label = { Text(c.nombre) }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val m = montoEsperadoEdit.toDoubleOrNull() ?: 0.0
                        val d1 = diaCobroEdit.toIntOrNull() ?: 15
                        val d2 = diaCobro2Edit.toIntOrNull() ?: 30
                        if (nombreIngresoEdit.isNotBlank() && m > 0) {
                            if (esEdicion && ingresoEditando != null) {
                                viewModel.editarIngresoProgramado(
                                    ingresoEditando!!.copy(
                                        nombre = nombreIngresoEdit.trim(),
                                        montoEsperado = m,
                                        diaCobro = d1,
                                        diaCobro2 = d2,
                                        categoria = categoriaIngresoEdit,
                                        cuentaId = cuentaIngresoEdit,
                                        frecuenciaTipo = frecuenciaIngresoEdit
                                    )
                                )
                            } else {
                                viewModel.crearIngresoProgramado(
                                    nombre = nombreIngresoEdit.trim(),
                                    montoEsperado = m,
                                    diaCobro = d1,
                                    diaCobro2 = d2,
                                    categoria = categoriaIngresoEdit,
                                    cuentaId = cuentaIngresoEdit,
                                    frecuenciaTipo = frecuenciaIngresoEdit
                                )
                            }
                            showNuevoIngresoModal = false
                            ingresoEditando = null
                        }
                    }
                ) {
                    Text(if (esEdicion) "Guardar" else "Crear")
                }
            },
            dismissButton = {
                Row {
                    if (esEdicion && ingresoEditando != null) {
                        TextButton(
                            onClick = {
                                viewModel.eliminarIngresoProgramado(ingresoEditando!!)
                                showNuevoIngresoModal = false
                                ingresoEditando = null
                            }
                        ) {
                            Text("Eliminar", color = MaterialTheme.colorScheme.error)
                        }
                    }
                    TextButton(
                        onClick = {
                            showNuevoIngresoModal = false
                            ingresoEditando = null
                        }
                    ) {
                        Text("Cancelar")
                    }
                }
            }
        )
    }

    // Dialog editar pago fijo
    pagoEditando?.let { pago ->
        AlertDialog(
            onDismissRequest = { pagoEditando = null },
            title = { Text("Editar pago fijo") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = nombrePagoEdit,
                        onValueChange = { nombrePagoEdit = it },
                        label = { Text("Nombre del servicio") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = montoPagoEdit,
                        onValueChange = { montoPagoEdit = it },
                        label = { Text("Monto (S/)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = diaPagoEdit,
                        onValueChange = { diaPagoEdit = it },
                        label = { Text("Día de vencimiento (1-31)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    val m = montoPagoEdit.toDoubleOrNull() ?: pago.monto
                    val d = diaPagoEdit.toIntOrNull() ?: pago.diaPago
                    viewModel.editarPagoFijo(pago.copy(nombre = nombrePagoEdit.trim(), monto = m, diaPago = d))
                    pagoEditando = null
                }) { Text("Guardar") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        viewModel.eliminarPagoFijo(pago)
                        pagoEditando = null
                    }) {
                        Text("Eliminar", color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = { pagoEditando = null }) { Text("Cancelar") }
                }
            }
        )
    }

    // Dialog editar tope de categoría
    categoriaEditandoTope?.let { cat ->
        AlertDialog(
            onDismissRequest = { categoriaEditandoTope = null },
            title = { Text("Editar tope: $cat") },
            text = {
                OutlinedTextField(
                    value = nuevoTopeStr,
                    onValueChange = { nuevoTopeStr = it },
                    label = { Text("Nuevo tope mensual (S/)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    val t = nuevoTopeStr.toDoubleOrNull() ?: 0.0
                    viewModel.editarTopeCat(cat, t)
                    categoriaEditandoTope = null
                }) { Text("Guardar") }
            },
            dismissButton = {
                TextButton(onClick = { categoriaEditandoTope = null }) { Text("Cancelar") }
            }
        )
    }

    if (showNuevoPagoModal) {
        val cuentas by viewModel.cuentas.collectAsState()
        DialogNuevoPagoFijo(
            cuentas = cuentas,
            onGuardar = { nom, monto, dia, cat, cuentaId, frecuenciaTipo ->
                viewModel.crearPagoFijo(nom, monto, dia, cat, cuentaId, frecuenciaTipo)
                showNuevoPagoModal = false
            },
            onCerrar = { showNuevoPagoModal = false }
        )
    }
}

// -------------------------------------------------------------------------
// PESTAÑA 4: COPILOTO IA — REDISEÑO STITCH
// -------------------------------------------------------------------------
@Composable
fun AnalyticsAiScreen(
    viewModel: GastosViewModel,
    format: NumberFormat,
    onOpenSettings: () -> Unit = {}
) {
    val gastos by viewModel.gastos.collectAsState()
    val copilotoMensajes by viewModel.copilotoMensajes.collectAsState()
    val diagnostico by viewModel.diagnosticoAnomalias.collectAsState()
    val topes by viewModel.topesCategorias.collectAsState()
    val presupuesto by viewModel.presupuesto.collectAsState()
    var textoPregunta by remember { mutableStateOf("") }

    // Datos calculados
    val ahora = java.util.Calendar.getInstance()
    val mesActual = ahora.get(java.util.Calendar.MONTH)
    val anioActual = ahora.get(java.util.Calendar.YEAR)

    val soloGastos = gastos.filter {
        !it.esTransferencia && !it.esIngreso &&
        run {
            val cal = java.util.Calendar.getInstance()
            cal.time = java.util.Date(it.fecha)
            cal.get(java.util.Calendar.MONTH) == mesActual && cal.get(java.util.Calendar.YEAR) == anioActual
        }
    }
    val totalMes = soloGastos.sumOf { it.montoRealUsuario }
    val porCategoria = soloGastos.groupBy { it.categoria }
        .mapValues { it.value.sumOf { g -> g.montoRealUsuario } }
        .toList().sortedByDescending { it.second }

    // Gastos hormiga (< S/10)
    val gastosHormiga = soloGastos.filter { it.montoRealUsuario < 10.0 }.sumOf { it.montoRealUsuario }

    // Presupuesto libre
    val presupuestoLibre = (presupuesto - totalMes).coerceAtLeast(0.0)

    val coloresCat = listOf(
        Color(0xFF38BDF8), Color(0xFFC084FC), Color(0xFF2DD4BF),
        Color(0xFFFBBF24), Color(0xFFF472B6), Color(0xFF818CF8)
    )

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        // ── ENCABEZADO SUPERIOR ───────────────────────────────────────────
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFF14243A),
                        border = BorderStroke(1.dp, Color(0xFF1E3553)),
                        modifier = Modifier.size(42.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = Color(0xFF76D1FF),
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            "MIS GASTOS",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF70B4E8),
                            letterSpacing = 0.5.sp,
                            fontSize = 10.sp
                        )
                        Text(
                            "Copiloto IA",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = CircleShape,
                        color = Color(0xFF14243A),
                        border = BorderStroke(1.dp, Color(0xFF1E3553)),
                        modifier = Modifier.size(38.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.Notifications,
                                contentDescription = "Notificaciones",
                                tint = Color(0xFF76D1FF),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    Surface(
                        onClick = onOpenSettings,
                        shape = CircleShape,
                        color = Color(0xFF16253B),
                        border = BorderStroke(1.5.dp, Color(0xFF38BDF8)),
                        modifier = Modifier.size(38.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.Person,
                                contentDescription = "Perfil y Ajustes",
                                tint = Color(0xFF76D1FF),
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
            }
        }

        // ── 1. CARD GASTOS BAJO CONTROL ───────────────────────────────────
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF132034)),
                border = BorderStroke(1.dp, Color(0xFF1D324E))
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    // Header de la tarjeta
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            color = Color(0xFF152A42),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.size(40.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                Icon(
                                    Icons.Default.Shield,
                                    contentDescription = null,
                                    tint = Color(0xFF38BDF8),
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                "Gastos Bajo Control",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Spacer(Modifier.height(2.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .background(Color(0xFF22C55E), CircleShape)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "Ritmo óptimo de consumo",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFF8E9FB8),
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(14.dp))

                    // Dos estadísticas en contenedor oscuro unificado
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        color = Color(0xFF0F1B2C),
                        border = BorderStroke(1.dp, Color(0xFF1A2A40))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Gastos hormiga
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "GASTOS HORMIGA",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF8E9FB8),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 10.sp
                                )
                                Spacer(Modifier.height(4.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "S/ ${"%.2f".format(gastosHormiga)}",
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = 15.sp,
                                        color = Color.White
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        "(-42%)",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color(0xFF34D399),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp
                                    )
                                }
                            }

                            // Presupuesto libre
                            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                                Text(
                                    "PRESUPUESTO LIBRE",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF8E9FB8),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 10.sp
                                )
                                Spacer(Modifier.height(4.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "S/ ${"%.2f".format(presupuestoLibre)}",
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = 15.sp,
                                        color = Color.White
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        "disponible",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color(0xFF8E9FB8),
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    // Checkmark row
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = Color(0xFF38BDF8),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Tus pagos fijos y metas de ahorro del mes están al día.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF8E9FB8),
                            fontSize = 12.sp
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        // ── 2. CARD DISTRIBUCIÓN DEL MES ──────────────────────────────────
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF132034)),
                border = BorderStroke(1.dp, Color(0xFF1D324E))
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    // Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = Color(0xFF2E1A47),
                                modifier = Modifier.size(38.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Default.TrendingUp,
                                        contentDescription = null,
                                        tint = Color(0xFFC084FC),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(
                                    "Distribución del",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Text(
                                    "Mes",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                "Total: S/",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF8E9FB8),
                                fontSize = 11.sp
                            )
                            Text(
                                "%.2f".format(totalMes),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }

                    Spacer(Modifier.height(14.dp))

                    if (porCategoria.isEmpty()) {
                        Text(
                            "Sin gastos registrados este mes.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF8E9FB8),
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    } else {
                        porCategoria.take(5).forEachIndexed { idx, (cat, suma) ->
                            val tope = topes[cat] ?: 0.0
                            val ratio = if (tope > 0) (suma / tope).toFloat().coerceIn(0f, 1f)
                                        else if (totalMes > 0) (suma / totalMes).toFloat().coerceIn(0f, 1f)
                                        else 0f
                            val barColor = coloresCat.getOrElse(idx) { Color(0xFF38BDF8) }
                            val pctTope = if (tope > 0) ((suma / tope) * 100).toInt() else 0

                            val iconBg = if (idx % 2 == 0) Color(0xFF152A42) else Color(0xFF2E1A47)
                            val iconTint = if (idx % 2 == 0) Color(0xFF38BDF8) else Color(0xFFC084FC)

                            Column(modifier = Modifier.padding(bottom = 12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = iconBg,
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Text(
                                                    when (cat) {
                                                        "Alimentación", "Comida" -> "🍔"
                                                        "Transporte" -> "🚗"
                                                        "Entretenimiento", "Ocio" -> "🎮"
                                                        "Salud" -> "💊"
                                                        "Educación" -> "📚"
                                                        "Ropa" -> "👕"
                                                        "Hogar", "Servicios" -> "🏠"
                                                        else -> "📦"
                                                    },
                                                    fontSize = 15.sp
                                                )
                                            }
                                        }
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            cat,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = Color.White
                                        )
                                    }
                                    Text(
                                        "S/ ${"%.2f".format(suma)}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                }
                                Spacer(Modifier.height(6.dp))
                                LinearProgressIndicator(
                                    progress = { ratio },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(5.dp)
                                        .clip(RoundedCornerShape(50)),
                                    color = barColor,
                                    trackColor = Color(0xFF1A2638)
                                )
                                Spacer(Modifier.height(4.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        if (tope > 0) "Presupuesto asignado: S/ ${"%.2f".format(tope)}" else "Sin tope configurado",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color(0xFF8E9FB8),
                                        fontSize = 10.sp
                                    )
                                    if (tope > 0) {
                                        Text(
                                            "$pctTope% del tope",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = barColor,
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 10.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        // ── 3. CARD COPILOTO INTELIGENTE ──────────────────────────────────
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF132034)),
                border = BorderStroke(1.dp, Color(0xFF1D324E))
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    // Header
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            color = Color(0xFF76D1FF),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.size(42.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                Icon(
                                    Icons.Default.SmartToy,
                                    contentDescription = null,
                                    tint = Color(0xFF0F1A2A),
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                "Copiloto",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF38BDF8)
                            )
                            Text(
                                "Inteligente",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF38BDF8)
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    Text(
                        "¡Hola! Soy tu Copiloto Financiero IA. Pregúntame si te alcanza para una compra, en qué se te está yendo el dinero o cómo optimizar tu ahorro.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFBAC7D5),
                        lineHeight = 18.sp
                    )

                    // Mensajes recientes si hay conversación
                    val ultimosMensajes = copilotoMensajes.takeLast(4)
                    if (ultimosMensajes.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        ultimosMensajes.forEach { msg ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp),
                                horizontalArrangement = if (msg.esUsuario) Arrangement.End else Arrangement.Start
                            ) {
                                Surface(
                                    color = if (msg.esUsuario) Color(0xFF152A42) else Color(0xFF0F1B2C),
                                    shape = RoundedCornerShape(12.dp),
                                    border = BorderStroke(1.dp, Color(0xFF1A2A40)),
                                    modifier = Modifier.fillMaxWidth(0.85f)
                                ) {
                                    Text(
                                        msg.texto,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.White,
                                        modifier = Modifier.padding(10.dp),
                                        lineHeight = 16.sp
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    // Input estilo pill oscuro con icono de chat y botón circular cyan
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF0F1B2C), RoundedCornerShape(50))
                            .border(1.dp, Color(0xFF192C45), RoundedCornerShape(50))
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            Icons.Default.ChatBubbleOutline,
                            contentDescription = null,
                            tint = Color(0xFF76D1FF),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        BasicTextField(
                            value = textoPregunta,
                            onValueChange = { textoPregunta = it },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(
                                color = Color.White,
                                fontSize = 13.sp
                            ),
                            decorationBox = { inner ->
                                if (textoPregunta.isEmpty()) {
                                    Text(
                                        "¿Puedo ahorrar S/ 200 más?",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color(0xFF8E9FB8),
                                        fontSize = 13.sp
                                    )
                                }
                                inner()
                            }
                        )
                        Surface(
                            onClick = {
                                if (textoPregunta.isNotBlank()) {
                                    viewModel.enviarMensajeCopiloto(textoPregunta)
                                    textoPregunta = ""
                                }
                            },
                            shape = CircleShape,
                            color = Color(0xFF38BDF8),
                            modifier = Modifier.size(34.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                Icon(
                                    Icons.AutoMirrored.Filled.Send,
                                    contentDescription = "Enviar",
                                    tint = Color(0xFF0F1A2A),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// =========================================================================
// ETAPA 4: HOJAS FLOTANTES (MODAL BOTTOM SHEETS) Y PANTALLAS SECUNDARIAS
// =========================================================================

// 1. Hoja de Confirmación de IA y Capturas
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiConfirmationSheet(
    movimientos: List<MovimientoDetectado>,
    cuentas: List<Cuenta>,
    onConfirmar: (List<MovimientoDetectado>) -> Unit,
    onDismiss: () -> Unit
) {
    val listaModificable = remember { mutableStateListOf(*movimientos.toTypedArray()) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
            Text("Confirmación Inteligente", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Revisa o cambia la categoría y cuenta antes de guardar en Firestore.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(16.dp))

            listaModificable.forEachIndexed { index, m ->
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val tipoTexto = when {
                                m.esTransferencia -> "Transferencia"
                                m.esIngreso -> "Ingreso (+)"
                                else -> "Gasto (-)"
                            }
                            val tipoColor = when {
                                m.esTransferencia -> MaterialTheme.colorScheme.tertiary
                                m.esIngreso -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.error
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(
                                    color = tipoColor.copy(alpha = 0.15f),
                                    shape = MaterialTheme.shapes.small
                                ) {
                                    Text(
                                        text = tipoTexto,
                                        color = tipoColor,
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.labelMedium,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                // Selector rápido para cambiar si es Gasto o Ingreso manualmente si la IA dudó
                                if (!m.esTransferencia) {
                                    TextButton(onClick = {
                                        listaModificable[index] = m.copy(esIngreso = !m.esIngreso)
                                    }) {
                                        Text(if (m.esIngreso) "Cambiar a Gasto" else "Cambiar a Ingreso", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }

                            Text(
                                text = (if (m.esIngreso) "+ " else if (!m.esTransferencia) "- " else "") + "S/ ${m.monto}",
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 18.sp,
                                color = tipoColor
                            )
                        }
                        if (m.nota.isNotBlank()) Text("Nota: ${m.nota}", style = MaterialTheme.typography.bodySmall)

                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Categoría:", style = MaterialTheme.typography.labelSmall)
                        Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                            Gasto.CATEGORIAS.take(5).forEach { cat ->
                                FilterChip(
                                    selected = m.categoria == cat,
                                    onClick = { listaModificable[index] = m.copy(categoria = cat) },
                                    label = { Text(cat) },
                                    modifier = Modifier.padding(end = 4.dp)
                                )
                            }
                        }

                        if (cuentas.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Cuenta:", style = MaterialTheme.typography.labelSmall)
                            Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                                cuentas.forEach { cta ->
                                    FilterChip(
                                        selected = m.cuentaNombre == cta.nombre,
                                        onClick = { listaModificable[index] = m.copy(cuentaNombre = cta.nombre) },
                                        label = { Text(cta.nombre) },
                                        modifier = Modifier.padding(end = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
            Button(
                onClick = { onConfirmar(listaModificable.toList()) },
                modifier = Modifier.fillMaxWidth().height(50.dp)
            ) {
                Text("Confirmar y Guardar en Firestore")
            }
        }
    }
}

// 2. Teclado Manual Rápido (Calculadora)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualEntrySheet(
    cuentas: List<Cuenta>,
    onGuardar: (Double, String, String, Boolean, String, Double, String) -> Unit,
    onDismiss: () -> Unit
) {
    var montoStr by remember { mutableStateOf("0") }
    var esIngreso by remember { mutableStateOf(false) }
    var categoria by remember { mutableStateOf("Comida") }
    var cuentaId by remember { mutableStateOf(cuentas.firstOrNull()?.id ?: "cuenta_bcp_yape") }
    var nota by remember { mutableStateOf("") }
    var dividirGasto by remember { mutableStateOf(false) }
    var deudor by remember { mutableStateOf("") }
    var montoPorCobrarStr by remember { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
            // Pantalla de Monto
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row {
                    FilterChip(selected = !esIngreso, onClick = { esIngreso = false }, label = { Text("Gasto") })
                    Spacer(modifier = Modifier.width(6.dp))
                    FilterChip(selected = esIngreso, onClick = { esIngreso = true }, label = { Text("Ingreso") })
                }
                Text("S/ $montoStr", fontSize = 32.sp, fontWeight = FontWeight.ExtraBold, color = if (esIngreso) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Categorías Rápidas
            Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                Gasto.CATEGORIAS.forEach { cat ->
                    FilterChip(
                        selected = categoria == cat,
                        onClick = { categoria = cat },
                        label = { Text(cat) },
                        modifier = Modifier.padding(end = 4.dp)
                    )
                }
            }

            // Opciones de División
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = dividirGasto, onCheckedChange = { dividirGasto = it })
                Text("Dividir gasto (Me deben una parte)", style = MaterialTheme.typography.bodySmall)
            }
            if (dividirGasto) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = deudor, onValueChange = { deudor = it }, label = { Text("Amigo") }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(value = montoPorCobrarStr, onValueChange = { montoPorCobrarStr = it }, label = { Text("Monto (S/)") }, modifier = Modifier.weight(1f), singleLine = true)
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Teclado Numérico Tipo Calculadora
            val botones = listOf(
                listOf("7", "8", "9"),
                listOf("4", "5", "6"),
                listOf("1", "2", "3"),
                listOf(".", "0", "⌫")
            )
            botones.forEach { fila ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    fila.forEach { num ->
                        Button(
                            onClick = {
                                when (num) {
                                    "⌫" -> montoStr = if (montoStr.length > 1) montoStr.dropLast(1) else "0"
                                    "." -> if (!montoStr.contains(".")) montoStr += "."
                                    else -> montoStr = if (montoStr == "0") num else montoStr + num
                                }
                            },
                            modifier = Modifier.weight(1f).height(48.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
                        ) {
                            Text(num, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
            }

            Button(
                onClick = {
                    val m = montoStr.toDoubleOrNull() ?: 0.0
                    val porCobrar = if (dividirGasto) montoPorCobrarStr.toDoubleOrNull() ?: 0.0 else 0.0
                    if (m > 0) {
                        onGuardar(m, categoria, nota, esIngreso, cuentaId, porCobrar, deudor)
                    }
                },
                modifier = Modifier.fillMaxWidth().height(50.dp)
            ) {
                Text("Guardar Movimiento")
            }
        }
    }
}

// 3. Historial y Buscador (HistoryScreen)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    viewModel: GastosViewModel,
    format: NumberFormat,
    onBack: () -> Unit
) {
    BackHandler { onBack() }
    var busqueda by remember { mutableStateOf("") }
    var categoriaFiltro by remember { mutableStateOf<String?>(null) }
    val gastos by viewModel.gastos.collectAsState()

    val filtrados = gastos.filter {
        (busqueda.isBlank() || it.nota.contains(busqueda, ignoreCase = true) || it.categoria.contains(busqueda, ignoreCase = true)) &&
        (categoriaFiltro == null || it.categoria == categoriaFiltro)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Historial Completo") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
            OutlinedTextField(
                value = busqueda,
                onValueChange = { busqueda = it },
                label = { Text("Buscar gasto o nota...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                FilterChip(
                    selected = categoriaFiltro == null,
                    onClick = { categoriaFiltro = null },
                    label = { Text("Todos") },
                    modifier = Modifier.padding(end = 4.dp)
                )
                Gasto.CATEGORIAS.forEach { cat ->
                    FilterChip(
                        selected = categoriaFiltro == cat,
                        onClick = { categoriaFiltro = if (categoriaFiltro == cat) null else cat },
                        label = { Text(cat) },
                        modifier = Modifier.padding(end = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            LazyColumn(modifier = Modifier.weight(1f)) {
                items(filtrados, key = { it.id }) { g ->
                    ListItem(
                        headlineContent = { Text(g.categoria) },
                        supportingContent = {
                            Text("${SimpleDateFormat("dd MMM yyyy, HH:mm", Locale("es")).format(Date(g.fecha))}${if(g.nota.isNotBlank()) " • ${g.nota}" else ""}")
                        },
                        trailingContent = {
                            Text(
                                text = (if (g.esIngreso) "+" else "-") + format.format(g.montoRealUsuario),
                                fontWeight = FontWeight.Bold,
                                color = if (g.esIngreso) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                            )
                        }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

// 4. Ajustes y Exportación (SettingsScreen)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: GastosViewModel,
    onExportar: () -> Unit,
    onBack: () -> Unit,
    onCerrarSesion: () -> Unit
) {
    BackHandler { onBack() }
    val biometriaHabilitada by viewModel.biometriaHabilitada.collectAsState()
    val nombreGuardado by viewModel.usuarioNombre.collectAsState()
    val emailGuardado by viewModel.usuarioEmail.collectAsState()
    val context = LocalContext.current
    val firebaseUser = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser

    val nombreMostrar = firebaseUser?.displayName ?: nombreGuardado ?: "Sin nombre"
    val emailMostrar = firebaseUser?.email ?: emailGuardado ?: "Sin correo"

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ajustes") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(modifier = Modifier.padding(padding).fillMaxSize().padding(16.dp)) {

            // ── Perfil del usuario ──
            item {
                val perfil by viewModel.usuarioPerfil.collectAsState()
                val apellido = perfil?.get("apellido") as? String ?: ""
                val nombreCompleto = if (apellido.isNotBlank()) "$nombreMostrar $apellido" else nombreMostrar

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Avatar circular
                        Box(
                            modifier = Modifier
                                .size(52.dp)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.4f), shape = CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = (nombreMostrar.firstOrNull() ?: emailMostrar.firstOrNull() ?: '?').toString().uppercase(),
                                color = MaterialTheme.colorScheme.onSurface,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = nombreCompleto,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = emailMostrar,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(20.dp))
            }

            // ── Apariencia / Tema ──
            item {
                val modoTema by viewModel.modoTema.collectAsState()
                Text(
                    text = "APARIENCIA",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.height(8.dp))

                // Selector segmentado limpio tipo iOS / Material 3 Pills
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(4.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        val opciones = listOf(
                            Triple("claro", "Claro", Icons.Default.LightMode),
                            Triple("oscuro", "Oscuro", Icons.Default.DarkMode),
                            Triple("sistema", "Sistema", Icons.Default.SettingsBrightness)
                        )

                        opciones.forEach { (clave, label, icono) ->
                            val seleccionado = modoTema == clave
                            val backgroundPill = if (seleccionado) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
                            val contentPillColor = if (seleccionado) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant

                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = backgroundPill,
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { viewModel.cambiarModoTema(clave) }
                            ) {
                                Row(
                                    modifier = Modifier.padding(vertical = 10.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = icono,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = contentPillColor
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = if (seleccionado) FontWeight.Bold else FontWeight.Normal,
                                        color = contentPillColor
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(20.dp))
            }

            // ── Seguridad ──
            item {
                Text(
                    text = "SEGURIDAD",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.height(8.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(14.dp))
                            Column {
                                Text(
                                    text = "Bloqueo biométrico",
                                    fontWeight = FontWeight.SemiBold,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    text = "Usa tu huella para abrir la app.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Switch(
                            checked = biometriaHabilitada,
                            onCheckedChange = { activo ->
                                if (activo && !BiometricHelper.esBiometriaDisponible(context)) {
                                    Toast.makeText(context, "No tienes huella configurada en el teléfono", Toast.LENGTH_SHORT).show()
                                } else {
                                    viewModel.alternarBiometria(activo)
                                }
                            }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(20.dp))
            }

            // ── Datos y Exportación ──
            item {
                Text(
                    text = "DATOS Y EXPORTACIÓN",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.height(8.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        // Botón Exportar Historial sólido
                        Button(
                            onClick = onExportar,
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Exportar historial", fontWeight = FontWeight.SemiBold)
                        }

                        Text(
                            text = "Descarga tus gastos en CSV o Excel.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp),
                            textAlign = TextAlign.Center
                        )

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                        // Fila Sincronizar ahora con flechita derecha
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.sincronizar() }
                                .padding(vertical = 12.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Sync,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Sincronizar ahora",
                                    fontWeight = FontWeight.SemiBold,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    text = "Actualiza tus datos en la nube.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                        // Fila Buscar actualizaciones
                        val estadoActualizacion by viewModel.estadoActualizacion.collectAsState()
                        val context = LocalContext.current
                        val pInfo = remember {
                            try {
                                context.packageManager.getPackageInfo(context.packageName, 0)
                            } catch (_: Exception) { null }
                        }
                        val vCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                            pInfo?.longVersionCode ?: 1L
                        } else {
                            @Suppress("DEPRECATION")
                            pInfo?.versionCode?.toLong() ?: 1L
                        }
                        val vName = pInfo?.versionName ?: "1.0"

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.buscarActualizaciones(vCode, esManual = true) }
                                .padding(vertical = 12.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.CloudDownload,
                                contentDescription = null,
                                tint = Color(0xFF38BDF8),
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Buscar actualizaciones",
                                    fontWeight = FontWeight.SemiBold,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    text = "Versión instalada: v$vName (Compilación $vCode)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (estadoActualizacion is EstadoActualizacion.Comprobando) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = Color(0xFF38BDF8)
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
            }

            // ── Cerrar Sesión Outlined ──
            item {
                OutlinedButton(
                    onClick = {
                        viewModel.desconectar()
                        onCerrarSesion()
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFEF5350)),
                    border = BorderStroke(1.dp, Color(0xFFEF5350).copy(alpha = 0.5f))
                ) {
                    Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Cerrar sesión", fontWeight = FontWeight.Bold)
                }

                Spacer(modifier = Modifier.height(28.dp))
                val context = LocalContext.current
                val vNameFooter = remember {
                    try { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0" }
                    catch (_: Exception) { "1.0" }
                }
                Text(
                    text = "MisGastos AI · v$vNameFooter",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            }
        }

        // Diálogos de Actualización
        val estadoAct by viewModel.estadoActualizacion.collectAsState()
        val context = LocalContext.current
        val pInfoDialog = remember {
            try { context.packageManager.getPackageInfo(context.packageName, 0) } catch (_: Exception) { null }
        }
        val vNameDialog = pInfoDialog?.versionName ?: "1.0"

        when (val estado = estadoAct) {
            is EstadoActualizacion.Disponible -> {
                AlertDialog(
                    onDismissRequest = {
                        if (!estado.info.esObligatoria) viewModel.cerrarModalActualizacion()
                    },
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.CloudDownload,
                                contentDescription = null,
                                tint = Color(0xFF38BDF8),
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "¡Nueva versión disponible!",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleLarge
                            )
                        }
                    },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = Color(0xFF0F253E),
                                border = BorderStroke(1.dp, Color(0xFF1D4A77))
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "Versión ${estado.info.versionRemota} disponible",
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF76D1FF),
                                        fontSize = 12.sp
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        "(Actual: v$vNameDialog)",
                                        color = Color(0xFF8E9FB8),
                                        fontSize = 11.sp
                                    )
                                }
                            }

                            if (estado.info.novedades.isNotBlank()) {
                                Text(
                                    "Novedades:",
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Color.White
                                )
                                Text(
                                    estado.info.novedades,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFFCBD5E1)
                                )
                            }

                            Text(
                                "Al presionar actualizar, se descargará el instalador. Tus datos, cuentas y registros permanecerán intactos.",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF8E9FB8),
                                fontSize = 11.sp
                            )
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = { viewModel.descargarEInstalarApk(context, estado.info) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Actualizar ahora", fontWeight = FontWeight.Bold)
                        }
                    },
                    dismissButton = {
                        if (!estado.info.esObligatoria) {
                            TextButton(onClick = { viewModel.cerrarModalActualizacion() }) {
                                Text("Más tarde")
                            }
                        }
                    }
                )
            }
            is EstadoActualizacion.Descargando -> {
                AlertDialog(
                    onDismissRequest = {},
                    title = {
                        Text("Descargando actualización...", fontWeight = FontWeight.Bold)
                    },
                    text = {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            LinearProgressIndicator(
                                progress = { estado.progreso },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(50)),
                                color = Color(0xFF38BDF8),
                                trackColor = Color(0xFF1E293B)
                            )
                            Text(
                                "${(estado.progreso * 100).toInt()}% descargado",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF76D1FF)
                            )
                            Text(
                                "Por favor espera un momento mientras se descarga el archivo de instalación.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF8E9FB8),
                                textAlign = TextAlign.Center
                            )
                        }
                    },
                    confirmButton = {}
                )
            }
            is EstadoActualizacion.ListoParaInstalar -> {
                AlertDialog(
                    onDismissRequest = { viewModel.cerrarModalActualizacion() },
                    title = { Text("Descarga completada", fontWeight = FontWeight.Bold) },
                    text = {
                        Text(
                            "El instalador está listo. Toca 'Instalar ahora' para actualizar la app.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    },
                    confirmButton = {
                        Button(
                            onClick = { viewModel.ejecutarInstalacion(context, estado.archivo) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF22C55E))
                        ) {
                            Text("Instalar ahora", fontWeight = FontWeight.Bold)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { viewModel.cerrarModalActualizacion() }) {
                            Text("Cerrar")
                        }
                    }
                )
            }
            is EstadoActualizacion.Error -> {
                AlertDialog(
                    onDismissRequest = { viewModel.cerrarModalActualizacion() },
                    title = { Text("Error al actualizar", fontWeight = FontWeight.Bold, color = Color(0xFFF87171)) },
                    text = { Text(estado.mensaje) },
                    confirmButton = {
                        Button(onClick = { viewModel.cerrarModalActualizacion() }) {
                            Text("Entendido")
                        }
                    }
                )
            }
            else -> {}
        }
    }
}

// ==========================================
// DIÁLOGO: TRANSFERENCIA NEUTRA ENTRE CUENTAS
// ==========================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DialogTransferencia(
    cuentas: List<Cuenta>,
    onTransferir: (String, String, Double, String) -> Unit,
    onCerrar: () -> Unit
) {
    var origenId by remember { mutableStateOf(cuentas.firstOrNull()?.id ?: "") }
    var destinoId by remember { mutableStateOf(cuentas.getOrNull(1)?.id ?: cuentas.firstOrNull()?.id ?: "") }
    var montoStr by remember { mutableStateOf("") }
    var nota by remember { mutableStateOf("") }

    var expandedOrigen by remember { mutableStateOf(false) }
    var expandedDestino by remember { mutableStateOf(false) }

    val cuentaOrigen = cuentas.find { it.id == origenId }
    val cuentaDestino = cuentas.find { it.id == destinoId }

    AlertDialog(
        onDismissRequest = onCerrar,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.SwapHoriz, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Transferir Dinero")
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Mueve dinero entre tus propias cuentas (ej: de BCP a tu Ahorro, o retiro de efectivo) sin contar como gasto del mes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Selector: Cuenta de ORIGEN (Sale dinero)
                Column {
                    Text("De (Cuenta Origen):", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(4.dp))
                    ExposedDropdownMenuBox(
                        expanded = expandedOrigen,
                        onExpandedChange = { expandedOrigen = !expandedOrigen }
                    ) {
                        OutlinedTextField(
                            value = "${cuentaOrigen?.nombre ?: "Seleccionar"} (S/ ${cuentaOrigen?.saldo ?: 0.0})",
                            onValueChange = {},
                            readOnly = true,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedOrigen) },
                            modifier = Modifier.menuAnchor().fillMaxWidth(),
                            singleLine = true
                        )
                        ExposedDropdownMenu(
                            expanded = expandedOrigen,
                            onDismissRequest = { expandedOrigen = false }
                        ) {
                            cuentas.forEach { c ->
                                DropdownMenuItem(
                                    text = { Text("${c.nombre} • S/ ${c.saldo} (${c.tipo})") },
                                    onClick = {
                                        origenId = c.id
                                        expandedOrigen = false
                                    }
                                )
                            }
                        }
                    }
                }

                // Selector: Cuenta de DESTINO (Entra dinero)
                Column {
                    Text("Hacia (Cuenta Destino):", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(4.dp))
                    ExposedDropdownMenuBox(
                        expanded = expandedDestino,
                        onExpandedChange = { expandedDestino = !expandedDestino }
                    ) {
                        OutlinedTextField(
                            value = "${cuentaDestino?.nombre ?: "Seleccionar"} (S/ ${cuentaDestino?.saldo ?: 0.0})",
                            onValueChange = {},
                            readOnly = true,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedDestino) },
                            modifier = Modifier.menuAnchor().fillMaxWidth(),
                            singleLine = true
                        )
                        ExposedDropdownMenu(
                            expanded = expandedDestino,
                            onDismissRequest = { expandedDestino = false }
                        ) {
                            cuentas.filter { it.id != origenId }.forEach { c ->
                                DropdownMenuItem(
                                    text = { Text("${c.nombre} • S/ ${c.saldo} (${c.tipo})") },
                                    onClick = {
                                        destinoId = c.id
                                        expandedDestino = false
                                    }
                                )
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = montoStr,
                    onValueChange = { montoStr = it },
                    label = { Text("Monto a transferir (S/)") },
                    placeholder = { Text("0.00") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = nota,
                    onValueChange = { nota = it },
                    label = { Text("Motivo / Nota (opcional)") },
                    placeholder = { Text("Ej: Ahorro de la semana, retiro cajero") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val m = montoStr.toDoubleOrNull()
                    if (m != null && m > 0 && origenId.isNotBlank() && destinoId.isNotBlank() && origenId != destinoId) {
                        onTransferir(origenId, destinoId, m, nota.ifBlank { "Transferencia entre cuentas" })
                    }
                },
                enabled = origenId != destinoId && (montoStr.toDoubleOrNull() ?: 0.0) > 0
            ) {
                Text("Confirmar Transferencia")
            }
        },
        dismissButton = {
            TextButton(onClick = onCerrar) { Text("Cancelar") }
        }
    )
}

// ==========================================
// DIÁLOGO: NUEVO PAGO FIJO
// ==========================================
@Composable
fun DialogNuevoPagoFijo(
    cuentas: List<Cuenta>,
    onGuardar: (String, Double, Int, String, String, Int) -> Unit,
    onCerrar: () -> Unit
) {
    var nombre by remember { mutableStateOf("") }
    var montoStr by remember { mutableStateOf("") }
    var diaStr by remember { mutableStateOf("1") }
    var categoria by remember { mutableStateOf("Servicios") }
    // 0: Mensual, 1: Quincenal, 2: Semanal, 3: Diario
    var frecuenciaSeleccionada by remember { mutableIntStateOf(0) }

    val cal = remember { java.util.Calendar.getInstance() }
    val diasMaxMes = remember { cal.getActualMaximum(java.util.Calendar.DAY_OF_MONTH) }

    AlertDialog(
        onDismissRequest = onCerrar,
        title = { Text("Nuevo Compromiso Fijo") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = nombre,
                    onValueChange = { nombre = it },
                    label = { Text("Nombre (ej. Banquito, Alquiler, Luz)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = montoStr,
                    onValueChange = { montoStr = it },
                    label = {
                        Text(
                            when (frecuenciaSeleccionada) {
                                1 -> "Monto por quincena (S/)"
                                2 -> "Monto por cada semana (S/)"
                                3 -> "Monto por día (S/)"
                                else -> "Monto mensual (S/)"
                            }
                        )
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                val labelDia = when (frecuenciaSeleccionada) {
                    1 -> "Primer día de quincena (1 a 15)"
                    2 -> "Primer día límite semanal (1 a 7)"
                    3 -> "Día de inicio en el mes (1 a $diasMaxMes)"
                    else -> "Día límite de pago del mes (1 a $diasMaxMes)"
                }

                OutlinedTextField(
                    value = diaStr,
                    onValueChange = { diaStr = it },
                    label = { Text(labelDia) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // Frecuencias: Mensual, Quincenal, Semanal, Diario
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text("Frecuencia de pago:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(
                                selected = frecuenciaSeleccionada == 0,
                                onClick = { frecuenciaSeleccionada = 0 },
                                label = { Text("1 vez al mes") }
                            )
                            FilterChip(
                                selected = frecuenciaSeleccionada == 1,
                                onClick = { frecuenciaSeleccionada = 1 },
                                label = { Text("Quincenal (2 al mes)") }
                            )
                            FilterChip(
                                selected = frecuenciaSeleccionada == 2,
                                onClick = { frecuenciaSeleccionada = 2 },
                                label = { Text("Semanal (~4 al mes)") }
                            )
                            FilterChip(
                                selected = frecuenciaSeleccionada == 3,
                                onClick = { frecuenciaSeleccionada = 3 },
                                label = { Text("Diario") }
                            )
                        }

                        val descripcionFrecuencia = when (frecuenciaSeleccionada) {
                            1 -> "Genera automáticamente 2 pagos programados en el mes (ej: día base y +15 días)."
                            2 -> "Genera automáticamente pagos correlativos cada 7 días según los días del mes actual."
                            3 -> "Registra el pago fijo distribuido para control diario durante el mes."
                            else -> "Se pagará una única vez en la fecha límite del mes indicada."
                        }

                        Text(
                            text = descripcionFrecuencia,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val m = montoStr.toDoubleOrNull()
                val maxLimiteDia = when (frecuenciaSeleccionada) {
                    1 -> 15
                    2 -> 7
                    else -> diasMaxMes
                }
                val d = diaStr.toIntOrNull()?.coerceIn(1, maxLimiteDia) ?: 1
                if (nombre.isNotBlank() && m != null && m > 0) {
                    onGuardar(nombre, m, d, categoria, cuentas.firstOrNull()?.id ?: "cuenta_bcp_yape", frecuenciaSeleccionada)
                }
            }) {
                Text("Guardar")
            }
        },
        dismissButton = {
            TextButton(onClick = onCerrar) {
                Text("Cancelar")
            }
        }
    )
}
