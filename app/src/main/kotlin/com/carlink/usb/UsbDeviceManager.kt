package com.carlink.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConfiguration
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import com.carlink.util.LogCallback
import java.util.Locale
import java.util.Timer
import java.util.TimerTask

private const val ACTION_USB_PERMISSION = "com.carlink.USB_PERMISSION"

private val pendingIntentFlag =
    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

/**
 * Creates a PendingIntent for USB permission requests.
 * Uses an explicit Intent (with package set) to satisfy Android 12+ security requirements.
 * FLAG_MUTABLE is required because UsbManager adds extras to the intent.
 */
private fun pendingPermissionIntent(context: Context) =
    PendingIntent.getBroadcast(
        context,
        0,
        Intent(ACTION_USB_PERMISSION).apply { setPackage(context.packageName) },
        pendingIntentFlag,
    )

/**
 * Known Carlinkit VID/PID combinations.
 * These must match as pairs for device identification.
 */
object CarlinkitDeviceIds {
    // Primary VID/PID combinations
    const val VID_PRIMARY = 0x1314
    const val PID_PRIMARY_1 = 0x1520
    const val PID_PRIMARY_2 = 0x1521

    // Alternate VID/PID combination
    const val VID_ALTERNATE = 0x08e4
    const val PID_ALTERNATE = 0x01c0

    /**
     * Checks if a device matches known Carlinkit VID/PID combinations.
     */
    fun isCarlinkit(
        vendorId: Int,
        productId: Int,
    ): Boolean =
        (vendorId == VID_PRIMARY && (productId == PID_PRIMARY_1 || productId == PID_PRIMARY_2)) ||
            (vendorId == VID_ALTERNATE && productId == PID_ALTERNATE)
}

/**
 * Centralized USB Device Management for Carlink.
 *
 * Manages all USB device operations for CPC200-CCPA wireless CarPlay/Android Auto adapters,
 * including device discovery, permission handling, connection lifecycle, and configuration
 * management. This class encapsulates Android USB Host API complexity and provides a clean
 * interface for the CarlinkPlugin.
 *
 * @param context Android application context for permission requests
 * @param usbManager Android UsbManager instance
 * @param logCallback Callback for logging messages
 */
