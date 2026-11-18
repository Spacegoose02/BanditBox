// Command banditbox runs the BanditBox master sync server.
package main

import (
	"context"
	"errors"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/spacegoose02/banditbox/server/internal/api"
	"github.com/spacegoose02/banditbox/server/internal/auth"
	"github.com/spacegoose02/banditbox/server/internal/hub"
	"github.com/spacegoose02/banditbox/server/internal/pairing"
	"github.com/spacegoose02/banditbox/server/internal/store"
)

// version is set at build time with -ldflags "-X main.version=...".
var version = "dev"

func main() {
	addr := envOr("BANDITBOX_ADDR", ":8686")
	dataDir := envOr("BANDITBOX_DATA", "/config")
	log := slog.New(slog.NewTextHandler(os.Stdout, nil))

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
