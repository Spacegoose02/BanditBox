# BanditBox web UI

SvelteKit (Svelte 5) built as a static single-page app with `adapter-static`. The Go server embeds the build and serves it, so there is no Node at runtime.

```sh
npm ci
npm run dev        # http://localhost:5173, with /api proxied to a server on :8686
npm run check      # types and Svelte warnings
npm run lint       # Prettier
npm run build      # writes build/
```

To run the server with the UI outside Docker, copy the build into the server before compiling:

```sh
npm run build && cp -r build/* ../server/internal/webui/dist/
cd ../server && go run ./cmd/banditbox
```

The Docker image does this itself. The API it talks to is in [docs/api.md](../docs/api.md).
