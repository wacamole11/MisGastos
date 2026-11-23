package com.gastos.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "pagos_fijos")
data class PagoFijo(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val nombre: String,
    val monto: Double,
    val diaPago: Int, // Día del mes (1 - 31)
    val categoria: String = "Hogar",
    val cuentaId: String = "cuenta_bcp_yape",
    val ultimoMesPagado: String = "" // Formato "yyyy-MM", e.g. "2026-11"
)