class UsbDeviceManager(
    private val context: Context,
    private val usbManager: UsbManager,
    private val logCallback: LogCallback,
) {
    // Current device connection state
    private var currentDevice: UsbDevice? = null
    private var currentConnection: UsbDeviceConnection? = null

    // Permission management
    private var permissionReceiver: BroadcastReceiver? = null
    private var permissionTimeout: Timer? = null

    /**
     * Gets the current connected USB device.
     */
    fun getCurrentDevice(): UsbDevice? = currentDevice

    /**
     * Gets the current USB device connection.
     */
    fun getCurrentConnection(): UsbDeviceConnection? = currentConnection

    /**
     * Scans for all USB devices and returns them as a list of maps.
     * Logs discovered Carlinkit devices.
     *
     * @return List of device information maps containing identifier, vendorId, productId, configurationCount
     */
    fun getDeviceList(): List<Map<String, Any>> {
        log("[USB] Scanning for Carlinkit devices...")

        val usbDeviceList =
            usbManager.deviceList.entries.map {
                val device = it.value
                val isCarlinkit = CarlinkitDeviceIds.isCarlinkit(device.vendorId, device.productId)

                if (isCarlinkit) {
                    log(
                        "[USB] Found Carlinkit device: ${it.key}, VID:${String.format(
                            Locale.US,
                            "%04X",
                            device.vendorId,
                        )}, PID:${String.format(Locale.US, "%04X", device.productId)}",
                    )
                }

                mapOf(
                    "identifier" to it.key,
                    "vendorId" to device.vendorId,
                    "productId" to device.productId,
                    "configurationCount" to device.configurationCount,
                )
            }

        val carlinkDevices =
            usbDeviceList.filter { device ->
                val vid = device["vendorId"] as Int
                val pid = device["productId"] as Int
                CarlinkitDeviceIds.isCarlinkit(vid, pid)
            }

        if (carlinkDevices.isEmpty()) {
            log("[USB] No Carlinkit devices found (${usbDeviceList.size} total USB devices)")
        } else {
            log("[USB] Found ${carlinkDevices.size} Carlinkit device(s)")
        }

        return usbDeviceList
    }

    /**
     * Gets device description information and optionally requests permission.
     *
     * @param identifier Device identifier from getDeviceList
     * @param requestPermission Whether to request permission if not already granted
     * @param callback Callback with device description map or error
     */
    fun getDeviceDescription(
        identifier: String,
        requestPermission: Boolean,
        callback: (Result<Map<String, String?>>) -> Unit,
    ) {
        val device =
            usbManager.deviceList[identifier]
                ?: return callback(Result.failure(Exception("Device not found: $identifier")))

        val hasPermission = usbManager.hasPermission(device)

        if (requestPermission && !hasPermission) {
            // Clean up any existing permission receiver first
            cleanupPermissionReceiver()

            permissionReceiver =
                object : BroadcastReceiver() {
                    override fun onReceive(
                        context: Context,
                        intent: Intent,
                    ) {
                        cleanupPermissionReceiver()
                        val granted =
                            intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)

                        callback(
                            Result.success(
                                mapOf(
                                    "manufacturer" to device.manufacturerName,
                                    "product" to device.productName,
                                    "serialNumber" to if (granted) device.serialNumber else null,
                                ),
                            ),
                        )
                    }
                }

            try {
                // Use ContextCompat to handle API differences for receiver registration
                // RECEIVER_NOT_EXPORTED ensures only this app can send permission broadcasts
                ContextCompat.registerReceiver(
                    context,
                    permissionReceiver,
                    IntentFilter(ACTION_USB_PERMISSION),
                    ContextCompat.RECEIVER_NOT_EXPORTED,
                )

                // Set up timeout to prevent receiver leak if permission is never granted/denied
                permissionTimeout =
                    Timer().apply {
                        schedule(
                            object : TimerTask() {
                                override fun run() {
                                    cleanupPermissionReceiver()
                                    callback(
                                        Result.failure(
                                            Exception("USB permission request timed out after 30 seconds"),
                                        ),
                                    )
                                }
                            },
                            30_000,
                        ) // 30 second timeout
                    }

                usbManager.requestPermission(device, pendingPermissionIntent(context))
            } catch (e: Exception) {
                cleanupPermissionReceiver()
                callback(Result.failure(Exception("Failed to register permission receiver: ${e.message}")))
            }
        } else {
            callback(
                Result.success(
                    mapOf(
                        "manufacturer" to device.manufacturerName,
                        "product" to device.productName,
                        "serialNumber" to if (hasPermission) device.serialNumber else null,
                    ),
                ),
            )
        }
    }

    /**
     * Checks if permission has been granted for a device.
     *
     * @param identifier Device identifier
     * @return true if permission granted, false otherwise
     */
    fun hasPermission(identifier: String): Boolean {
        val device = usbManager.deviceList[identifier] ?: return false
        return usbManager.hasPermission(device)
    }

    /**
     * Requests permission for a USB device.
     *
     * @param identifier Device identifier
     * @param callback Callback with permission result
     */
    fun requestPermission(
        identifier: String,
        callback: (Boolean) -> Unit,
    ) {
        val device = usbManager.deviceList[identifier]

        if (device == null) {
            callback(false)
            return
        }

        if (usbManager.hasPermission(device)) {
            callback(true)
        } else {
            val receiver =
                object : BroadcastReceiver() {
                    override fun onReceive(
                        context: Context,
                        intent: Intent,
                    ) {
                        context.unregisterReceiver(this)
                        val granted =
                            intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        callback(granted)
                    }
                }

            // Use ContextCompat to handle API differences for receiver registration
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter(ACTION_USB_PERMISSION),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )

            usbManager.requestPermission(device, pendingPermissionIntent(context))
        }
    }

    /**
     * Opens a USB device connection by identifier.
     * Handles USB re-enumeration by searching for fresh device by VID/PID.
     *
     * @param identifier Device identifier (may be stale after USB reset)
     * @return true if device opened successfully, false otherwise
     */
    fun openDevice(identifier: String): Boolean {
        log("[USB] Opening device with identifier: $identifier")

        // Refresh device from current device list to handle USB re-enumeration
        // The device path may change after USB reset (e.g., /dev/bus/usb/001/048 -> 001/052)
        // so we find the device by VID/PID instead of using the stale identifier
        var freshDevice: UsbDevice? = null
        for (device in usbManager.deviceList.values) {
            if (CarlinkitDeviceIds.isCarlinkit(device.vendorId, device.productId)) {
                log(
                    "[USB] Found fresh device: ${device.deviceName}, " +
                        "VID:${String.format(Locale.US, "%04X", device.vendorId)}, " +
                        "PID:${String.format(Locale.US, "%04X", device.productId)}",
                )
                freshDevice = device
                break
            }
        }

        if (freshDevice == null) {
            log("[USB] Device not found in current device list (may have been re-enumerated)")
            return false
        }

        currentDevice = freshDevice
        currentConnection = usbManager.openDevice(currentDevice)
        val success = currentConnection != null
        log("[USB] Device open result: $success")

        if (success) {
            log(
                "[USB] Device info: ${currentDevice?.productName} by ${currentDevice?.manufacturerName}",
            )
            log("[USB] Device path: ${currentDevice?.deviceName}")
        } else {
            log("[USB] Failed to open device - connection is null")
        }

        return success
    }

    /**
     * Closes the current USB device connection.
     */
    fun closeDevice() {
        log("[USB] Closing device connection")
        currentConnection?.close()
        currentConnection = null
        currentDevice = null
        log("[USB] Device connection closed")
    }

    /**
     * Resets the current USB device using reflection to access hidden resetDevice method.
     *
     * @return true if reset successful, false otherwise
     */
    fun resetDevice(): Boolean {
        log("[USB] Attempting device reset")
        return try {
            val resetMethod = currentConnection?.javaClass?.getDeclaredMethod("resetDevice")
            resetMethod?.invoke(currentConnection)
            log("[USB] Device reset successful")
            true
        } catch (e: Exception) {
            log("[USB] Device reset failed: $e")
            false
        }
    }

    /**
     * Gets a USB configuration by index.
     *
     * @param index Configuration index
     * @return Configuration map including index, id, and interfaces
     */
    fun getConfiguration(index: Int): Map<String, Any>? {
        val device = currentDevice ?: return null
        val configuration = device.getConfiguration(index)
        return configuration.toMap() + ("index" to index)
    }

    /**
     * Sets the active USB configuration.
     *
     * @param index Configuration index
     * @return true if successful, false otherwise
     */
    fun setConfiguration(index: Int): Boolean {
        val device = currentDevice ?: return false
        val connection = currentConnection ?: return false
        val configuration = device.getConfiguration(index)

        log("[USB] Setting configuration $index (ID: ${configuration.id})")
        val success = connection.setConfiguration(configuration)
        log("[USB] Configuration set result: $success")

        return success
    }

    /**
     * Claims a USB interface for exclusive access.
     *
     * @param id Interface ID
     * @param alternateSetting Alternate setting
     * @return Result with success or error message
     */
    fun claimInterface(
        id: Int,
        alternateSetting: Int,
    ): Result<Boolean> {
        val device = currentDevice ?: return Result.failure(Exception("Device not opened"))
        val connection = currentConnection ?: return Result.failure(Exception("Connection not established"))

        // Validate parameters
        if (id < 0) {
            return Result.failure(IllegalArgumentException("interface id must be non-negative"))
        }
        if (alternateSetting < 0) {
            return Result.failure(IllegalArgumentException("alternateSetting must be non-negative"))
        }

        log("[USB] Claiming interface ID:$id, Alt:$alternateSetting")

        val usbInterface =
            device.findInterface(id, alternateSetting)
                ?: return Result.failure(Exception("interface not found"))

        return try {
            // Validate interface before claiming (Android USB host best practice)
            if (usbInterface.endpointCount == 0) {
                return Result.failure(Exception("interface has no endpoints"))
            }

            // Use force claim as per Android documentation for exclusive access
            val success = connection.claimInterface(usbInterface, true)
            log("[USB] Interface claim result: $success (Endpoints: ${usbInterface.endpointCount})")

            if (!success) {
                Result.failure(Exception("failed to claim interface exclusively"))
            } else {
                Result.success(success)
            }
        } catch (e: Exception) {
            log("[USB] Exception claiming interface: ${e.message}")
            Result.failure(Exception("Exception claiming interface: ${e.message}"))
        }
    }

    /**
     * Releases a USB interface.
     *
     * @param id Interface ID
     * @param alternateSetting Alternate setting
     * @return true if successful, false otherwise
     */
    fun releaseInterface(
        id: Int,
        alternateSetting: Int,
    ): Boolean {
        val device = currentDevice ?: return false
        val connection = currentConnection ?: return false

        log("[USB] Releasing interface ID:$id, Alt:$alternateSetting")
        val usbInterface = device.findInterface(id, alternateSetting)
        val success = connection.releaseInterface(usbInterface)
        log("[USB] Interface release result: $success")

        return success
    }

    /**
     * Finds a USB endpoint by number and direction.
     *
     * @param endpointNumber Endpoint number
     * @param direction Endpoint direction (UsbConstants.USB_DIR_IN or USB_DIR_OUT)
     * @return UsbEndpoint or null if not found
     */
    fun findEndpoint(
        endpointNumber: Int,
        direction: Int,
    ): UsbEndpoint? = currentDevice?.findEndpoint(endpointNumber, direction)

    /**
     * Cleans up permission receiver and timeout timer.
     * This method is synchronized to prevent concurrent cleanup attempts.
     */
    @Synchronized
    private fun cleanupPermissionReceiver() {
        permissionTimeout?.cancel()
        permissionTimeout = null

        permissionReceiver?.let { receiver ->
            try {
                context.unregisterReceiver(receiver)
            } catch (e: IllegalArgumentException) {
                // Receiver was already unregistered - this is expected in cleanup scenarios
                log("Permission receiver already unregistered: ${e.message}")
            }
            permissionReceiver = null
        }
    }

    /**
     * Cleans up all resources including current connection and permission receivers.
     * Should be called when the manager is no longer needed.
     */
    fun cleanup() {
        cleanupPermissionReceiver()
        closeDevice()
    }

    private fun log(message: String) {
        logCallback.log(message)
    }
}

