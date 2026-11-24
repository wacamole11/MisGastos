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
        const val ACTION_VOZ = "com.gastos.app.widget.ACTION_VOZ"
        const val ACTION_NUEVO_CONSUMO = "com.gastos.app.widget.ACTION_NUEVO_CONSUMO"
        const val EXTRA_CATEGORIA = "extra_categoria"
        const val EXTRA_WIDGET_ID = "extra_widget_id"

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
            var saldoDisponibleTexto = "Disp: S/ --"
            var ultimoGastoTexto: String? = null

            try {
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

                saldoDisponibleTexto = "Disp: " + NumberFormat.getCurrencyInstance(Locale("es", "PE")).format(saldoLibre)

                val ultimos = db.gastoDao().todosLista()
                val ultimo = ultimos.firstOrNull()
                if (ultimo != null) {
                    val catEmoji = when (ultimo.categoria.lowercase()) {
                        "comida" -> "🍱"
                        "transporte" -> "🚗"
                        "hogar" -> "🏠"
                        "servicios" -> "💡"
                        "salud" -> "💊"
                        "ocio" -> "🎮"
                        else -> "📦"
                    }
                    ultimoGastoTexto = "Último: S/ ${"%.2f".format(ultimo.monto)} en $catEmoji ${ultimo.categoria}"
                }
            } catch (_: Exception) {}

            withContext(Dispatchers.Main) {
                val views = RemoteViews(context.packageName, R.layout.widget_acceso_rapido)
                val feedback = prefs.getString("feedback_$appWidgetId", null)

                // 1. Encabezado e información
                views.setTextViewText(R.id.widget_txt_info, saldoDisponibleTexto)

                // 2. Mensaje en el Chatsito
                if (feedback != null) {
                    views.setTextViewText(R.id.widget_chat_message, feedback)
                    views.setTextViewText(R.id.widget_chat_subtext, "✓ Registro completado · Toca para nuevo gasto")
                } else if (ultimoGastoTexto != null) {
                    views.setTextViewText(
                        R.id.widget_chat_message,
                        "$ultimoGastoTexto\n¿Tuviste otro gasto hoy? Toca para dictar o escribir."
                    )
                    views.setTextViewText(R.id.widget_chat_subtext, "Copiloto MisGastos · En línea")
                } else {
                    views.setTextViewText(
                        R.id.widget_chat_message,
                        "¡Hola! 👋 ¿Qué compraste hoy? Tócame para dictar por voz o escribir tu gasto."
                    )
                    views.setTextViewText(R.id.widget_chat_subtext, "Toca para responder o registrar gasto")
                }

                // 3. PendingIntent para abrir la app desde el encabezado
                val intentApp = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                val pendingApp = PendingIntent.getActivity(
                    context,
                    200 + appWidgetId,
                    intentApp,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                views.setOnClickPendingIntent(R.id.widget_btn_open_app, pendingApp)

                // 4. PendingIntent para dictar por voz (Botón Micrófono)
                val intentVoz = Intent(context, MainActivity::class.java).apply {
                    action = ACTION_VOZ
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                val pendingVoz = PendingIntent.getActivity(
                    context,
                    210 + appWidgetId,
                    intentVoz,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                views.setOnClickPendingIntent(R.id.widget_btn_voice, pendingVoz)

                // 5. PendingIntent para entrada de chat / escribir (Burbuja, Barra y Botón Enviar)
                val intentChat = Intent(context, MainActivity::class.java).apply {
                    action = ACTION_NUEVO_CONSUMO
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                val pendingChat = PendingIntent.getActivity(
                    context,
                    220 + appWidgetId,
                    intentChat,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                views.setOnClickPendingIntent(R.id.widget_chat_bubble, pendingChat)
                views.setOnClickPendingIntent(R.id.widget_chat_bar, pendingChat)
                views.setOnClickPendingIntent(R.id.widget_btn_write, pendingChat)

                // 6. PendingIntents para Chips Rápidos de Categoría
                vincularChip(context, views, R.id.widget_chip_1, "Comida", appWidgetId, 231)
                vincularChip(context, views, R.id.widget_chip_2, "Transporte", appWidgetId, 232)
                vincularChip(context, views, R.id.widget_chip_3, "Hogar", appWidgetId, 233)
                vincularChip(context, views, R.id.widget_chip_4, "Ocio", appWidgetId, 234)

                appWidgetManager.updateAppWidget(appWidgetId, views)
            }
        }
    }

    private fun vincularChip(
        context: Context,
        views: RemoteViews,
        viewId: Int,
        categoria: String,
        appWidgetId: Int,
        requestCode: Int
    ) {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_NUEVO_CONSUMO
            putExtra(EXTRA_CATEGORIA, categoria)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            requestCode + appWidgetId * 10,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(viewId, pendingIntent)
    }
}
