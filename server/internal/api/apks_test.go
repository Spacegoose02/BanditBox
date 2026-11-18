package api

import (
	"bytes"
	"io"
	"net/http"
	"os"
	"testing"

	"github.com/spacegoose02/banditbox/server/internal/store"
)

func (e *env) tvRequest(method, path, token string, body any, out any) int {
	e.t.Helper()
	tv := &env{t: e.t, srv: e.srv, c: &http.Client{Transport: bearer(token)}}
	return tv.do(method, path, body, out)
}

type bearer string

func (b bearer) RoundTrip(r *http.Request) (*http.Response, error) {
	r = r.Clone(r.Context())
	r.Header.Set("Authorization", "Bearer "+string(b))
	return http.DefaultTransport.RoundTrip(r)
}

func TestAPKUploadAndInstall(t *testing.T) {
	e := newEnv(t)
	e.signIn()
	token, deviceID := e.pairTV(nil)
	other, _ := e.pairTV(nil)

	file, err := os.ReadFile("../apk/testdata/banditbox-0.2.0.apk")
	if err != nil {
		t.Fatal(err)
	}
	upload := func(ct string, body []byte) (*http.Response, store.APK) {
		req, _ := http.NewRequest("POST", e.srv.URL+"/api/v1/apks", bytes.NewReader(body))
		req.Header.Set("Content-Type", ct)
		req.Header.Set("X-File-Name", `C:\Downloads\banditbox.apk`)
		res, err := e.c.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		defer res.Body.Close()
		var a store.APK
		if res.StatusCode == 201 {
			decode(t, res, &a)
		}
		return res, a
	}
	if res, _ := upload("text/plain", file); res.StatusCode != 415 {
		t.Fatalf("wrong type = %d", res.StatusCode)
	}
	if res, _ := upload("application/vnd.android.package-archive", []byte("not a zip")); res.StatusCode != 422 {
		t.Fatalf("junk = %d", res.StatusCode)
	}
	res, a := upload("application/vnd.android.package-archive", file)
	if res.StatusCode != 201 || a.Package != "com.spacegoose.banditbox" || a.VersionCode != 7 || a.FileName != "banditbox.apk" {
		t.Fatalf("upload = %d %+v", res.StatusCode, a)
	}

	// Send it to one TV.
	if c := e.do("POST", "/api/v1/apks/"+a.SHA256+"/install", installRequest{Devices: []string{deviceID}}, &a); c != 200 || len(a.Installs) != 1 {
		t.Fatalf("install = %d %+v", c, a)
	}
	var pending []store.APK
	if c := e.tvRequest("GET", "/api/v1/device/installs", token, nil, &pending); c != 200 || len(pending) != 1 {
		t.Fatalf("pending = %d %+v", c, pending)
	}
	if c := e.tvRequest("GET", "/api/v1/device/installs", other, nil, &pending); c != 200 || len(pending) != 0 {
		t.Fatalf("other TV pending = %+v", pending)
	}

	// Only the TV it was sent to may download it.
	get := func(tok string) (int, []byte) {
		req, _ := http.NewRequest("GET", e.srv.URL+"/api/v1/device/apks/"+a.SHA256, nil)
		req.Header.Set("Authorization", "Bearer "+tok)
		res, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		defer res.Body.Close()
		b, _ := io.ReadAll(res.Body)
		return res.StatusCode, b
	}
	if c, b := get(token); c != 200 || !bytes.Equal(b, file) {
		t.Fatalf("download = %d, %d bytes", c, len(b))
	}
	if c, _ := get(other); c != 404 {
		t.Fatalf("other TV download = %d", c)
	}

	if c := e.tvRequest("POST", "/api/v1/device/installs/"+a.SHA256, token, map[string]string{"status": "bogus"}, nil); c != 400 {
		t.Fatalf("bad status = %d", c)
	}
	if c := e.tvRequest("POST", "/api/v1/device/installs/"+a.SHA256, token, map[string]string{"status": "installed"}, nil); c != 204 {
		t.Fatalf("report = %d", c)
	}
	e.tvRequest("GET", "/api/v1/device/installs", token, nil, &pending)
	if len(pending) != 0 {
		t.Fatalf("still pending after install: %+v", pending)
	}
	var list []store.APK
	e.do("GET", "/api/v1/apks", nil, &list)
	if len(list) != 1 || list[0].Installs[0].Status != "installed" {
		t.Fatalf("list = %+v", list)
	}

	// All TVs.
	e.do("POST", "/api/v1/apks/"+a.SHA256+"/install", installRequest{}, &a)
	if len(a.Installs) != 2 {
		t.Fatalf("install on all = %+v", a.Installs)
	}
	if c := e.do("DELETE", "/api/v1/apks/"+a.SHA256, nil, nil); c != 204 {
		t.Fatalf("delete = %d", c)
	}
	if c := e.tvRequest("GET", "/api/v1/device/installs", other, nil, &pending); c != 200 || len(pending) != 0 {
		t.Fatalf("after delete = %+v", pending)
	}
}
