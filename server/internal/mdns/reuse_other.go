//go:build !unix

package mdns

import "syscall"

func reuse(_, _ string, _ syscall.RawConn) error { return nil }
