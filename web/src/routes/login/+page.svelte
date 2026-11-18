<script lang="ts">
	import { goto } from '$app/navigation';
	import { page } from '$app/state';
	import { api } from '$lib/api';
	import { session } from '$lib/session.svelte';

	let username = $state('');
	let password = $state('');
	let error = $state('');
	let busy = $state(false);

	function next() {
		const n = page.url.searchParams.get('next') ?? '';
		// Only local paths, never another site.
		return n.startsWith('/') && !n.startsWith('//') ? n : '/devices';
	}

	$effect(() => {
		if (session.admin) goto(next(), { replaceState: true });
	});

	async function submit(e: SubmitEvent) {
		e.preventDefault();
		busy = true;
		error = '';
		try {
			const st = await api.login(username, password);
			session.admin = st.admin;
		} catch (err) {
			error = (err as Error).message;
		} finally {
			busy = false;
		}
	}
</script>

<div class="panel">
	<h1>Sign in to BanditBox</h1>
	<form onsubmit={submit}>
		<label
			><span>Username</span><input autocomplete="username" bind:value={username} required /></label
		>
		<label
			><span>Password</span><input
				type="password"
				autocomplete="current-password"
				bind:value={password}
				required
			/></label
		>
		{#if error}<p class="error" role="alert">{error}</p>{/if}
		<button class="primary" disabled={busy}>Sign in</button>
	</form>
</div>