// Extension functions for USB classes

fun UsbDevice.findInterface(
    id: Int,
    alternateSetting: Int,
): UsbInterface? {
    for (i in 0 until interfaceCount) {
        val usbInterface = getInterface(i)
        if (usbInterface.id == id && usbInterface.alternateSetting == alternateSetting) {
            return usbInterface
        }
    }
    return null
}

fun UsbDevice.findEndpoint(
    endpointNumber: Int,
    direction: Int,
): UsbEndpoint? {
    for (i in 0 until interfaceCount) {
        val usbInterface = getInterface(i)
        for (j in 0 until usbInterface.endpointCount) {
            val endpoint = usbInterface.getEndpoint(j)
            if (endpoint.endpointNumber == endpointNumber && endpoint.direction == direction) {
                return endpoint
            }
        }
    }
    return null
}

fun UsbConfiguration.toMap() =
    mapOf(
        "id" to id,
        "interfaces" to List(interfaceCount) { getInterface(it).toMap() },
    )

fun UsbInterface.toMap() =
    mapOf(
        "id" to id,
        "alternateSetting" to alternateSetting,
        "endpoints" to List(endpointCount) { getEndpoint(it).toMap() },
    )

fun UsbEndpoint.toMap() =
    mapOf(
        "endpointNumber" to endpointNumber,
        "direction" to direction,
        "maxPacketSize" to maxPacketSize,
    )
