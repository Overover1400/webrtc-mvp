# Build hev-socks5-tunnel for use in our VPN service.
# The library registers JNI methods on hev.htproxy.TProxyService (the default
# PKGNAME/CLSNAME), which is a thin Kotlin shim in our app.

include $(call my-dir)/hev-socks5-tunnel/Android.mk
