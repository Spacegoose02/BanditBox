// Package auth handles web UI sign-in: the local admin's password, session
// cookies, first-run setup and a limit on failed sign-ins.
package auth

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"errors"
	"fmt"
	"net/http"
	"strings"
	"sync"
	"time"

	"golang.org/x/crypto/bcrypt"

	"github.com/spacegoose02/banditbox/server/internal/store"
)

const (
	// CookieName holds the session token.
	CookieName = "bb_session"
	// SessionTTL is how long a session lasts without use.
	SessionTTL = 30 * 24 * time.Hour
	// MinPasswordLen is the shortest password accepted.
	MinPasswordLen = 10
)

// bcryptCost is a variable so tests can make hashing fast.
var bcryptCost = 12

var (
	// ErrBadCredentials means the username or password is wrong.
	ErrBadCredentials = errors.New("wrong username or password")
	// ErrTooManyAttempts means sign-in is paused for this client.
	ErrTooManyAttempts = errors.New("too many failed sign-ins, try again later")
	// ErrBadSetupCode means the first-run setup code is wrong.
	ErrBadSetupCode = errors.New("wrong setup code")
	// ErrSetupDone means an admin already exists.
	ErrSetupDone = errors.New("setup is already done")
)

// Auth signs admins in and out.
type Auth struct {
	store     *store.Store
	limiter   *limiter
	mu        sync.Mutex
	setupCode string
}

// New returns an Auth backed by st.
func New(st *store.Store) *Auth {
	return &Auth{store: st, limiter: newLimiter(5, 15*time.Minute)}
}

// SetupCode returns the one-time code needed to create the first admin, making
// one if needed. It returns "" once an admin exists. The server prints it to its
// log, so only someone who can read the container's log can claim the server.
func (a *Auth) SetupCode(ctx context.Context) (string, error) {
	n, err := a.store.AdminCount(ctx)
	if err != nil || n > 0 {
		return "", err
	}
	a.mu.Lock()
	defer a.mu.Unlock()
	if a.setupCode == "" {
		a.setupCode = randomCode()
	}
	return a.setupCode, nil
}

// SetupNeeded reports whether no admin exists yet.
func (a *Auth) SetupNeeded(ctx context.Context) (bool, error) {
	n, err := a.store.AdminCount(ctx)
	return n == 0, err
}

// Setup creates the first admin and returns a session token for them.
func (a *Auth) Setup(ctx context.Context, client, code, username, password string) (string, store.Admin, error) {
	if !a.limiter.allow(client) {
		return "", store.Admin{}, ErrTooManyAttempts
	}
	a.mu.Lock()
	want := a.setupCode
	a.mu.Unlock()
	if want == "" || subtle.ConstantTimeCompare([]byte(normalizeCode(code)), []byte(want)) != 1 {
		if need, _ := a.SetupNeeded(ctx); !need {
			return "", store.Admin{}, ErrSetupDone
		}
		a.limiter.fail(client)
		return "", store.Admin{}, ErrBadSetupCode
	}
	if err := checkCredentials(username, password); err != nil {
		return "", store.Admin{}, err
	}
	hash, err := hashPassword(password)
	if err != nil {
		return "", store.Admin{}, err
	}
	admin, err := a.store.CreateFirstAdmin(ctx, strings.TrimSpace(username), hash)
	if errors.Is(err, store.ErrExists) {
		return "", store.Admin{}, ErrSetupDone
	}
	if err != nil {
		return "", store.Admin{}, err
	}
	a.mu.Lock()
	a.setupCode = ""
	a.mu.Unlock()
	token, err := a.newSession(ctx, admin.ID)
	return token, admin, err
}

// Login checks a username and password and returns a new session token.
func (a *Auth) Login(ctx context.Context, client, username, password string) (string, store.Admin, error) {
	if !a.limiter.allow(client) {
		return "", store.Admin{}, ErrTooManyAttempts
	}
	admin, err := a.store.AdminByUsername(ctx, strings.TrimSpace(username))
	if errors.Is(err, store.ErrNotFound) {
		// Spend the same time as a real check so usernames can't be probed by timing.
		_ = bcrypt.CompareHashAndPassword(dummyHash(), []byte(password))
		a.limiter.fail(client)
		return "", store.Admin{}, ErrBadCredentials
	}
	if err != nil {
		return "", store.Admin{}, err
	}
	if bcrypt.CompareHashAndPassword([]byte(admin.PwHash), []byte(password)) != nil {
		a.limiter.fail(client)
		return "", store.Admin{}, ErrBadCredentials
	}
	a.limiter.reset(client)
	token, err := a.newSession(ctx, admin.ID)
	return token, admin, err
}

