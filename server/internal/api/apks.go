package api

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"io"
	"mime"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"strings"

	"github.com/spacegoose02/banditbox/server/internal/apk"
	"github.com/spacegoose02/banditbox/server/internal/store"
)

// maxAPK caps an uploaded APK.
const maxAPK = 512 << 20

var shaRE = regexp.MustCompile(`^[0-9a-f]{64}$`)

func (s *server) apkPath(sha string) string { return filepath.Join(s.APKDir, sha+".apk") }

func (s *server) listAPKs(w http.ResponseWriter, r *http.Request, _ store.Admin) {
	list, err := s.Store.ListAPKs(r.Context())
	if err != nil {
		s.internal(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, list)
}

// uploadAPK stores an APK sent as the request body (Content-Type
// application/vnd.android.package-archive), with its file name in X-File-Name.
func (s *server) uploadAPK(w http.ResponseWriter, r *http.Request, _ store.Admin) {
	if ct, _, _ := mime.ParseMediaType(r.Header.Get("Content-Type")); ct != "application/vnd.android.package-archive" {
		writeError(w, http.StatusUnsupportedMediaType, "send the APK with Content-Type: application/vnd.android.package-archive")
		return
	}
	if err := os.MkdirAll(s.APKDir, 0o750); err != nil {
		s.internal(w, r, err)
		return
	}
	tmp, err := os.CreateTemp(s.APKDir, ".upload-*")
	if err != nil {
		s.internal(w, r, err)
		return
	}
	defer os.Remove(tmp.Name()) // no-op once renamed
	defer tmp.Close()

	h := sha256.New()
	size, err := io.Copy(io.MultiWriter(tmp, h), http.MaxBytesReader(w, r.Body, maxAPK))
	var tooBig *http.MaxBytesError
	if errors.As(err, &tooBig) {
		writeError(w, http.StatusRequestEntityTooLarge, "the APK is larger than 512 MB")
		return
	}
	if err != nil {
		writeError(w, http.StatusBadRequest, "upload interrupted")
		return
	}
	info, err := apk.Read(tmp, size)
	if err != nil {
		writeError(w, http.StatusUnprocessableEntity, "that file isn't an Android APK")
		return
	}
	sha := hex.EncodeToString(h.Sum(nil))
	if err := tmp.Close(); err != nil {
		s.internal(w, r, err)
		return
	}
	if err := os.Rename(tmp.Name(), s.apkPath(sha)); err != nil {
		s.internal(w, r, err)
		return
	}
	name := r.Header.Get("X-File-Name") // the web UI sends it URL-encoded
	if u, err := url.PathUnescape(name); err == nil {
		name = u
	}
	name = filepath.Base(strings.ReplaceAll(name, `\`, "/"))
	if len(name) > 200 || name == "." || name == "/" {
		name = ""
	}
	a, err := s.Store.AddAPK(r.Context(), store.APK{
		SHA256: sha, Package: info.Package, VersionCode: info.VersionCode, VersionName: info.VersionName,
		FileName: name, Size: size,
	})
	if err != nil {
		s.internal(w, r, err)
		return
	}
	writeJSON(w, http.StatusCreated, a)
}

func (s *server) deleteAPK(w http.ResponseWriter, r *http.Request, _ store.Admin) {
	sha := r.PathValue("sha")
	if err := s.Store.DeleteAPK(r.Context(), sha); errors.Is(err, store.ErrNotFound) {
		writeError(w, http.StatusNotFound, "no such APK")
		return
	} else if err != nil {
		s.internal(w, r, err)
		return
	}
	if shaRE.MatchString(sha) {
		os.Remove(s.apkPath(sha))
	}
	w.WriteHeader(http.StatusNoContent)
}

type installRequest struct {
	// Devices to install on; empty means every paired TV.
	Devices []string `json:"devices"`
}

// installAPK sends an APK to TVs. TVs in front get told at once; the rest on their next sync.
func (s *server) installAPK(w http.ResponseWriter, r *http.Request, _ store.Admin) {
	var req installRequest
	if !readJSON(w, r, &req) {
		return
	}
	sha := r.PathValue("sha")
	if _, err := s.Store.GetAPK(r.Context(), sha); errors.Is(err, store.ErrNotFound) {
		writeError(w, http.StatusNotFound, "no such APK")
		return
	} else if err != nil {
		s.internal(w, r, err)
		return
	}
	ids, ok := s.deviceIDs(w, r, req.Devices)
	if !ok {
		return
	}
	if err := s.Store.QueueInstall(r.Context(), sha, ids); err != nil {
		s.internal(w, r, err)
		return
	}
	for _, id := range ids {
		s.Hub.InstallsChanged(id)
	}
	a, err := s.Store.GetAPK(r.Context(), sha)
	if err != nil {
		s.internal(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, a)
}

// deviceIDs checks the chosen TVs exist; none chosen means all of them.
func (s *server) deviceIDs(w http.ResponseWriter, r *http.Request, chosen []string) ([]string, bool) {
	all, err := s.Store.ListDevices(r.Context())
	if err != nil {
		s.internal(w, r, err)
		return nil, false
	}
	known := map[string]bool{}
	ids := []string{}
	for _, d := range all {
		known[d.ID] = true
		ids = append(ids, d.ID)
	}
	if len(chosen) == 0 {
		return ids, true
	}
	for _, id := range chosen {
		if !known[id] {
			writeError(w, http.StatusBadRequest, "no such TV: "+id)
			return nil, false
		}
	}
	return chosen, true
}

// deviceInstalls lists what this TV should install.
func (s *server) deviceInstalls(w http.ResponseWriter, r *http.Request, d store.Device) {
	list, err := s.Store.PendingInstalls(r.Context(), d.ID)
	if err != nil {
		s.internal(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, list)
}

// deviceAPK downloads an APK this TV was sent.
func (s *server) deviceAPK(w http.ResponseWriter, r *http.Request, d store.Device) {
	sha := r.PathValue("sha")
	if !s.sentTo(r.Context(), d.ID, sha) {
		writeError(w, http.StatusNotFound, "no such APK for this TV")
		return
	}
	w.Header().Set("Content-Type", "application/vnd.android.package-archive")
	http.ServeFile(w, r, s.apkPath(sha))
}

func (s *server) sentTo(ctx context.Context, deviceID, sha string) bool {
	if !shaRE.MatchString(sha) {
		return false
	}
	list, err := s.Store.PendingInstalls(ctx, deviceID)
	if err != nil {
		return false
	}
	for _, a := range list {
		if a.SHA256 == sha {
			return true
		}
	}
	return false
}

// deviceReport records what the TV did with an APK: {status, detail}.
func (s *server) deviceReport(w http.ResponseWriter, r *http.Request, d store.Device) {
	var req struct {
		Status string `json:"status"`
		Detail string `json:"detail"`
	}
	if !readJSON(w, r, &req) {
		return
	}
	if !store.ValidReportedStatus(req.Status) {
		writeError(w, http.StatusBadRequest, "status must be waiting, installed, skipped or failed")
		return
	}
	if err := s.Store.ReportInstall(r.Context(), d.ID, r.PathValue("sha"), req.Status, req.Detail); errors.Is(err, store.ErrNotFound) {
		writeError(w, http.StatusNotFound, "no such install for this TV")
		return
	} else if err != nil {
		s.internal(w, r, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}
