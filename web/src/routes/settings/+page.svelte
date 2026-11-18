<script lang="ts">
	import { api } from '$lib/api';
	import { session } from '$lib/session.svelte';

	let current = $state('');
	let next = $state('');
	let confirmNext = $state('');
	let message = $state('');
	let error = $state('');
	let busy = $state(false);

	async function change(e: SubmitEvent) {
		e.preventDefault();
		message = error = '';
		if (next !== confirmNext) {
			error = "The new passwords don't match.";
			return;
		}
		busy = true;
		try {
			await api.changePassword(current, next);
			current = next = confirmNext = '';
			message = 'Password changed. Other browsers have been signed out.';
		} catch (err) {
			error = (err as Error).message;
		} finally {
			busy = false;
		}
	}
</script>

<h1>Settings</h1>

<form class="panel" onsubmit={change}>
	<h2>Change password</h2>
	<p class="muted small">Signed in as {session.admin?.username}.</p>
	<input type="text" autocomplete="username" value={session.admin?.username} hidden readonly />
	<label
		><span>Current password</span><input
			type="password"
			autocomplete="current-password"
			bind:value={current}
			required
		/></label
	>
	<label
		><span>New password (10 characters or more)</span><input
			type="password"
			autocomplete="new-password"
			minlength="10"
			bind:value={next}
			required
		/></label
	>
	<label
		><span>New password again</span><input
			type="password"
			autocomplete="new-password"
			bind:value={confirmNext}
			required
		/></label
	>
	{#if error}<p class="error" role="alert">{error}</p>{/if}
	{#if message}<p role="status">{message}</p>{/if}
	<button class="primary" disabled={busy}>Change password</button>
</form>
