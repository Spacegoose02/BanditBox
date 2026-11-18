// Package apk reads the package name and version from an Android APK, so the server knows
// what an uploaded file is without running Android tools.
package apk

import (
	"archive/zip"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"unicode/utf16"
)

// Info is what the server needs to know about an APK.
type Info struct {
	Package     string `json:"package"`
	VersionCode int64  `json:"versionCode"`
	VersionName string `json:"versionName"`
}

// ErrNotAPK means the file isn't an APK this reader understands.
var ErrNotAPK = errors.New("not an Android APK")

// Read reads the manifest of the APK in r (size bytes long).
func Read(r io.ReaderAt, size int64) (Info, error) {
	z, err := zip.NewReader(r, size)
	if err != nil {
		return Info{}, ErrNotAPK
	}
	for _, f := range z.File {
		if f.Name != "AndroidManifest.xml" {
			continue
		}
		if f.UncompressedSize64 > 8<<20 {
			return Info{}, ErrNotAPK
		}
		rc, err := f.Open()
		if err != nil {
			return Info{}, ErrNotAPK
		}
		defer rc.Close()
		data, err := io.ReadAll(io.LimitReader(rc, 8<<20))
		if err != nil {
			return Info{}, ErrNotAPK
		}
		return parseManifest(data)
	}
	return Info{}, ErrNotAPK
}

// Chunk types and attribute ids from Android's binary XML format (ResourceTypes.h).
const (
	chunkXML          = 0x0003
	chunkStringPool   = 0x0001
	chunkResourceMap  = 0x0180
	chunkStartElement = 0x0102

	attrVersionCode = 0x0101021b
	attrVersionName = 0x0101021c

	typeString = 0x03
	typeIntDec = 0x10
	typeIntHex = 0x11

	utf8Flag = 1 << 8
)

// parseManifest reads the <manifest> element's package, versionCode and versionName.
func parseManifest(b []byte) (Info, error) {
	le := binary.LittleEndian
	if len(b) < 8 || le.Uint16(b) != chunkXML {
		return Info{}, ErrNotAPK
	}
	var strs []string
	var resIDs []uint32
	off := int(le.Uint16(b[2:]))
	for off+8 <= len(b) {
		typ := le.Uint16(b[off:])
		hdr := int(le.Uint16(b[off+2:]))
		size := int(le.Uint32(b[off+4:]))
		if size < 8 || off+size > len(b) || hdr > size {
			return Info{}, ErrNotAPK
		}
		chunk := b[off : off+size]
		switch typ {
		case chunkStringPool:
			s, err := stringPool(chunk)
			if err != nil {
				return Info{}, err
			}
			strs = s
		case chunkResourceMap:
			for i := hdr; i+4 <= size; i += 4 {
				resIDs = append(resIDs, le.Uint32(chunk[i:]))
			}
		case chunkStartElement:
			if hdr+20 > size {
				return Info{}, ErrNotAPK
			}
			ext := chunk[hdr:]
			if str(strs, le.Uint32(ext[4:])) != "manifest" {
				break
			}
			attrStart := int(le.Uint16(ext[8:]))
			attrSize := int(le.Uint16(ext[10:]))
			count := int(le.Uint16(ext[12:]))
			if attrSize < 20 {
				return Info{}, ErrNotAPK
			}
			var info Info
			for i := range count {
				a := hdr + attrStart + i*attrSize
				if a+20 > size {
					return Info{}, ErrNotAPK
				}
				at := chunk[a:]
				nameIdx := le.Uint32(at[4:])
				name := str(strs, nameIdx)
				var resID uint32
				if int(nameIdx) < len(resIDs) {
					resID = resIDs[nameIdx]
				}
				raw := le.Uint32(at[8:])
				dataType := at[15]
				data := le.Uint32(at[16:])
				switch {
				case name == "package":
					info.Package = str(strs, raw)
				case resID == attrVersionCode || name == "versionCode":
					if dataType == typeIntDec || dataType == typeIntHex {
						info.VersionCode = int64(data)
					}
				case resID == attrVersionName || name == "versionName":
					if raw != 0xffffffff {
						info.VersionName = str(strs, raw)
					} else if dataType == typeString {
						info.VersionName = str(strs, data)
					}
				}
			}
			if info.Package == "" {
				return Info{}, ErrNotAPK
			}
			return info, nil
		}
		off += size
	}
	return Info{}, ErrNotAPK
}

func str(strs []string, i uint32) string {
	if int64(i) < int64(len(strs)) {
		return strs[i]
	}
	return ""
}

func stringPool(c []byte) ([]string, error) {
	le := binary.LittleEndian
	if len(c) < 28 {
		return nil, ErrNotAPK
	}
	hdr := int(le.Uint16(c[2:]))
	count := int(le.Uint32(c[8:]))
	flags := le.Uint32(c[16:])
	start := int(le.Uint32(c[20:]))
	if count > 1<<20 || hdr+count*4 > len(c) || start > len(c) {
		return nil, ErrNotAPK
	}
	out := make([]string, count)
	for i := range count {
		p := start + int(le.Uint32(c[hdr+i*4:]))
		if p >= len(c) {
			return nil, ErrNotAPK
		}
		s, err := readString(c[p:], flags&utf8Flag != 0)
		if err != nil {
			return nil, fmt.Errorf("%w: string %d", ErrNotAPK, i)
		}
		out[i] = s
	}
	return out, nil
}

func readString(b []byte, utf8 bool) (string, error) {
	if utf8 {
		// Length in characters, then in bytes; each is one byte, or two when the high bit is set.
		_, n1 := len8(b)
		if n1 == 0 || n1 >= len(b) {
			return "", ErrNotAPK
		}
		blen, n2 := len8(b[n1:])
		if n2 == 0 || n1+n2+blen > len(b) {
			return "", ErrNotAPK
		}
		return string(b[n1+n2 : n1+n2+blen]), nil
	}
	if len(b) < 2 {
		return "", ErrNotAPK
	}
	le := binary.LittleEndian
	n := int(le.Uint16(b))
	p := 2
	if n&0x8000 != 0 {
		if len(b) < 4 {
			return "", ErrNotAPK
		}
		n = (n&0x7fff)<<16 | int(le.Uint16(b[2:]))
		p = 4
	}
	if p+n*2 > len(b) {
		return "", ErrNotAPK
	}
	u := make([]uint16, n)
	for i := range n {
		u[i] = le.Uint16(b[p+i*2:])
	}
	return string(utf16.Decode(u)), nil
}

func len8(b []byte) (int, int) {
	if len(b) == 0 {
		return 0, 0
	}
	if b[0]&0x80 == 0 {
		return int(b[0]), 1
	}
	if len(b) < 2 {
		return 0, 0
	}
	return int(b[0]&0x7f)<<8 | int(b[1]), 2
}
