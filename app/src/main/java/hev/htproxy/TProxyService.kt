package hev.htproxy

/**
 * JNI shim for libhev-socks5-tunnel.so.
 * The library's JNI_OnLoad registers these three methods against this exact
 * class/package path (hev/htproxy/TProxyService), so the package name must
 * not be changed without also rebuilding the native library with a new PKGNAME.
 *
 * TProxyStartService runs the tunnel on a background thread and blocks until
 * TProxyStopService is called; callers must invoke it from their own thread.
 */
object TProxyService {
    init {
        System.loadLibrary("hev-socks5-tunnel")
    }

    external fun TProxyStartService(configPath: String, fd: Int)
    external fun TProxyStopService()
    external fun TProxyGetStats(): LongArray
}
