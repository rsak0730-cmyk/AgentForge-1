package com.agentforge.app.automation

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import com.agentforge.app.shizuku.IPrivilegedActions
import com.agentforge.app.shizuku.PrivilegedUserService
import rikka.shizuku.Shizuku

class ShizukuBridge(private val context: Context) {
    companion object { const val PERMISSION_CODE = 42 }
    private var remote: IPrivilegedActions? = null
    private val args = Shizuku.UserServiceArgs(ComponentName(context, PrivilegedUserService::class.java)).daemon(false).version(1).tag("agentforge-privileged")
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) { remote = IPrivilegedActions.Stub.asInterface(service) }
        override fun onServiceDisconnected(name: ComponentName?) { remote = null }
    }
    fun isAvailable(): Boolean = try { !Shizuku.isPreV11() && Shizuku.getVersion() >= 11 } catch (_: Throwable) { false }
    fun hasPermission(): Boolean = try { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED } catch (_: Throwable) { false }
    fun requestPermission() { if (isAvailable() && !hasPermission()) Shizuku.requestPermission(PERMISSION_CODE) }
    fun connect(): Boolean = try { if (!hasPermission()) false else { Shizuku.bindUserService(args, connection); true } } catch (_: Throwable) { false }
    fun run(action: String): String = try { remote?.runAllowed(action, "") ?: "Shizuku bridge not connected" } catch (e: Exception) { "ERROR: ${e.message}" }
    fun close() { try { Shizuku.unbindUserService(args, connection, true) } catch (_: Throwable) {}; remote = null }
}
