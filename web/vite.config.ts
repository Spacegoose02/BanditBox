import { sveltekit } from '@sveltejs/kit/vite';
import { defineConfig } from 'vite';

export default defineConfig({
	plugins: [sveltekit()],
	server: {
		// `npm run dev` talks to a server started with `go run ./cmd/banditbox`.
		proxy: { '/api': { target: 'http://localhost:8686', ws: true } }
	}
});
