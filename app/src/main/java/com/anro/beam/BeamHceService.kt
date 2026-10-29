package com.anro.beam

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.util.Log

import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * ANRO-BEAM NFC HCE (Host Card Emulation) service.
 *
 * The receiver device runs this service so it appears as an NFC "card".
 * When the sender taps its NFC reader against the receiver, Android routes
 * the ISO-DEP APDU exchange here. The sender sends a SELECT AID command;
 * this service replies "ANROBEAM_READY" + SW 9000, confirming a valid
 * ANRO-BEAM receiver is present. The sender then initiates Wi-Fi Direct.
 *
 * AID: F0 41 4E 52 4F 42 45 41  ("F0ANROBEA" — custom proprietary AID,
 *      must start with F0 per ISO 7816-4 for proprietary use).
 */
class BeamHceService : HostApduService() {

    companion object {
        /** Our proprietary AID — F0 + "ANROBEA" in ASCII hex */
        val AID = byteArrayOf(
            0xF0.toByte(),
            0x41, 0x4E, 0x52, 0x4F, 0x42, 0x45, 0x41
        )

        // APDU status words
        private val SW_OK      = byteArrayOf(0x90.toByte(), 0x00)
        private val SW_UNKNOWN = byteArrayOf(0x00, 0x00)

        /** Response payload sent to the sender on a valid SELECT AID */
        val RESPONSE_PAYLOAD = "ANROBEAM_READY".toByteArray(Charsets.UTF_8)
    }

    override fun processCommandApdu(apdu: ByteArray, extras: Bundle?): ByteArray {
        Log.d("AnroBeam_HCE", "APDU: ${apdu.toHex()}")
        return if (isSelectAid(apdu)) {
            Log.d("AnroBeam_HCE", "SELECT AID matched — sending READY response")
            try {
                val serviceIntent = Intent(this, BeamTransferService::class.java).apply {
                    action = BeamTransferService.ACTION_START_RECEIVE
                }
                ContextCompat.startForegroundService(this, serviceIntent)
            } catch (e: Exception) {
                Log.e("AnroBeam_HCE", "Failed to start BeamTransferService", e)
            }
            RESPONSE_PAYLOAD + SW_OK
        } else {
            Log.w("AnroBeam_HCE", "Unknown APDU — ignoring")
            SW_UNKNOWN
        }
    }

    /**
     * Returns true if [apdu] is a SELECT AID command targeting [AID].
     * Format: 00 A4 04 00 <Lc> <AID bytes> [00]
     */
    private fun isSelectAid(apdu: ByteArray): Boolean {
        if (apdu.size < 5 + AID.size) return false
        if (apdu[0] != 0x00.toByte()) return false
        if (apdu[1] != 0xA4.toByte()) return false
        if (apdu[2] != 0x04.toByte()) return false
        val lc = apdu[4].toInt() and 0xFF
        if (lc != AID.size) return false
        for (i in AID.indices) {
            if (apdu[5 + i] != AID[i]) return false
        }
        return true
    }

    override fun onDeactivated(reason: Int) {
        Log.d("AnroBeam_HCE", "HCE deactivated (reason=$reason)")
    }

    private fun ByteArray.toHex() = joinToString("") { "%02X".format(it) }
}
