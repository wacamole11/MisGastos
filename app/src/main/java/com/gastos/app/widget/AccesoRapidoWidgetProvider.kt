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
        const val ACTION_NUEVO_CONSUMO = "com.gastos.app.ACTION_NUEVO_CONSUMO"
        const val ACTION_VOZ = "com.gastos.app.ACTION_VOZ"

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

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
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

                val format = NumberFormat.getCurrencyInstance(Locale("es", "PE"))
                val saldoTexto = format.format(saldoLibre)

                withContext(Dispatchers.Main) {
                    for (appWidgetId in appWidgetIds) {
                        val views = RemoteViews(context.packageName, R.layout.widget_acceso_rapido)

                        // 1. Mostrar saldo disponible hoy
                        views.setTextViewText(R.id.widget_txt_saldo, saldoTexto)

                        // 2. Clic en contenedor general -> Abre la app
                        val intentApp = Intent(context, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        }
                        val pendingApp = PendingIntent.getActivity(
                            context,
                            0,
                            intentApp,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )
                        views.setOnClickPendingIntent(R.id.widget_container, pendingApp)

                        // 3. Clic en botón "Consumo" -> Abre directo al formulario de nuevo gasto
                        val intentConsumo = Intent(context, MainActivity::class.java).apply {
                            action = ACTION_NUEVO_CONSUMO
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        }
                        val pendingConsumo = PendingIntent.getActivity(
                            context,
                            1,
                            intentConsumo,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )
                        views.setOnClickPendingIntent(R.id.widget_btn_nuevo_consumo, pendingConsumo)

                        // 4. Clic en botón "Por Voz" -> Abre e inicia dictado por voz
                        val intentVoz = Intent(context, MainActivity::class.java).apply {
                            action = ACTION_VOZ
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        }
                        val pendingVoz = PendingIntent.getActivity(
                            context,
                            2,
                            intentVoz,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )
                        views.setOnClickPendingIntent(R.id.widget_btn_voz, pendingVoz)

                        appWidgetManager.updateAppWidget(appWidgetId, views)
                    }
                }
            } catch (_: Exception) {
            } finally {
                pendingResult.finish()
            }
        }
    }
}
