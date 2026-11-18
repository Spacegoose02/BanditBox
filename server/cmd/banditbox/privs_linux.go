package main

import (
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
	"strconv"
	"syscall"
)

// dropPrivileges runs when the container starts as root (the default) with
// PUID/PGID set, as Unraid expects (99:100 is nobody:users). It makes the data
// folder belong to that user, then switches to it, so files in appdata are
// owned by nobody:users like other Unraid containers.
func dropPrivileges(dataDir string) error {
	if os.Getuid() != 0 {
		return nil
	}
	puid, pgid := os.Getenv("PUID"), os.Getenv("PGID")
	if puid == "" && pgid == "" {
		return nil
	}
	uid, err := strconv.Atoi(puid)
	if err != nil {
		return fmt.Errorf("PUID must be a number, got %q", puid)
	}
	gid, err := strconv.Atoi(pgid)
	if err != nil {
		return fmt.Errorf("PGID must be a number, got %q", pgid)
	}
	if uid == 0 {
		return nil
	}
	if err := os.MkdirAll(dataDir, 0o750); err != nil {
		return err
	}
	err = filepath.WalkDir(dataDir, func(p string, _ fs.DirEntry, err error) error {
		if err != nil {
			return err
		}
		return os.Lchown(p, uid, gid)
	})
	if err != nil {
		return fmt.Errorf("give %s to %d:%d: %w", dataDir, uid, gid, err)
	}
	if err := syscall.Setgroups([]int{gid}); err != nil {
		return err
	}
	if err := syscall.Setgid(gid); err != nil {
		return err
	}
	return syscall.Setuid(uid)
}
