// Package mdns advertises the server on the LAN as _banditbox._tcp so TVs can
// find it without typing an address. It is a minimal responder: it answers
// questions about this one service and announces it at start and stop.
//
// Multicast only reaches the LAN when the container uses host networking or
// its own LAN address (Unraid's br0). On Docker's default bridge it does
// nothing useful, and TVs fall back to typing the address.
package mdns

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"net"
	"net/netip"
	"os"
	"strings"
	"time"

	"golang.org/x/net/dns/dnsmessage"
	"golang.org/x/net/ipv4"
)

// ServiceType is the DNS-SD service type TVs browse for.
const ServiceType = "_banditbox._tcp.local."

const (
	ttl      = 120 // seconds, the usual for SRV/TXT/A
	ptrTTL   = 4500
	mdnsPort = 5353
)

var group = netip.MustParseAddr("224.0.0.251")

// Service describes what to advertise.
type Service struct {
	Instance string       // shown to people, for example "BanditBox"
	Host     string       // host label without .local, for example "tower"
	Port     uint16       // port the API listens on, as reachable from the LAN
	IPs      []netip.Addr // IPv4 addresses to advertise; empty means the machine's own
	TXT      []string     // key=value pairs
}

// Run answers mDNS queries until ctx ends. It returns an error only if it can't
// listen at all; problems on single interfaces are logged.
func Run(ctx context.Context, svc Service, log *slog.Logger) error {
	if len(svc.IPs) == 0 {
		svc.IPs = localIPs()
	}
	if len(svc.IPs) == 0 {
		return errors.New("no LAN IPv4 address to advertise")
	}
	lc := net.ListenConfig{Control: reuse}
	pconn, err := lc.ListenPacket(ctx, "udp4", fmt.Sprintf(":%d", mdnsPort))
	if err != nil {
		return fmt.Errorf("listen on udp/5353: %w", err)
	}
	conn := pconn.(*net.UDPConn)
	pc := ipv4.NewPacketConn(conn)
	joined := 0
	ifaces, _ := net.Interfaces()
	for _, ifi := range ifaces {
		if ifi.Flags&net.FlagUp == 0 || ifi.Flags&net.FlagMulticast == 0 || ifi.Flags&net.FlagLoopback != 0 {
			continue
		}
		if err := pc.JoinGroup(&ifi, &net.UDPAddr{IP: group.AsSlice()}); err == nil {
			joined++
		}
	}
	if joined == 0 {
		conn.Close()
		return errors.New("could not join the mDNS group on any interface")
	}
	_ = pc.SetMulticastTTL(255)
	_ = pc.SetMulticastLoopback(true)

	r := &responder{svc: svc}
	dst := &net.UDPAddr{IP: group.AsSlice(), Port: mdnsPort}
	announce := func(goodbye bool) {
		if msg, err := r.announcement(goodbye); err == nil {
			_, _ = conn.WriteToUDP(msg, dst)
		}
	}
	log.Info("mdns advertising", "service", ServiceType, "instance", svc.Instance, "ips", svc.IPs, "port", svc.Port)

	go func() {
		// RFC 6762 §8.3: announce twice, a second apart.
		announce(false)
		select {
		case <-ctx.Done():
		case <-time.After(time.Second):
			announce(false)
		}
		<-ctx.Done()
		announce(true)
		conn.Close()
	}()

	buf := make([]byte, 9000)
	for {
		n, from, err := conn.ReadFromUDP(buf)
		if err != nil {
			if ctx.Err() != nil {
				return nil
			}
			return err
		}
		resp, unicast := r.answer(buf[:n], from.Port != mdnsPort)
		if resp == nil {
			continue
		}
		to := dst
		if unicast {
			to = from
		}
		if _, err := conn.WriteToUDP(resp, to); err != nil {
			log.Debug("mdns reply", "err", err)
		}
	}
}

type responder struct {
	svc Service
}

func (r *responder) instanceName() string { return r.svc.Instance + "." + ServiceType }
func (r *responder) hostName() string     { return r.svc.Host + ".local." }

func mustName(s string) dnsmessage.Name { return dnsmessage.MustNewName(s) }

// records returns every record we own, keyed by what they answer.
func (r *responder) records(ttlScale uint32) (ptr, srv, txt dnsmessage.Resource, a []dnsmessage.Resource) {
	cls := dnsmessage.ClassINET
	flush := dnsmessage.Class(0x8000) | cls // cache-flush bit for unique records
	ptr = dnsmessage.Resource{
		Header: dnsmessage.ResourceHeader{Name: mustName(ServiceType), Type: dnsmessage.TypePTR, Class: cls, TTL: ptrTTL * ttlScale},
		Body:   &dnsmessage.PTRResource{PTR: mustName(r.instanceName())},
	}
	srv = dnsmessage.Resource{
		Header: dnsmessage.ResourceHeader{Name: mustName(r.instanceName()), Type: dnsmessage.TypeSRV, Class: flush, TTL: ttl * ttlScale},
		Body:   &dnsmessage.SRVResource{Port: r.svc.Port, Target: mustName(r.hostName())},
	}
	txt = dnsmessage.Resource{
		Header: dnsmessage.ResourceHeader{Name: mustName(r.instanceName()), Type: dnsmessage.TypeTXT, Class: flush, TTL: ttl * ttlScale},
		Body:   &dnsmessage.TXTResource{TXT: append([]string{}, r.svc.TXT...)},
	}
	for _, ip := range r.svc.IPs {
		a = append(a, dnsmessage.Resource{
			Header: dnsmessage.ResourceHeader{Name: mustName(r.hostName()), Type: dnsmessage.TypeA, Class: flush, TTL: ttl * ttlScale},
			Body:   &dnsmessage.AResource{A: ip.As4()},
		})
	}
	return
}

