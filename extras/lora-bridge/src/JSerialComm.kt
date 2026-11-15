// UNTESTED ON HARDWARE — not part of the demo build
package mesh.extras.lora

import com.fazecast.jSerialComm.SerialPort
import java.io.InputStream
import java.io.OutputStream

/**
 * Opens a Meshtastic radio's USB serial port (115200 8N1) with jSerialComm (com.fazecast:jSerialComm:2.x, LGPL-3.0 /
 * Apache-2.0). Kept apart from MeshtasticTransport so the framing and policy compile and test without it.
 *
 * Usage: MeshtasticTransport({ openSerial("/dev/ttyUSB0") })   // Windows: "COM5"
 */
fun openSerial(portName: String): Pair<InputStream, OutputStream> {
    val p = SerialPort.getCommPort(portName)
    p.setComPortParameters(115_200, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY)
    p.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 0, 0)   // read blocks until at least one byte
    check(p.openPort()) { "cannot open $portName" }
    return p.inputStream to p.outputStream
}