// ChangePassword checks the current password, sets a new one and signs out every
// session for that admin. It returns a fresh session token.
func (a *Auth) ChangePassword(ctx context.Context, admin store.Admin, current, next string) (string, error) {
	if bcrypt.CompareHashAndPassword([]byte(admin.PwHash), []byte(current)) != nil {
		return "", ErrBadCredentials
	}
	if err := checkCredentials(admin.Username, next); err != nil {
		return "", err
	}
	hash, err := hashPassword(next)
	if err != nil {
		return "", err
	}
	if err := a.store.SetAdminPassword(ctx, admin.ID, hash); err != nil {
		return "", err
	}
	return a.newSession(ctx, admin.ID)
}

// Session returns the admin a session token belongs to.
func (a *Auth) Session(ctx context.Context, token string) (store.Admin, error) {
	if token == "" {
		return store.Admin{}, store.ErrNotFound
	}
	return a.store.SessionAdmin(ctx, hashToken(token), time.Now().Add(SessionTTL))
}

// Logout ends a session.
func (a *Auth) Logout(ctx context.Context, token string) error {
	return a.store.DeleteSession(ctx, hashToken(token))
}

func (a *Auth) newSession(ctx context.Context, adminID int64) (string, error) {
	token := randomToken()
	if err := a.store.CreateSession(ctx, hashToken(token), adminID, time.Now().Add(SessionTTL)); err != nil {
		return "", err
	}
	return token, nil
}

// ValidationError is a problem with what the user typed.
type ValidationError string

func (e ValidationError) Error() string { return string(e) }

func checkCredentials(username, password string) error {
	u := strings.TrimSpace(username)
	if u == "" || len(u) > 64 {
		return ValidationError("username must be 1 to 64 characters")
	}
	if len(password) < MinPasswordLen {
		return ValidationError(fmt.Sprintf("password must be at least %d characters", MinPasswordLen))
	}
	if len(password) > 72 {
		return ValidationError("password must be at most 72 characters")
	}
	return nil
}

func hashPassword(pw string) (string, error) {
	h, err := bcrypt.GenerateFromPassword([]byte(pw), bcryptCost)
	return string(h), err
}

var (
	dummyOnce sync.Once
	dummy     []byte
)

func dummyHash() []byte {
	dummyOnce.Do(func() { dummy, _ = bcrypt.GenerateFromPassword([]byte("banditbox-dummy"), bcryptCost) })
	return dummy
}

func hashToken(token string) []byte {
	h := sha256.Sum256([]byte(token))
	return h[:]
}

func randomToken() string {
	b := make([]byte, 32)
	if _, err := rand.Read(b); err != nil {
		panic(err)
	}
	return base64.RawURLEncoding.EncodeToString(b)
}

// randomCode makes a setup code like "K7QF-2M9X" from an alphabet without look-alikes.
func randomCode() string {
	const alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
	b := make([]byte, 8)
	if _, err := rand.Read(b); err != nil {
		panic(err)
	}
	out := make([]byte, 0, 9)
	for i, c := range b {
		if i == 4 {
			out = append(out, '-')
		}
		out = append(out, alphabet[int(c)%len(alphabet)])
	}
	return string(out)
}

func normalizeCode(s string) string {
	s = strings.ToUpper(strings.TrimSpace(s))
	s = strings.ReplaceAll(s, " ", "")
	if len(s) == 8 && !strings.Contains(s, "-") {
		s = s[:4] + "-" + s[4:]
	}
	return s
}

// SetCookie sets the session cookie on w. secure should be true when the browser
// reached the server over HTTPS (directly or through a reverse proxy).
func SetCookie(w http.ResponseWriter, token string, secure bool) {
	http.SetCookie(w, &http.Cookie{
		Name:     CookieName,
		Value:    token,
		Path:     "/",
		MaxAge:   int(SessionTTL / time.Second),
		HttpOnly: true,
		Secure:   secure,
		SameSite: http.SameSiteStrictMode,
	})
}

// ClearCookie removes the session cookie.
func ClearCookie(w http.ResponseWriter, secure bool) {
	http.SetCookie(w, &http.Cookie{
		Name: CookieName, Value: "", Path: "/", MaxAge: -1,
		HttpOnly: true, Secure: secure, SameSite: http.SameSiteStrictMode,
	})
}

// FastHashingForTests lowers the bcrypt cost so tests run quickly. Never call it
// outside tests.
func FastHashingForTests() { bcryptCost = bcrypt.MinCost }
