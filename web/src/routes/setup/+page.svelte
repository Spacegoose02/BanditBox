<script lang="ts">
	import { goto } from '$app/navigation';
	import { api } from '$lib/api';
	import { session } from '$lib/session.svelte';

	let code = $state('');
	let username = $state('admin');
	let password = $state('');
	let confirm = $state('');
	let error = $state('');
	let busy = $state(false);

	$effect(() => {
		if (session.checked && !session.setupNeeded) goto('/devices', { replaceState: true });
	});

	async function submit(e: SubmitEvent) {
		e.preventDefault();
		error = '';
		if (password !== confirm) {
			error = "The passwords don't match.";
			return;
		}
		busy = true;
		try {
			const st = await api.setup(code, username, password);
			session.admin = st.admin;
			session.setupNeeded = false;
		} catch (err) {
			error = (err as Error).message;
		} finally {
			busy = false;
		}
	}
</script>

<div class="panel">
	<h1>Set up BanditBox</h1>
	<p class="muted">
		Create the admin account. The setup code is in the server's log: on Unraid, click the BanditBox
		icon and choose <b>Logs</b>, or run <span class="code">docker logs banditbox</span>.
	</p>
	<form onsubmit={submit}>
		<label
			><span>Setup code</span><input
				class="code"
				placeholder="ABCD-1234"
				autocomplete="off"
				bind:value={code}
				required
			/></label
		>
		<label
			><span>Username</span><input autocomplete="username" bind:value={username} required /></label
		>
		<label
			><span>Password (10 characters or more)</span><input
				type="password"
				autocomplete="new-password"
				minlength="10"
				bind:value={password}
				required
			/></label
		>
		<label
			><span>Password again</span><input
				type="password"
				autocomplete="new-password"
				bind:value={confirm}
				required
			/></label
		>
		{#if error}<p class="error" role="alert">{error}</p>{/if}
		<button class="primary" disabled={busy}>Create account</button>
	</form>
</div>
