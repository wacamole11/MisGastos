# 📱 MisGastos AI

> **Gestión Financiera Inteligente en Tiempo Real con IA**  
> Una aplicación Android moderna, intuitiva y potente diseñada para el control total de finanzas personales, presupuestos, ingresos y compromisos en Perú (Soles - S/).

---

## ✨ Características Principales

* **🤖 Copiloto Financiero con IA:** Asistente inteligente potenciado por Gemini para evaluar compras, analizar ritmo de consumo y sugerir ahorros.
* **🎙️ Registro Rápido por Voz y Texto:** Dicta o escribe tus transacciones rápidamente (*"Cena 15 con Yape"*, *"Taxi 20 efectivo"*).
* **🏦 Gestión Integral de Cuentas:**
  * Efectivo y Billeteras Físicas.
  * Bancos y Billeteras Digitales (BCP, Yape, Interbank, Plin, BBVA, Scotiabank, Cajas).
  * Fondos de Ahorro y Metas de inversión con barra de progreso.
* **📋 Deudas y Préstamos (Nos deben / Debemos):**
  * Control de préstamos otorgados y deudas adquiridas.
  * Registro de fecha de entrega/recepción.
  * Cálculo de intereses opcionales (% porcentaje o monto fijo en S/).
  * Fecha límite de pago con cálculo de días restantes o alerta de vencimiento.
* **🗓️ Calendario Financiero Mensual:**
  * Vista interactiva de días con ingresos proyectados (sueldos, extras), compromisos fijos y vencimiento de deudas.
  * Botones de liquidación directa (*Cobrar* o *Pagar*) que actualizan automáticamente los saldos contables.
* **🔒 Seguridad Biométrica:** Acceso seguro mediante huella dactilar o reconocimiento facial.
* **☁️ Sincronización en la Nube:** Respaldo y sincronización en tiempo real con Firebase Firestore.
* **📊 Exportación de Datos:** Descarga de historial completo de movimientos en formato CSV/Excel.

---

## 🛠️ Stack Tecnológico

* **Lenguaje:** Kotlin
* **UI Toolkit:** Jetpack Compose + Material 3 (Tema Oscuro Navy moderno)
* **Arquitectura:** MVVM (Model-View-ViewModel) con StateFlow y Corrutinas
* **Base de Datos Local:** Room Database (SQLite)
* **Base de Datos en la Nube:** Firebase Firestore
* **Autenticación:** Firebase Authentication + Google Sign-In
* **Seguridad:** AndroidX Biometric API
* **Inteligencia Artificial:** Google Gemini Interactions API

---

## 🚀 Instalación y Compilación

1. Clona este repositorio:
   ```bash
   git clone https://github.com/wacamole11/MisGastos.git
   ```
2. Abre el proyecto en **Android Studio Ladybug (o superior)**.
3. Asegúrate de contar con el archivo `google-services.json` configurado en la carpeta `/app`.
4. Compila y ejecuta en tu dispositivo o emulador:
   ```bash
   ./gradlew assembleDebug
   ```

---

## 📄 Licencia

Este proyecto está bajo la licencia MIT. Creado por [Anthony](https://github.com/wacamole11).
