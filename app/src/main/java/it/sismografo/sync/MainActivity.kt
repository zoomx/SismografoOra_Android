package it.sismografo.sync

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Collega il telefono (USB OTG) al sismografo SensorTile.box e gli imposta l'ora.
 * Il comando inviato e':  H aaaa-mm-gg hh:mm:ss 0   (ora UTC del telefono, scarto 0)
 * La scheda deve essere nella sua finestra di servizio (15 s dopo il reset, oppure premi USER).
 */
class MainActivity : Activity(), SerialInputOutputManager.Listener {

    private val actionUsbPermission = "it.sismografo.sync.USB_PERMISSION"

    private lateinit var usbManager: UsbManager
    private lateinit var tvStato: TextView
    private lateinit var tvLog: TextView
    private lateinit var scrollLog: ScrollView
    private lateinit var etCmd: EditText

    private var port: UsbSerialPort? = null
    private var ioManager: SerialInputOutputManager? = null

    private val permessoReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == actionUsbPermission) {
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    connetti()
                } else {
                    stato("Permesso USB negato")
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        tvStato = findViewById(R.id.tvStato)
        tvLog = findViewById(R.id.tvLog)
        scrollLog = findViewById(R.id.scrollLog)
        etCmd = findViewById(R.id.etCmd)

        findViewById<Button>(R.id.btnConnetti).setOnClickListener { connetti() }
        findViewById<Button>(R.id.btnOra).setOnClickListener { impostaOra() }
        findViewById<Button>(R.id.btnStato).setOnClickListener { invia("?") }
        findViewById<Button>(R.id.btnSd).setOnClickListener { invia("SD") }
        findViewById<Button>(R.id.btnEsci).setOnClickListener { invia("X") }
        findViewById<Button>(R.id.btnInvia).setOnClickListener {
            val testo = etCmd.text.toString().trim()
            if (testo.isNotEmpty()) invia(testo)
        }

        val filtro = IntentFilter(actionUsbPermission)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(permessoReceiver, filtro, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(permessoReceiver, filtro)
        }
    }

    override fun onDestroy() {
        chiudi()
        unregisterReceiver(permessoReceiver)
        super.onDestroy()
    }

    // ------------------------------------------------------------------ connessione
    private fun connetti() {
        chiudi()
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        if (drivers.isEmpty()) {
            stato("Nessuna porta seriale USB trovata. Controlla cavo OTG, scheda accesa e finestra di servizio.")
            return
        }
        val driver = drivers[0]
        if (!usbManager.hasPermission(driver.device)) {
            val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val intent = Intent(actionUsbPermission).setPackage(packageName)
            val pi = PendingIntent.getBroadcast(this, 0, intent, flags)
            usbManager.requestPermission(driver.device, pi)
            stato("Concedi il permesso USB nella finestra di Android...")
            return
        }
        apri(driver)
    }

    private fun apri(driver: UsbSerialDriver) {
        val connessione = usbManager.openDevice(driver.device)
        if (connessione == null) {
            stato("Impossibile aprire il dispositivo USB")
            return
        }
        val p = driver.ports[0]
        try {
            p.open(connessione)
            p.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            try {
                // La scheda considera "collegato un host" quando il DTR e' attivo
                p.setDTR(true)
                p.setRTS(true)
            } catch (e: Exception) {
                log("(DTR non impostabile: ${e.message})")
            }
            port = p
            ioManager = SerialInputOutputManager(p, this).also { it.start() }
            stato("Connesso. Se la scheda non risponde premi USER su di essa e riprova.")
        } catch (e: Exception) {
            stato("Errore di connessione: ${e.message}")
            try { p.close() } catch (_: Exception) {}
            port = null
        }
    }

    private fun chiudi() {
        ioManager?.let {
            it.listener = null
            it.stop()
        }
        ioManager = null
        try { port?.close() } catch (_: Exception) {}
        port = null
    }

    // ------------------------------------------------------------------ invio
    private fun invia(testo: String) {
        val p = port
        if (p == null) {
            stato("Non connesso: premi prima 'Connetti'")
            return
        }
        Thread {
            try {
                p.write((testo + "\n").toByteArray(Charsets.UTF_8), 2000)
                log(">> $testo")
            } catch (e: Exception) {
                log("Errore invio: ${e.message}")
            }
        }.start()
    }

    /** Aspetta l'inizio del secondo successivo e invia quell'ora UTC: errore di pochi ms. */
    private fun impostaOra() {
        val p = port
        if (p == null) {
            stato("Non connesso: premi prima 'Connetti'")
            return
        }
        Thread {
            try {
                val adesso = System.currentTimeMillis()
                val prossimo = (adesso / 1000 + 1) * 1000
                val attesa = prossimo - System.currentTimeMillis()
                if (attesa > 0) Thread.sleep(attesa)
                val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                fmt.timeZone = TimeZone.getTimeZone("UTC")
                val comando = "H ${fmt.format(Date(prossimo))} 0"
                p.write((comando + "\n").toByteArray(Charsets.UTF_8), 2000)
                log(">> $comando")
            } catch (e: Exception) {
                log("Errore invio: ${e.message}")
            }
        }.start()
    }

    // ------------------------------------------------------------------ ricezione
    override fun onNewData(data: ByteArray) {
        val testo = String(data, Charsets.UTF_8)
        runOnUiThread {
            tvLog.append(testo)
            scrollLog.post { scrollLog.fullScroll(View.FOCUS_DOWN) }
        }
    }

    override fun onRunError(e: Exception) {
        log("Connessione interrotta: ${e.message}")
        runOnUiThread { chiudi(); stato("Disconnesso") }
    }

    // ------------------------------------------------------------------ UI
    private fun stato(testo: String) = runOnUiThread { tvStato.text = testo }

    private fun log(riga: String) = runOnUiThread {
        tvLog.append(riga + "\n")
        scrollLog.post { scrollLog.fullScroll(View.FOCUS_DOWN) }
    }
}