func (r *responder) announcement(goodbye bool) ([]byte, error) {
	scale := uint32(1)
	if goodbye {
		scale = 0
	}
	ptr, srv, txt, a := r.records(scale)
	msg := dnsmessage.Message{
		Header:  dnsmessage.Header{Response: true, Authoritative: true},
		Answers: append([]dnsmessage.Resource{ptr, srv, txt}, a...),
	}
	return msg.Pack()
}

// answer builds the reply to a query, or nil if it isn't about us. legacy is
// true for one-shot queries from a port other than 5353, which get a unicast
// reply echoing the query id and questions (RFC 6762 §6.7).
func (r *responder) answer(query []byte, legacy bool) (resp []byte, unicast bool) {
	var p dnsmessage.Parser
	h, err := p.Start(query)
	if err != nil || h.Response {
		return nil, false
	}
	qs, err := p.AllQuestions()
	if err != nil {
		return nil, false
	}
	ptr, srv, txt, a := r.records(1)
	var answers, extra []dnsmessage.Resource
	wantUnicast := legacy
	for _, q := range qs {
		name := strings.ToLower(q.Name.String())
		all := q.Type == dnsmessage.TypeALL
		if q.Class&0x8000 != 0 {
			wantUnicast = true // QU bit
		}
		switch {
		case name == "_services._dns-sd._udp.local." && (q.Type == dnsmessage.TypePTR || all):
			answers = append(answers, dnsmessage.Resource{
				Header: dnsmessage.ResourceHeader{Name: q.Name, Type: dnsmessage.TypePTR, Class: dnsmessage.ClassINET, TTL: ptrTTL},
				Body:   &dnsmessage.PTRResource{PTR: mustName(ServiceType)},
			})
		case name == ServiceType && (q.Type == dnsmessage.TypePTR || all):
			answers = append(answers, ptr)
			extra = append(append(extra, srv, txt), a...)
		case name == strings.ToLower(r.instanceName()):
			if q.Type == dnsmessage.TypeSRV || all {
				answers = append(answers, srv)
				extra = append(extra, a...)
			}
			if q.Type == dnsmessage.TypeTXT || all {
				answers = append(answers, txt)
			}
		case name == strings.ToLower(r.hostName()) && (q.Type == dnsmessage.TypeA || all):
			answers = append(answers, a...)
		}
	}
	if len(answers) == 0 {
		return nil, false
	}
	msg := dnsmessage.Message{
		Header:      dnsmessage.Header{Response: true, Authoritative: true},
		Answers:     answers,
		Additionals: extra,
	}
	if legacy {
		msg.Header.ID = h.ID
		msg.Questions = qs
	}
	out, err := msg.Pack()
	if err != nil {
		return nil, false
	}
	return out, wantUnicast
}

// localIPs returns the machine's LAN IPv4 addresses, skipping loopback,
// link-local and container bridges.
func localIPs() []netip.Addr {
	var out []netip.Addr
	ifaces, _ := net.Interfaces()
	for _, ifi := range ifaces {
		if ifi.Flags&net.FlagUp == 0 || ifi.Flags&net.FlagLoopback != 0 {
			continue
		}
		if n := ifi.Name; n == "docker0" || strings.HasPrefix(n, "br-") || strings.HasPrefix(n, "veth") || strings.HasPrefix(n, "virbr") {
			continue
		}
		addrs, _ := ifi.Addrs()
		for _, addr := range addrs {
			pfx, err := netip.ParsePrefix(addr.String())
			if err != nil {
				continue
			}
			ip := pfx.Addr()
			if ip.Is4() && !ip.IsLoopback() && !ip.IsLinkLocalUnicast() {
				out = append(out, ip)
			}
		}
	}
	return out
}

// HostLabel turns the machine's hostname into a safe .local label.
func HostLabel() string {
	h, _ := os.Hostname()
	h = strings.ToLower(strings.Split(h, ".")[0])
	h = strings.Map(func(r rune) rune {
		if (r >= 'a' && r <= 'z') || (r >= '0' && r <= '9') || r == '-' {
			return r
		}
		return '-'
	}, h)
	h = strings.Trim(h, "-")
	if h == "" {
		return "banditbox"
	}
	if len(h) > 63 {
		h = h[:63]
	}
	return h
}
