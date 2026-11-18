package apk

import (
	"archive/zip"
	"bytes"
	"encoding/binary"
	"os"
	"testing"
	"unicode/utf16"
)

// manifest builds a binary AndroidManifest.xml the way aapt2 lays it out.
func manifest(utf8 bool, pkg, versionName string, versionCode uint32) []byte {
	le := binary.LittleEndian
	// Attribute names with resource ids come first, matching the resource map.
	strs := []string{"versionCode", "versionName", "android", "http://schemas.android.com/apk/res/android", "package", "manifest", pkg, versionName}

	var pool bytes.Buffer
	var offsets []uint32
	for _, s := range strs {
		offsets = append(offsets, uint32(pool.Len()))
		if utf8 {
			pool.WriteByte(byte(len([]rune(s))))
			pool.WriteByte(byte(len(s)))
			pool.WriteString(s)
			pool.WriteByte(0)
		} else {
			u := utf16.Encode([]rune(s))
			binary.Write(&pool, le, uint16(len(u)))
			binary.Write(&pool, le, u)
			binary.Write(&pool, le, uint16(0))
		}
	}
	for pool.Len()%4 != 0 {
		pool.WriteByte(0)
	}
	var sp bytes.Buffer
	hdr := 28
	start := hdr + 4*len(strs)
	var flags uint32
	if utf8 {
		flags = utf8Flag
	}
	put(&sp, []any{uint16(chunkStringPool), uint16(hdr), uint32(start + pool.Len()), uint32(len(strs)), uint32(0), flags, uint32(start), uint32(0)})
	binary.Write(&sp, le, offsets)
	sp.Write(pool.Bytes())

	var rm bytes.Buffer
	put(&rm, []any{uint16(chunkResourceMap), uint16(8), uint32(16), uint32(attrVersionCode), uint32(attrVersionName)})

	attr := func(ns, name, raw uint32, typ byte, data uint32) []any {
		return []any{ns, name, raw, uint16(8), byte(0), typ, data}
	}
	var el bytes.Buffer
	attrs := [][]any{
		attr(3, 0, 0xffffffff, typeIntDec, versionCode),
		attr(3, 1, 7, typeString, 7),
		attr(0xffffffff, 4, 6, typeString, 6),
	}
	size := 16 + 20 + 20*len(attrs)
	put(&el, []any{uint16(chunkStartElement), uint16(16), uint32(size), uint32(1), uint32(0xffffffff)})
	put(&el, []any{uint32(0xffffffff), uint32(5), uint16(20), uint16(20), uint16(len(attrs)), uint16(0), uint16(0), uint16(0)})
	for _, a := range attrs {
		put(&el, a)
	}

	var out bytes.Buffer
	total := 8 + sp.Len() + rm.Len() + el.Len()
	put(&out, []any{uint16(chunkXML), uint16(8), uint32(total)})
	out.Write(sp.Bytes())
	out.Write(rm.Bytes())
	out.Write(el.Bytes())
	return out.Bytes()
}

// put writes each value in little-endian order.
func put(w *bytes.Buffer, vals []any) {
	for _, v := range vals {
		if err := binary.Write(w, binary.LittleEndian, v); err != nil {
			panic(err)
		}
	}
}

func apkWith(t *testing.T, files map[string][]byte) *bytes.Reader {
	t.Helper()
	var buf bytes.Buffer
	z := zip.NewWriter(&buf)
	for name, data := range files {
		w, _ := z.Create(name)
		w.Write(data)
	}
	if err := z.Close(); err != nil {
		t.Fatal(err)
	}
	return bytes.NewReader(buf.Bytes())
}

func TestReadsManifest(t *testing.T) {
	for _, utf8 := range []bool{false, true} {
		r := apkWith(t, map[string][]byte{
			"classes.dex":         {1, 2, 3},
			"AndroidManifest.xml": manifest(utf8, "com.spacegoose.banditbox", "0.2.0", 7),
		})
		info, err := Read(r, r.Size())
		if err != nil {
			t.Fatalf("utf8=%v: %v", utf8, err)
		}
		want := Info{Package: "com.spacegoose.banditbox", VersionCode: 7, VersionName: "0.2.0"}
		if info != want {
			t.Fatalf("utf8=%v: got %+v, want %+v", utf8, info, want)
		}
	}
}

func TestRejectsOtherFiles(t *testing.T) {
	for name, r := range map[string]*bytes.Reader{
		"not a zip":   bytes.NewReader([]byte("hello")),
		"no manifest": apkWith(t, map[string][]byte{"a.txt": []byte("x")}),
		"text xml":    apkWith(t, map[string][]byte{"AndroidManifest.xml": []byte("<manifest/>")}),
		"truncated":   apkWith(t, map[string][]byte{"AndroidManifest.xml": manifest(false, "a.b", "1", 1)[:60]}),
	} {
		if _, err := Read(r, r.Size()); err == nil {
			t.Errorf("%s: accepted", name)
		}
	}
}

func TestSampleFile(t *testing.T) {
	f, err := os.Open("testdata/banditbox-0.2.0.apk")
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()
	st, _ := f.Stat()
	if info, err := Read(f, st.Size()); err != nil || info.VersionCode != 7 {
		t.Fatalf("sample = %+v, %v", info, err)
	}
}
