package me.aljan.telpo_flutter_sdk

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import com.telpo.tps550.api.printer.UsbThermalPrinter

class TelpoThermalPrinter(plugin: TelpoFlutterSdkPlugin) {
    private val TAG = "TelpoThermalPrinter"

    private val context: Context = plugin.context
    private val mUsbThermalPrinter: UsbThermalPrinter = UsbThermalPrinter(context)
    private val utils = Utils()

    private var result: MethodChannelResultWrapper? = null
    private var printDataArray: ArrayList<Map<String, Any>> = ArrayList()
    private var noPaper = false

    // Handler Message Codes
    private val NOPAPER = 3
    private val LOWBATTERY = 4
    private val PRINT = 9
    private val CANCELPROMPT = 10
    private val PRINTERR = 11
    private val OVERHEAT = 12
    private val DEVICETRANSMITDATA = 13

    // Printer Status Codes
    private val STATUS_OK = 0
    private val STATUS_NO_PAPER = 16
    private val STATUS_OVER_HEAT = 2
    private val STATUS_OVER_FLOW = 3
    private val STATUS_UNKNOWN = 4

    // Exception Class Names
    private val NOPAPEREXCEPTION = "com.telpo.tps550.api.printer.NoPaperException"
    private val OVERHEATEXCEPTION = "com.telpo.tps550.api.printer.OverHeatException"
    private val DEVICETRANSMITDATAEXCEPTION = "com.telpo.tps550.api.printer.DeviceTransmitDataException"

    @SuppressLint("HandlerLeak")
    inner class PrintHandler : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                NOPAPER -> result?.error("3", "No paper, please put paper in and retry", null)
                LOWBATTERY -> result?.error("4", "Low battery", null)
                PRINT -> Print().start()
                CANCELPROMPT -> Log.d(TAG, "Cancel prompt")
                OVERHEAT -> result?.error("12", "Overheat error", null)
                DEVICETRANSMITDATA -> result?.error("13", "Device Transmit Data Exception", null)
                PRINTERR -> result?.error("11", "Unknown printing error", null)
            }
        }
    }

    fun checkStatus(result: MethodChannelResultWrapper, lowBattery: Boolean) {
        this.result = result
        try {
            when (mUsbThermalPrinter.checkStatus()) {
                STATUS_OK -> {
                    if (lowBattery) {
                        PrintHandler().sendMessage(PrintHandler().obtainMessage(LOWBATTERY))
                    } else {
                        result.success("STATUS_OK")
                    }
                }
                STATUS_NO_PAPER -> result.success("STATUS_NO_PAPER")
                STATUS_UNKNOWN -> result.success("STATUS_UNKNOWN")
                STATUS_OVER_FLOW -> result.success("STATUS_OVER_FLOW")
                STATUS_OVER_HEAT -> result.success("STATUS_OVER_HEAT")
                else -> result.success("STATUS_UNHANDLED")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Check status error", e)
            result.error("CheckStatusException", e.message, null)
        }
    }

    fun connect(): Boolean {
        return try {
            mUsbThermalPrinter.start(0)
            mUsbThermalPrinter.reset()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Connect error", e)
            result?.error("Printer Start Error", e.message, e.stackTrace)
            false
        }
    }

    fun disconnect(): Boolean {
        return try {
            mUsbThermalPrinter.reset()
            mUsbThermalPrinter.stop()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Disconnect error", e)
            result?.error("Printer Disconnect Error", e.message, e.stackTrace)
            false
        }
    }

    fun print(
        result: MethodChannelResultWrapper,
        printDataArray: ArrayList<Map<String, Any>>,
        lowBattery: Boolean
    ) {
        this.result = result
        this.printDataArray = printDataArray

        val handler = PrintHandler()

        when {
            lowBattery -> handler.sendMessage(handler.obtainMessage(LOWBATTERY))
            noPaper -> handler.sendMessage(handler.obtainMessage(NOPAPER))
            else -> handler.sendMessage(handler.obtainMessage(PRINT))
        }
    }

    private inner class Print : Thread() {
        override fun run() {
            try {
                mUsbThermalPrinter.reset()
                mUsbThermalPrinter.setAlgin(UsbThermalPrinter.ALGIN_LEFT)
                mUsbThermalPrinter.setLeftIndent(0)
                mUsbThermalPrinter.setLineSpace(0)
                mUsbThermalPrinter.setGray(5)

                for (data in printDataArray) {
                    when (utils.getPrintType(data["type"].toString())) {
                        PrintType.Text -> printText(data)
                        PrintType.Byte -> printByte(data)
                        PrintType.QR -> {} // Implement as needed
                        PrintType.PDF -> {} // Implement as needed
                        PrintType.WalkPaper -> {
                            val step = data["data"].toString().toIntOrNull() ?: 2
                            mUsbThermalPrinter.walkPaper(step)
                        }
                    }
                }
                result?.success(true)
            } catch (e: Exception) {
                Log.e(TAG, "Print error", e)
                when (e.javaClass.name) {
                    NOPAPEREXCEPTION -> {
                        noPaper = true
                        PrintHandler().sendMessage(PrintHandler().obtainMessage(NOPAPER))
                    }
                    DEVICETRANSMITDATAEXCEPTION -> {
                        PrintHandler().sendMessage(PrintHandler().obtainMessage(DEVICETRANSMITDATA))
                    }
                    OVERHEATEXCEPTION -> {
                        PrintHandler().sendMessage(PrintHandler().obtainMessage(OVERHEAT))
                    }
                    else -> {
                        PrintHandler().sendMessage(PrintHandler().obtainMessage(PRINTERR))
                    }
                }
            } finally {
                PrintHandler().sendMessage(PrintHandler().obtainMessage(CANCELPROMPT))
                if (!noPaper) {
                    mUsbThermalPrinter.stop()
                }
            }
        }
    }

    private fun printText(data: Map<String, Any>) {
        val text = data["data"].toString()
        val alignment = utils.getAlignment(data["alignment"].toString())
        val fontSize = utils.getFontSize(data["fontSize"].toString())

        mUsbThermalPrinter.setTextSize(fontSize)
        mUsbThermalPrinter.setAlgin(alignment)
        mUsbThermalPrinter.addString(text)
        mUsbThermalPrinter.printString()
    }

    private fun printByte(data: Map<String, Any>) {
        val value = data["data"]
        if (value is ArrayList<*>) {
            for (item in value) {
                if (item is ByteArray) {
                    val bmp = utils.createByteImage(item)
                    mUsbThermalPrinter.printLogo(bmp, false)
                }
            }
        }
        mUsbThermalPrinter.printString()
    }
}