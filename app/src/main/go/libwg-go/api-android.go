package main

// #cgo LDFLAGS: -llog
// #include <android/log.h>
import "C"

import (
    "fmt"
    "math"
    "net"
    "os"
    "os/signal"
    "runtime"
    "runtime/debug"
    "strings"
    "unsafe"

    "golang.org/x/sys/unix"
    "github.com/bepass-org/warp-plus/wireguard/conn"
    "github.com/bepass-org/warp-plus/wireguard/device"
    "github.com/bepass-org/warp-plus/wireguard/ipc"
    "github.com/bepass-org/warp-plus/wireguard/tun"
)

type AndroidLogger struct { level C.int; tag *C.char }
func cstring(s string) *C.char {
    b, err := unix.BytePtrFromString(s)
    if err != nil { z := [1]C.char{}; return &z[0] }
    return (*C.char)(unsafe.Pointer(b))
}
func (l AndroidLogger) Printf(format string, args ...interface{}) {
    C.__android_log_write(l.level, l.tag, cstring(fmt.Sprintf(format, args...)))
}
type TunnelHandle struct { device *device.Device; uapi net.Listener }
var tunnelHandles map[int32]TunnelHandle

func init() {
    tunnelHandles = make(map[int32]TunnelHandle)
    signals := make(chan os.Signal, 1)
    signal.Notify(signals, unix.SIGUSR2)
    go func() {
        buf := make([]byte, os.Getpagesize())
        for range signals {
            n := runtime.Stack(buf, true)
            if n == len(buf) { n-- }
            buf[n] = 0
            C.__android_log_write(C.ANDROID_LOG_ERROR, cstring("bekhruz/WireGuard/Stacktrace"), (*C.char)(unsafe.Pointer(&buf[0])))
        }
    }()
}

//export wgTurnOn
func wgTurnOn(interfaceName string, tunFd int32, settings string) int32 {
    logName := interfaceName
    if base, reserved, ok := strings.Cut(interfaceName, "@reserved="); ok {
        logName = base
        values := strings.TrimSpace(reserved)
        marker := "\npublic_key="
        at := strings.Index(settings, marker)
        if at < 0 { return -1 }
        lineEnd := strings.Index(settings[at+1:], "\n")
        if lineEnd < 0 { return -1 }
        insertAt := at + 1 + lineEnd + 1
        settings = settings[:insertAt] + "reserved=" + values + "\n" + settings[insertAt:]
    }
    tag := cstring("WireGuard/GoBackend/" + logName)
    logger := &device.Logger{
        Verbosef: AndroidLogger{level: C.ANDROID_LOG_DEBUG, tag: tag}.Printf,
        Errorf: AndroidLogger{level: C.ANDROID_LOG_ERROR, tag: tag}.Printf,
    }
    tunDevice, name, err := tun.CreateUnmonitoredTUNFromFD(int(tunFd))
    if err != nil {
        unix.Close(int(tunFd)); logger.Errorf("CreateUnmonitoredTUNFromFD: %v", err); return -1
    }
    logger.Verbosef("Attaching to interface %v", name)
    dev := device.NewDevice(tunDevice, conn.NewStdNetBind(), logger)
    if err = dev.IpcSet(settings); err != nil {
        unix.Close(int(tunFd)); dev.Close(); logger.Errorf("IpcSet: %v", err); return -1
    }
    dev.DisableSomeRoamingForBrokenMobileSemantics()
    var uapi net.Listener
    uapiFile, err := ipc.UAPIOpen(name)
    if err == nil {
        uapi, err = ipc.UAPIListen(name, uapiFile)
        if err == nil {
            go func() {
                for {
                    c, acceptErr := uapi.Accept()
                    if acceptErr != nil { return }
                    go dev.IpcHandle(c)
                }
            }()
        } else { uapiFile.Close() }
    }
    if err = dev.Up(); err != nil {
        logger.Errorf("Unable to bring up device: %v", err)
        if uapi != nil { uapi.Close() }
        dev.Close()
        return -1
    }
    var handle int32
    for handle = 0; handle < math.MaxInt32; handle++ {
        if _, exists := tunnelHandles[handle]; !exists { break }
    }
    if handle == math.MaxInt32 {
        if uapi != nil { uapi.Close() }; dev.Close(); return -1
    }
    tunnelHandles[handle] = TunnelHandle{device: dev, uapi: uapi}
    return handle
}

//export wgTurnOff
func wgTurnOff(id int32) {
    h, ok := tunnelHandles[id]; if !ok { return }
    delete(tunnelHandles, id)
    if h.uapi != nil { h.uapi.Close() }
    h.device.Close()
}
//export wgGetSocketV4
func wgGetSocketV4(id int32) int32 {
    h, ok := tunnelHandles[id]; if !ok { return -1 }
    b, _ := h.device.Bind().(conn.PeekLookAtSocketFd); if b == nil { return -1 }
    fd, err := b.PeekLookAtSocketFd4(); if err != nil { return -1 }; return int32(fd)
}
//export wgGetSocketV6
func wgGetSocketV6(id int32) int32 {
    h, ok := tunnelHandles[id]; if !ok { return -1 }
    b, _ := h.device.Bind().(conn.PeekLookAtSocketFd); if b == nil { return -1 }
    fd, err := b.PeekLookAtSocketFd6(); if err != nil { return -1 }; return int32(fd)
}
//export wgGetConfig
func wgGetConfig(id int32) *C.char {
    h, ok := tunnelHandles[id]; if !ok { return nil }
    s, err := h.device.IpcGet(); if err != nil { return nil }; return C.CString(s)
}
//export wgVersion
func wgVersion() *C.char {
    info, ok := debug.ReadBuildInfo(); if !ok { return C.CString("bekhruz-warp-reserved") }
    for _, dep := range info.Deps {
        if dep.Path == "github.com/bepass-org/warp-plus" { return C.CString("bekhruz-warp-reserved+" + dep.Version) }
    }
    return C.CString("bekhruz-warp-reserved")
}
func main() {}
