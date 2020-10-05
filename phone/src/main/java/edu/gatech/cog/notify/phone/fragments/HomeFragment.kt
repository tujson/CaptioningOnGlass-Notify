package edu.gatech.cog.notify.phone.fragments

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.fragment.app.Fragment
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import edu.gatech.cog.notify.common.CHUNK_SIZE
import edu.gatech.cog.notify.common.NOTIFICATION
import edu.gatech.cog.notify.common.cogNotifyUUID
import edu.gatech.cog.notify.common.models.GlassNotification
import edu.gatech.cog.notify.phone.R
import kotlinx.android.synthetic.main.fragment_home.*
import java.io.IOException
import java.nio.charset.Charset
import java.util.concurrent.atomic.AtomicBoolean


private val TAG = HomeFragment::class.java.simpleName

class HomeFragment : Fragment(R.layout.fragment_home) {

    private lateinit var connectedThread: ConnectedThread

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        connectedThread = ConnectedThread()
        setQrCode()

        btnNotify.setOnClickListener {
            val notifyText = etMessage.text.toString()
            val isVibrate = toggleVibrate.isChecked
            connectedThread.write(GlassNotification(notifyText, isVibrate))
        }
    }

    private fun setQrCode() {
        val bluetoothName = BluetoothAdapter.getDefaultAdapter().name
        val qrCodeBitmap =
            BarcodeEncoder().encodeBitmap(bluetoothName, BarcodeFormat.QR_CODE, 800, 800)
        ivQrCode.setImageBitmap(qrCodeBitmap)
    }

    override fun onDestroy() {
        super.onDestroy()
        connectedThread.cancel()
    }

    inner class ConnectedThread : Thread() {
        private val TAG = "ConnectedThread"
        private var isRunning = AtomicBoolean(true)
        private var bluetoothSocket: BluetoothSocket? = null

        override fun run() {
            val buffer = ByteArray(1024)

            bluetoothSocket = establishConnection()
            Log.v(TAG, "bluetoothSocket isConnected: ${bluetoothSocket?.isConnected}")

            while (bluetoothSocket?.isConnected == true && isRunning.get()) {
                try {
                    bluetoothSocket?.inputStream?.let { inputStream ->
                        val bytes = inputStream.read(buffer)
                        val incomingMessage = String(buffer, 0, bytes)
                        Log.v(TAG, "incomingMessage: $incomingMessage")
                    }
                } catch (e: IOException) {
                    Log.e(TAG, "run()", e)

                    // Attempt to reconnect
                    bluetoothSocket = establishConnection()
                    Log.v(TAG, "bluetoothSocket isConnected: ${bluetoothSocket?.isConnected}")
                }
            }

            cancel()
        }

        private fun establishConnection(): BluetoothSocket? {
            val serverSocket = BluetoothAdapter.getDefaultAdapter()
                .listenUsingRfcommWithServiceRecord("edu.gatech.cog.notify", cogNotifyUUID)
            val socket = try {
                serverSocket.accept()
            } catch (e: IOException) {
                Log.e(TAG, "socket.connect() failed", e)
                return null
            }
            serverSocket.close()

            return socket
        }

        fun write(glassNotification: GlassNotification) {
            Log.v(TAG, "Writing: $glassNotification")
            val byteArray = GlassNotification.convert(glassNotification).toByteArray()
            val chunkedByteArray = mutableListOf<ByteArray>()

            if (byteArray.size > CHUNK_SIZE) {
                for (i in 0 until byteArray.size - CHUNK_SIZE step CHUNK_SIZE) {
                    chunkedByteArray.add(byteArray.copyOfRange(i, i + CHUNK_SIZE))
                }
                chunkedByteArray.add(
                    byteArray.copyOfRange(
                        byteArray.size - (byteArray.size % CHUNK_SIZE),
                        byteArray.size
                    )
                )
            } else {
                chunkedByteArray.add(byteArray)
            }

            val meta = NOTIFICATION + chunkedByteArray.size
            write(meta.toByteArray())

            chunkedByteArray.forEach {
                write(it)
            }
        }

        private fun write(bytes: ByteArray) {
            try {
                bluetoothSocket?.outputStream?.let { outputStream ->
                    Log.v(TAG, "Writing: ${String(bytes, Charset.defaultCharset())}")

                    outputStream.write(bytes)
                    outputStream.flush()
                }
            } catch (e: IOException) {
                Log.e(TAG, "write()", e)
            }
        }

        /* Call this from the main activity to shutdown the connection */
        fun cancel() {
            try {
                isRunning.set(false)
                bluetoothSocket?.close()
            } catch (e: IOException) {
                Log.e(TAG, "cancel", e)
            }
        }
    }
}