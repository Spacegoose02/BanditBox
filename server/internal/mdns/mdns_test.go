package mdns

import (
	"net/netip"
	"testing"

	"golang.org/x/net/dns/dnsmessage"
)

func testResponder() *responder {
	return &responder{svc: Service{
		Instance: "BanditBox",
		Host:     "tower",
		Port:     8686,
		IPs:      []netip.Addr{netip.MustParseAddr("192.168.80.10")},
		TXT:      []string{"path=/api/v1", "version=test"},
	}}
}

func query(t *testing.T, id uint16, name string, typ dnsmessage.Type, class dnsmessage.Class) []byte {
	t.Helper()
	msg := dnsmessage.Message{
		Header:    dnsmessage.Header{ID: id},
		Questions: []dnsmessage.Question{{Name: dnsmessage.MustNewName(name), Type: typ, Class: class}},
	}
	b, err := msg.Pack()
	if err != nil {
		t.Fatal(err)
	}
	return b
}

func parse(t *testing.T, b []byte) dnsmessage.Message {
	t.Helper()
	var m dnsmessage.Message
	if err := m.Unpack(b); err != nil {
		t.Fatal(err)
	}
	return m
}

func TestBrowseAnswersWithEverything(t *testing.T) {
	r := testResponder()
	resp, unicast := r.answer(query(t, 0, ServiceType, dnsmessage.TypePTR, dnsmessage.ClassINET), false)
	if resp == nil || unicast {
		t.Fatalf("resp=%v unicast=%v", resp != nil, unicast)
	}
	m := parse(t, resp)
	if len(m.Answers) != 1 || m.Answers[0].Body.(*dnsmessage.PTRResource).PTR.String() != "BanditBox._banditbox._tcp.local." {
		t.Fatalf("answers %+v", m.Answers)
	}
	var srv *dnsmessage.SRVResource
	var a *dnsmessage.AResource
	for _, x := range m.Additionals {
		switch b := x.Body.(type) {
		case *dnsmessage.SRVResource:
			srv = b
		case *dnsmessage.AResource:
			a = b
		}
	}
	if srv == nil || srv.Port != 8686 || srv.Target.String() != "tower.local." {
		t.Fatalf("srv %+v", srv)
	}
	if a == nil || a.A != [4]byte{192, 168, 80, 10} {
		t.Fatalf("a %+v", a)
	}
}

func TestIgnoresOtherServicesAndResponses(t *testing.T) {
	r := testResponder()
	if resp, _ := r.answer(query(t, 0, "_googlecast._tcp.local.", dnsmessage.TypePTR, dnsmessage.ClassINET), false); resp != nil {
		t.Fatal("answered someone else's service")
	}
	ann, _ := r.announcement(false)
	if resp, _ := r.answer(ann, false); resp != nil {
		t.Fatal("answered a response")
	}
}

func TestLegacyAndQUQueriesGetUnicast(t *testing.T) {
	r := testResponder()
	resp, unicast := r.answer(query(t, 42, ServiceType, dnsmessage.TypePTR, dnsmessage.ClassINET), true)
	m := parse(t, resp)
	if !unicast || m.Header.ID != 42 || len(m.Questions) != 1 {
		t.Fatalf("legacy: unicast=%v header=%+v", unicast, m.Header)
	}
	_, unicast = r.answer(query(t, 0, "tower.local.", dnsmessage.TypeA, dnsmessage.ClassINET|0x8000), false)
	if !unicast {
		t.Fatal("QU bit ignored")
	}
}

func TestGoodbyeHasZeroTTL(t *testing.T) {
	b, _ := testResponder().announcement(true)
	for _, a := range parse(t, b).Answers {
		if a.Header.TTL != 0 {
			t.Fatalf("ttl %d", a.Header.TTL)
		}
	}
}
