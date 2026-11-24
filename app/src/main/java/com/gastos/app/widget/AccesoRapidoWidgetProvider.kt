package com.gastos.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.gastos.app.MainActivity
import com.gastos.app.R
import com.gastos.app.data.AppDatabase
import com.gastos.app.data.Cuenta
import com.gastos.app.data.FirestoreRepository
import com.gastos.app.data.Gasto
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class AccesoRapidoWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_DIGIT = "com.gastos.app.widget.ACTION_DIGIT"
        const val ACTION_DEL = "com.gastos.app.widget.ACTION_DEL"
        const val ACTION_CLR = "com.gastos.app.widget.ACTION_CLR"
        const val ACTION_CYCLE_CAT = "com.gastos.app.widget.ACTION_CYCLE_CAT"
        const val ACTION_CYCLE_CTA = "com.gastos.app.widget.ACTION_CYCLE_CTA"
        const val ACTION_GUARDAR = "com.gastos.app.widget.ACTION_GUARDAR"
        const val ACTION_VOZ = "com.gastos.app.widget.ACTION_VOZ"
        const val ACTION_NUEVO_CONSUMO = "com.gastos.app.widget.ACTION_NUEVO_CONSUMO"

        const val EXTRA_DIGIT = "extra_digit"
        const val EXTRA_WIDGET_ID = "extra_widget_id"

        val CATEGORIAS = listOf(
            "Comida" to "🍱 Comida",
            "Transporte" to "🚗 Transporte",
            "Hogar" to "🏠 Hogar",
            "Servicios" to "💡 Servicios",
            "Salud" to "💊 Salud",
            "Ocio" to "🎮 Ocio",
            "Otros" to "📦 Otros"
        )

        val CUENTAS = listOf(
            "cuenta_bcp_yape" to "🟣 Yape",
            "cuenta_efectivo" to "💵 Efectivo",
            "cuenta_bcp" to "🏦 BCP",
            "cuenta_interbank" to "🟢 Interbank",
            "cuenta_bbva" to "🔵 BBVA"
        )

        fun actualizarWidgets(context: Context) {
            try {
                val appWidgetManager = AppWidgetManager.getInstance(context)
                val thisWidget = ComponentName(context, AccesoRapidoWidgetProvider::class.java)
                val appWidgetIds = appWidgetManager.getAppWidgetIds(thisWidget)
                if (appWidgetIds != null && appWidgetIds.isNotEmpty()) {
                    val intent = Intent(context, AccesoRapidoWidgetProvider::class.java).apply {
                        action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                        putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, appWidgetIds)
                    }
                    context.sendBroadcast(intent)
                }
            } catch (_: Exception) {}
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val action = intent.action ?: return
        val appWidgetId = intent.getIntExtra(EXTRA_WIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val prefs = context.getSharedPreferences("widget_state", Context.MODE_PRIVATE)

        when (action) {
            ACTION_DIGIT -> {
                val digit = intent.getStringExtra(EXTRA_DIGIT) ?: return
                var monto = prefs.getString("monto_$appWidgetId", "") ?: ""
                prefs.edit().remove("feedback_$appWidgetId").apply()

                if (digit == ".") {
                    if (!monto.contains(".") && monto.length < 8) {
                        monto = if (monto.isEmpty()) "0." else "$monto."
                    }
                } else {
                    if (monto == "0") monto = digit
                    else if (monto.length < 8) monto += digit
                }
                prefs.edit().putString("monto_$appWidgetId", monto).apply()
                actualizarUnWidget(context, appWidgetId)
            }

            ACTION_DEL -> {
                prefs.edit().remove("feedback_$appWidgetId").apply()
                val monto = prefs.getString("monto_$appWidgetId", "") ?: ""
                if (monto.isNotEmpty()) {
                    prefs.edit().putString("monto_$appWidgetId", monto.dropLast(1)).apply()
                }
                actualizarUnWidget(context, appWidgetId)
            }

            ACTION_CLR -> {
                prefs.edit()
                    .remove("feedback_$appWidgetId")
                    .putString("monto_$appWidgetId", "")
                    .apply()
                actualizarUnWidget(context, appWidgetId)
            }

            ACTION_CYCLE_CAT -> {
                val current = prefs.getInt("cat_idx_$appWidgetId", 0)
                prefs.edit().putInt("cat_idx_$appWidgetId", (current + 1) % CATEGORIAS.size).apply()
                actualizarUnWidget(context, appWidgetId)
            }

            ACTION_CYCLE_CTA -> {
                val current = prefs.getInt("cta_idx_$appWidgetId", 0)
                prefs.edit().putInt("cta_idx_$appWidgetId", (current + 1) % CUENTAS.size).apply()
                actualizarUnWidget(context, appWidgetId)
            }

            ACTION_GUARDAR -> {
                val montoStr = prefs.getString("monto_$appWidgetId", "") ?: ""
                val monto = montoStr.toDoubleOrNull()
                if (monto == null || monto <= 0.0) {
                    prefs.edit().putString("feedback_$appWidgetId", "¡Digita un monto!").apply()
                    actualizarUnWidget(context, appWidgetId)
                    return
                }

                val catIdx = prefs.getInt("cat_idx_$appWidgetId", 0) % CATEGORIAS.size
                val ctaIdx = prefs.getInt("cta_idx_$appWidgetId", 0) % CUENTAS.size
                val catNombre = CATEGORIAS[catIdx].first
                val ctaId = CUENTAS[ctaIdx].first

                val pendingResult = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val db = AppDatabase.obtener(context)
                        val nuevoGasto = Gasto(
                            monto = monto,
                            categoria = catNombre,
                            nota = "Directo desde Widget",
                            fecha = System.currentTimeMillis(),
                            esIngreso = false,
                            sincronizado = false,
                            cuentaId = ctaId
                        )
                        db.gastoDao().insertar(nuevoGasto)

                        val cuentas = db.gastoDao().todasCuentasLista()
                        val targetCuenta = cuentas.find { it.id == ctaId }
                        if (targetCuenta != null) {
                            db.gastoDao().actualizarSaldo(ctaId, targetCuenta.saldo - monto)
                        }

                        try {
                            if (FirebaseAuth.getInstance().currentUser != null) {
                                FirestoreRepository.guardar(nuevoGasto)
                                if (targetCuenta != null) {
                                    FirestoreRepository.guardarCuenta(targetCuenta.copy(saldo = targetCuenta.saldo - monto))
                                }
                            }
                        } catch (_: Exception) {}

                        prefs.edit()
                            .putString("monto_$appWidgetId", "")
                            .putString("feedback_$appWidgetId", "✓ S/ ${"%.2f".format(monto)} en $catNombre guardado")
                            .apply()

                        withContext(Dispatchers.Main) {
                            actualizarUnWidget(context, appWidgetId)
                        }
                    } catch (e: Exception) {
                        prefs.edit().putString("feedback_$appWidgetId", "Error al guardar").apply()
                        withContext(Dispatchers.Main) {
                            actualizarUnWidget(context, appWidgetId)
                        }
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            actualizarUnWidget(context, appWidgetId)
        }
    }

    private fun actualizarUnWidget(context: Context, appWidgetId: Int) {
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val prefs = context.getSharedPreferences("widget_state", Context.MODE_PRIVATE)

        CoroutineScope(Dispatchers.IO).launch {
            val saldoDisponibleTexto = try {
                val db = AppDatabase.obtener(context)
                val cuentas = db.gastoDao().todasCuentasLista()
                val pagosFijos = db.gastoDao().todosPagosFijosLista()

                val disponibleTotal = cuentas.filter { it.tipo == Cuenta.TIPO_DISPONIBLE }.sumOf { it.saldo }
                val mesActual = SimpleDateFormat("yyyy-MM", Locale.US).format(Date())
                val fijosPendientes = pagosFijos.filter { it.ultimoMesPagado != mesActual }.sumOf { it.monto }

                val cal = Calendar.getInstance()
                val diaHoy = cal.get(Calendar.DAY_OF_MONTH)
                val totalDias = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
                val diasRestantes = (totalDias - diaHoy + 1).coerceAtLeast(1)
                val saldoLibre = ((disponibleTotal - fijosPendientes) / diasRestantes).coerceAtLeast(0.0)

                NumberFormat.getCurrencyInstance(Locale("es", "PE")).format(saldoLibre)
            } catch (_: Exception) {
                "S/ --.--"
            }

            withContext(Dispatchers.Main) {
                val views = RemoteViews(context.packageName, R.layout.widget_acceso_rapido)

                val montoStr = prefs.getString("monto_$appWidgetId", "") ?: ""
                val feedback = prefs.getString("feedback_$appWidgetId", null)
                val catIdx = prefs.getInt("cat_idx_$appWidgetId", 0) % CATEGORIAS.size
                val ctaIdx = prefs.getInt("cta_idx_$appWidgetId", 0) % CUENTAS.size

                // 1. Display
                if (feedback != null) {
                    views.setTextViewText(R.id.widget_txt_monto, feedback)
                    views.setTextViewText(R.id.widget_txt_info, "Disponible: $saldoDisponibleTexto")
                } else if (montoStr.isNotEmpty()) {
                    views.setTextViewText(R.id.widget_txt_monto, "S/ $montoStr")
                    views.setTextViewText(R.id.widget_txt_info, "Disponible: $saldoDisponibleTexto")
                } else {
                    views.setTextViewText(R.id.widget_txt_monto, "S/ 0.00")
                    views.setTextViewText(R.id.widget_txt_info, "Disponible: $saldoDisponibleTexto")
                }

                // 2. Selectores de Categoría y Cuenta
                views.setTextViewText(R.id.widget_key_cat, CATEGORIAS[catIdx].second)
                views.setTextViewText(R.id.widget_key_cta, CUENTAS[ctaIdx].second)

                // 3. Vincular PendingIntents del Teclado
                vincularBotonDigito(context, views, R.id.widget_key_0, "0", appWidgetId, 100)
                vincularBotonDigito(context, views, R.id.widget_key_1, "1", appWidgetId, 101)
                vincularBotonDigito(context, views, R.id.widget_key_2, "2", appWidgetId, 102)
                vincularBotonDigito(context, views, R.id.widget_key_3, "3", appWidgetId, 103)
                vincularBotonDigito(context, views, R.id.widget_key_4, "4", appWidgetId, 104)
                vincularBotonDigito(context, views, R.id.widget_key_5, "5", appWidgetId, 105)
                vincularBotonDigito(context, views, R.id.widget_key_6, "6", appWidgetId, 106)
                vincularBotonDigito(context, views, R.id.widget_key_7, "7", appWidgetId, 107)
                vincularBotonDigito(context, views, R.id.widget_key_8, "8", appWidgetId, 108)
                vincularBotonDigito(context, views, R.id.widget_key_9, "9", appWidgetId, 109)
                vincularBotonDigito(context, views, R.id.widget_key_dot, ".", appWidgetId, 110)

                vincularBotonAccion(context, views, R.id.widget_key_del, ACTION_DEL, appWidgetId, 111)
                vincularBotonAccion(context, views, R.id.widget_key_clr, ACTION_CLR, appWidgetId, 112)
                vincularBotonAccion(context, views, R.id.widget_key_cat, ACTION_CYCLE_CAT, appWidgetId, 113)
                vincularBotonAccion(context, views, R.id.widget_key_cta, ACTION_CYCLE_CTA, appWidgetId, 114)
                vincularBotonAccion(context, views, R.id.widget_key_save, ACTION_GUARDAR, appWidgetId, 115)

                // 4. Abrir app completa (solo desde el título o logo)
                val intentApp = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                val pendingApp = PendingIntent.getActivity(
                    context,
                    120 + appWidgetId,
                    intentApp,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                views.setOnClickPendingIntent(R.id.widget_btn_open_app, pendingApp)

                // 5. Botón Voz (abre directamente dictado)
                val intentVoz = Intent(context, MainActivity::class.java).apply {
                    action = ACTION_VOZ
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                val pendingVoz = PendingIntent.getActivity(
                    context,
                    130 + appWidgetId,
                    intentVoz,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                views.setOnClickPendingIntent(R.id.widget_btn_voice, pendingVoz)

                appWidgetManager.updateAppWidget(appWidgetId, views)
            }
        }
    }

    private fun vincularBotonDigito(context: Context, views: RemoteViews, viewId: Int, digit: String, appWidgetId: Int, reqCodeBase: Int) {
        val intent = Intent(context, AccesoRapidoWidgetProvider::class.java).apply {
            action = ACTION_DIGIT
            putExtra(EXTRA_DIGIT, digit)
            putExtra(EXTRA_WIDGET_ID, appWidgetId)
        }
        val pending = PendingIntent.getBroadcast(
            context,
            reqCodeBase * 1000 + appWidgetId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(viewId, pending)
    }

    private fun vincularBotonAccion(context: Context, views: RemoteViews, viewId: Int, action: String, appWidgetId: Int, reqCodeBase: Int) {
        val intent = Intent(context, AccesoRapidoWidgetProvider::class.java).apply {
            this.action = action
            putExtra(EXTRA_WIDGET_ID, appWidgetId)
        }
        val pending = PendingIntent.getBroadcast(
            context,
            reqCodeBase * 1000 + appWidgetId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(viewId, pending)
    }
}
