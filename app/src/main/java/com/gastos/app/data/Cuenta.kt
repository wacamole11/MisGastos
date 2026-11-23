package com.gastos.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "cuentas")
data class Cuenta(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val nombre: String,
    val tipo: String, // "DISPONIBLE", "AHORRO", "INVERSION"
    val saldo: Double = 0.0,
    val colorHex: Long = 0xFF2196F3,
    val icono: String = "wallet"
) {
    companion object {
        const val TIPO_DISPONIBLE = "DISPONIBLE"
        const val TIPO_AHORRO = "AHORRO"

        const val TIPO_INVERSION = "INVERSION"

        fun cuentasPorDefecto(): List<Cuenta> = listOf(
            Cuenta(
                id = "cuenta_efectivo",
                nombre = "Efectivo / Billetera",
                tipo = TIPO_DISPONIBLE,
                saldo = 0.0,
                colorHex = 0xFF4CAF50,
                icono = "payments"
            ),
            Cuenta(
                id = "cuenta_bcp_yape",
                nombre = "BCP / Yape",
                tipo = TIPO_DISPONIBLE,
                saldo = 0.0,
                colorHex = 0xFF7B1FA2,
                icono = "account_balance"
            ),
            Cuenta(
                id = "cuenta_ahorro",
                nombre = "Bolsillo Ahorro",
                tipo = TIPO_AHORRO,
                saldo = 0.0,
                colorHex = 0xFF009688,
                icono = "savings"
            ),
            Cuenta(
                id = "cuenta_inversion",
                nombre = "Fondo Inversión",
                tipo = TIPO_INVERSION,
                saldo = 0.0,
                colorHex = 0xFFFF9800,
                icono = "trending_up"
            )
        )
    }
}
