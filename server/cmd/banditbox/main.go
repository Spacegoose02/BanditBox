// Command banditbox runs the BanditBox master sync server.
package main

import (
	"context"
	"errors"
	"log/slog"
	"net"
	"net/http"
	"net/netip"
	"os"
	"os/signal"
	"strconv"
	"strings"
	"syscall"
	"time"

	"github.com/spacegoose02/banditbox/server/internal/api"
	"github.com/spacegoose02/banditbox/server/internal/auth"
	"github.com/spacegoose02/banditbox/server/internal/hub"
	"github.com/spacegoose02/banditbox/server/internal/mdns"
	"github.com/spacegoose02/banditbox/server/internal/pairing"
	"github.com/spacegoose02/banditbox/server/internal/store"
)

// version is set at build time with -ldflags "-X main.version=...".
var version = "dev"

func main() {
	addr := envOr("BANDITBOX_ADDR", ":8686")
	dataDir := envOr("BANDITBOX_DATA", "/config")
	log := slog.New(slog.NewTextHandler(os.Stdout, nil))

	if len(os.Args) > 1 && os.Args[1] == "healthcheck" {
		os.Exit(healthcheck(addr))
	}
	if err := dropPrivileges(dataDir); err != nil {
		log.Error("switch to PUID/PGID", "err", err)
		os.Exit(1)
	}

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	st, err := store.Open(ctx, dataDir)
	if err != nil {
		log.Error("open database", "err", err)
		os.Exit(1)
	}
	defer st.Close()
	if err := st.EnsureDefaultProfile(ctx); err != nil {
		log.Error("create default profile", "err", err)
		os.Exit(1)
	}

	au := auth.New(st)
	if code, err := au.SetupCode(ctx); err != nil {
		log.Error("check admin", "err", err)
		os.Exit(1)
	} else if code != "" {
		log.Warn("first run: open the web UI and create the admin account with this setup code", "setupCode", code)
	}
	go cleanSessions(ctx, st, log)
	if envOr("BANDITBOX_MDNS", "on") != "off" {
		go advertise(ctx, addr, log)
	}

	srv := &http.Server{
		Addr:              addr,
		Handler:           api.NewHandler(api.Deps{Version: version, Store: st, Auth: au, Pairing: pairing.New(st), Hub: hub.New(), Log: log}),
		ReadHeaderTimeout: 10 * time.Second,
	}

	go func() {
		log.Info("banditbox listening", "addr", addr, "data", dataDir, "version", version)
		if err := srv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			log.Error("server stopped", "err", err)
			os.Exit(1)
		}
	}()

	<-ctx.Done()
	shutdownCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	if err := srv.Shutdown(shutdownCtx); err != nil {
		log.Error("shutdown", "err", err)
	}
}

// healthcheck is the Docker HEALTHCHECK: the image has no shell or curl.
func healthcheck(addr string) int {
	_, port, err := net.SplitHostPort(addr)
	if err != nil {
		return 1
	}
	c := http.Client{Timeout: 3 * time.Second}
	res, err := c.Get("http://127.0.0.1:" + port + "/api/v1/health")
	if err != nil {
		return 1
	}
	res.Body.Close()
	if res.StatusCode != http.StatusOK {
		return 1
	}
	return 0
}

// advertise announces the server on the LAN so TVs can find it.
func advertise(ctx context.Context, addr string, log *slog.Logger) {
	svc := mdns.Service{
		Instance: envOr("BANDITBOX_NAME", "BanditBox"),
		Host:     mdns.HostLabel(),
		TXT:      []string{"path=/api/v1", "version=" + version},
	}
	port := os.Getenv("BANDITBOX_ADVERTISE_PORT")
	if port == "" {
		_, port, _ = net.SplitHostPort(addr)
	}
	p, err := strconv.ParseUint(port, 10, 16)
	if err != nil || p == 0 {
		log.Warn("mdns off: can't tell the port from BANDITBOX_ADDR; set BANDITBOX_ADVERTISE_PORT", "addr", addr)
		return
	}
	svc.Port = uint16(p)
	if ips := os.Getenv("BANDITBOX_ADVERTISE_IP"); ips != "" {
		for _, s := range strings.Split(ips, ",") {
			ip, err := netip.ParseAddr(strings.TrimSpace(s))
			if err != nil || !ip.Is4() {
				log.Warn("mdns off: BANDITBOX_ADVERTISE_IP must be IPv4 addresses", "value", ips)
				return
			}
			svc.IPs = append(svc.IPs, ip)
		}
	}
	if err := mdns.Run(ctx, svc, log); err != nil {
		log.Warn("mdns off: TVs will need the server address typed in", "err", err)
	}
}

// cleanSessions removes expired web UI sessions once a day.
func cleanSessions(ctx context.Context, st *store.Store, log *slog.Logger) {
	t := time.NewTicker(24 * time.Hour)
	defer t.Stop()
	for {
		if err := st.DeleteExpiredSessions(ctx); err != nil && ctx.Err() == nil {
			log.Warn("clean sessions", "err", err)
		}
		select {
		case <-ctx.Done():
			return
		case <-t.C:
		}
	}
}

func envOr(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}
