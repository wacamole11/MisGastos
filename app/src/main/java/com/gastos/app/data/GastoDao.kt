package com.gastos.app.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface GastoDao {
    // === GASTOS ===
    @Query("SELECT * FROM gastos ORDER BY fecha DESC, id DESC")
    fun todos(): Flow<List<Gasto>>

    @Query("SELECT * FROM gastos ORDER BY fecha DESC, id DESC")
    suspend fun todosLista(): List<Gasto>

    @Query("SELECT * FROM gastos WHERE montoPorCobrar > 0 AND cobrado = 0 ORDER BY fecha DESC")
    fun porCobrar(): Flow<List<Gasto>>

    @Query("SELECT * FROM gastos WHERE sincronizado = 0 ORDER BY id ASC")
    suspend fun pendientes(): List<Gasto>

    @Query("UPDATE gastos SET sincronizado = 1 WHERE id = :id")
    suspend fun marcarSincronizado(id: String)

    @Query("UPDATE gastos SET cobrado = 1 WHERE id = :id")
    suspend fun marcarCobrado(id: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertar(gasto: Gasto): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertarTodos(gastos: List<Gasto>)

    @Delete
    suspend fun eliminar(gasto: Gasto)

    @Query("DELETE FROM gastos WHERE id = :id")
    suspend fun eliminarPorId(id: String)

    @Query("DELETE FROM gastos")
    suspend fun limpiarTodos()

    @Query("DELETE FROM cuentas")
    suspend fun limpiarCuentas()

    @Query("DELETE FROM pagos_fijos")
    suspend fun limpiarPagosFijos()

    // === CUENTAS ===
    @Query("SELECT * FROM cuentas ORDER BY tipo ASC, nombre ASC")
    fun todasCuentas(): Flow<List<Cuenta>>

    @Query("SELECT * FROM cuentas ORDER BY tipo ASC, nombre ASC")
    suspend fun todasCuentasLista(): List<Cuenta>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertarCuenta(cuenta: Cuenta)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertarCuentas(cuentas: List<Cuenta>)

    @Query("UPDATE cuentas SET saldo = :nuevoSaldo WHERE id = :cuentaId")
    suspend fun actualizarSaldo(cuentaId: String, nuevoSaldo: Double)

    @Delete
    suspend fun eliminarCuenta(cuenta: Cuenta)

    // === PAGOS FIJOS ===
    @Query("SELECT * FROM pagos_fijos ORDER BY diaPago ASC")
    fun todosPagosFijos(): Flow<List<PagoFijo>>

    @Query("SELECT * FROM pagos_fijos ORDER BY diaPago ASC")
    suspend fun todosPagosFijosLista(): List<PagoFijo>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertarPagoFijo(pago: PagoFijo)

    @Update
    suspend fun actualizarPagoFijo(pago: PagoFijo)

    @Query("UPDATE pagos_fijos SET ultimoMesPagado = :mes WHERE id = :id")
    suspend fun marcarPagoFijoMes(id: String, mes: String)

    @Delete
    suspend fun eliminarPagoFijo(pago: PagoFijo)
}
