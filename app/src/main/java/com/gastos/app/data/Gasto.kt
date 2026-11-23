package com.gastos.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "gastos")
data class Gasto(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val monto: Double,
    val categoria: String,
    val nota: String = "",
    val fecha: Long,
    val esIngreso: Boolean = false,
    val sincronizado: Boolean = false,
    val cuentaId: String = "cuenta_bcp_yape",
    val esTransferencia: Boolean = false,
    val cuentaDestinoId: String? = null,
    val montoPorCobrar: Double = 0.0,
    val deudor: String = "",
    val cobrado: Boolean = false,
    val codigoOperacion: String = ""
) {
    // Monto real que afecta al usuario (restando lo que le deben amigos)
    val montoRealUsuario: Double
        get() = if (esTransferencia) monto else (monto - montoPorCobrar).coerceAtLeast(0.0)

    companion object {
        val CATEGORIAS = listOf(
            "Sueldo/Ingresos",
            "Comida",
            "Transporte",
            "Hogar",
            "Servicios",
            "Salud",
            "Ocio",
            "Educación",
            "Ropa",
            "Ahorro/Inversión",
            "Otros"
        )
    }
}
