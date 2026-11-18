//go:build unix

package mdns

import (
	"syscall"

	"golang.org/x/sys/unix"
)

// reuse lets us share udp/5353 with another responder on the same host, such
// as avahi on Unraid when the container uses host networking.
func reuse(_, _ string, c syscall.RawConn) error {
	var serr error
	err := c.Control(func(fd uintptr) {
		if serr = unix.SetsockoptInt(int(fd), unix.SOL_SOCKET, unix.SO_REUSEADDR, 1); serr != nil {
			return
		}
		serr = unix.SetsockoptInt(int(fd), unix.SOL_SOCKET, unix.SO_REUSEPORT, 1)
	})
	if err != nil {
		return err
	}
	return serr
}
