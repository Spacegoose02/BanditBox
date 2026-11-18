package hub

import "testing"

func TestCoalescesToLatest(t *testing.T) {
	h := New()
	s := h.Subscribe("d1")
	h.Publish("p", 1)
	h.Publish("p", 3)
	h.Publish("p", 2)
	h.Publish("q", 7)
	<-s.Ready
	v, profiles, kicked := s.Take()
	if kicked || profiles || v["p"] != 3 || v["q"] != 7 || len(v) != 2 {
		t.Fatalf("got %v %v", v, kicked)
	}
	h.ProfilesChanged()
	<-s.Ready
	if v, profiles, _ := s.Take(); len(v) != 0 || !profiles {
		t.Fatalf("second take %v", v)
	}
	h.Unsubscribe(s)
	if h.Count() != 0 {
		t.Fatal("not removed")
	}
}

func TestKick(t *testing.T) {
	h := New()
	a, b := h.Subscribe("d1"), h.Subscribe("d2")
	h.Kick("d1")
	<-a.Ready
	if _, _, kicked := a.Take(); !kicked {
		t.Fatal("d1 not kicked")
	}
	select {
	case <-b.Ready:
		t.Fatal("d2 signalled")
	default:
	}
}
