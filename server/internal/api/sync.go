package api

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"strconv"
	"time"

	"github.com/coder/websocket"
	"github.com/coder/websocket/wsjson"

	"github.com/spacegoose02/banditbox/server/internal/schema"
	"github.com/spacegoose02/banditbox/server/internal/store"
	"github.com/spacegoose02/banditbox/server/internal/syncdoc"
)

const (
	maxOpsPerBatch = 500
	pingEvery      = 30 * time.Second
)

type layoutResponse struct {
	ProfileID string          `json:"profileId"`
	Version   int64           `json:"version"`
	Doc       json.RawMessage `json:"doc"`
}

// syncLayout returns a profile's layout to a TV: GET /api/v1/sync/layout?profile=ID&since=V.
// It answers 204 when the TV already has the current version. Layouts are a few KB,
// so a full snapshot is always sent rather than the ops since V.
func (s *server) syncLayout(w http.ResponseWriter, r *http.Request, d store.Device) {
	id := r.URL.Query().Get("profile")
	if !s.allowed(w, d, id) {
		return
	}
	l, err := s.Store.GetLayout(r.Context(), id)
	if s.layoutError(w, r, err) {
		return
	}
	if v := r.URL.Query().Get("since"); v != "" {
		since, err := strconv.ParseInt(v, 10, 64)
		if err != nil {
			writeError(w, http.StatusBadRequest, "since must be a version number")
			return
		}
		if since == l.Version {
			w.WriteHeader(http.StatusNoContent)
			return
		}
	}
	writeJSON(w, http.StatusOK, layoutResponse{ProfileID: id, Version: l.Version, Doc: l.Doc})
}

type opsRequest struct {
	ProfileID   string            `json:"profileId"`
	BaseVersion int64             `json:"baseVersion"`
	Ops         []json.RawMessage `json:"ops"`
}

type opsResponse struct {
	store.OpsResult
	// Doc is the layout after the batch, so the TV can replace its copy in one round trip.
	Doc json.RawMessage `json:"doc"`
}

// syncOps receives a TV's queued changes: POST /api/v1/sync/ops.
func (s *server) syncOps(w http.ResponseWriter, r *http.Request, d store.Device) {
	var req opsRequest
	if !readJSON(w, r, &req) {
		return
	}
	if !s.allowed(w, d, req.ProfileID) {
		return
	}
	if len(req.Ops) > maxOpsPerBatch {
		writeError(w, http.StatusRequestEntityTooLarge, "send at most "+strconv.Itoa(maxOpsPerBatch)+" ops per batch")
		return
	}
	var ops []syncdoc.Op
	var bad []store.Rejected
	for i, raw := range req.Ops {
		var op syncdoc.Op
		_ = json.Unmarshal(raw, &op)
		if err := schema.ValidateOp(raw); err != nil {
			id := op.OpID
			if id == "" {
				id = "#" + strconv.Itoa(i)
			}
			bad = append(bad, store.Rejected{OpID: id, Reason: store.RejectInvalid, Detail: err.Error()})
			continue
		}
		op.DeviceID = d.ID
		ops = append(ops, op)
	}

	before, err := s.Store.GetLayout(r.Context(), req.ProfileID)
	if s.layoutError(w, r, err) {
		return
	}
	res, err := s.Store.ApplyOps(r.Context(), req.ProfileID, d.ID, req.BaseVersion, ops)
	if errors.Is(err, store.ErrFutureBase) {
		writeError(w, http.StatusConflict, err.Error())
		return
	}
	if s.layoutError(w, r, err) {
		return
	}
	res.Rejected = append(res.Rejected, bad...)
	if res.Version != before.Version {
		s.Hub.Publish(req.ProfileID, res.Version)
	}
	if len(res.Rejected) > 0 {
		s.Log.Info("sync ops rejected", "device", d.ID, "profile", req.ProfileID, "rejected", len(res.Rejected))
	}
	l, err := s.Store.GetLayout(r.Context(), req.ProfileID)
	if err != nil {
		s.internal(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, opsResponse{OpsResult: res, Doc: l.Doc})
}

// streamMessage is sent over the sync WebSocket.
type streamMessage struct {
	Type      string `json:"type"` // "version"
	ProfileID string `json:"profileId,omitempty"`
	Version   int64  `json:"version,omitempty"`
}

// syncStream is the WebSocket a TV keeps open while the launcher is in front.
// On connect it sends the current version of every profile the TV may sync, then
// one message whenever a profile changes. The server pings every 30 seconds.
func (s *server) syncStream(w http.ResponseWriter, r *http.Request, d store.Device) {
	conn, err := websocket.Accept(w, r, nil)
	if err != nil {
		return
	}
	defer conn.CloseNow()
	conn.SetReadLimit(4096)
	// The TV never sends anything we need; CloseRead handles its pongs and close.
	ctx := conn.CloseRead(r.Context())

	sub := s.Hub.Subscribe(d.ID)
	defer s.Hub.Unsubscribe(sub)

	list, err := s.Store.ListProfiles(ctx)
	if err != nil {
		conn.Close(websocket.StatusInternalError, "internal error")
		return
	}
	for _, p := range list {
		if d.AllowsProfile(p.ID) {
			if err := send(ctx, conn, streamMessage{Type: "version", ProfileID: p.ID, Version: p.Version}); err != nil {
				return
			}
		}
	}

	ping := time.NewTicker(pingEvery)
	defer ping.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ping.C:
			pctx, cancel := context.WithTimeout(ctx, 10*time.Second)
			err := conn.Ping(pctx)
			cancel()
			if err != nil {
				return
			}
		case <-sub.Ready:
			versions, kicked := sub.Take()
			if kicked {
				conn.Close(4401, "this TV is no longer paired")
				return
			}
			// Re-read the device so profile changes made in the web UI apply at once.
			cur, err := s.Store.GetDevice(ctx, d.ID)
			if err != nil {
				conn.Close(4401, "this TV is no longer paired")
				return
			}
			for id, v := range versions {
				if cur.AllowsProfile(id) {
					if err := send(ctx, conn, streamMessage{Type: "version", ProfileID: id, Version: v}); err != nil {
						return
					}
				}
			}
		}
	}
}

func send(ctx context.Context, conn *websocket.Conn, m streamMessage) error {
	wctx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	return wsjson.Write(wctx, conn, m)
}

// allowed checks the device may sync profileID, writing 403 if not.
func (s *server) allowed(w http.ResponseWriter, d store.Device, profileID string) bool {
	if profileID == "" {
		writeError(w, http.StatusBadRequest, "profile is required")
		return false
	}
	if !d.AllowsProfile(profileID) {
		writeError(w, http.StatusForbidden, "this TV may not sync that profile")
		return false
	}
	return true
}
