<!-- Settings → Backups: the server's saved copies of every profile, made daily and by hand. -->
<script lang="ts">
	import { onMount } from 'svelte';
	import { api, type BackupEntry, type RestoreResult } from '$lib/api';

	let backups = $state<BackupEntry[]>([]);
	let error = $state('');
	let message = $state('');
	let busy = $state(false);

	const kinds: Record<string, string> = {
		auto: 'Daily',
		manual: 'By hand',
		'before-restore': 'Before a restore'
	};

	function kind(name: string) {
		return kinds[name.replace(/^banditbox-/, '').replace(/-\d{8}-\d{6}\.json$/, '')] ?? '';
	}

	async function load() {
		try {
			backups = await api.backups();
		} catch (err) {
			error = (err as Error).message;
		}
	}

	onMount(load);

	async function run(work: () => Promise<string>) {
		busy = true;
		error = message = '';
		try {
			message = await work();
		} catch (err) {
			error = (err as Error).message;
		} finally {
			busy = false;
			await load();
		}
	}

	function restored(r: RestoreResult) {
		return r.changed === 0
			? 'Nothing to restore: the profiles already match that backup.'
			: `Restored. ${r.changed} of ${r.profiles} profiles changed; TVs pick them up within seconds.`;
	}

	const warning =
		'Replace every profile with this backup? Profiles not in it are deleted, on every TV. A backup of how things are now is saved first.';

	function restore(b: BackupEntry) {
		if (confirm(warning)) run(async () => restored(await api.restoreBackup(b.name)));
	}

	async function upload(e: Event) {
		const input = e.currentTarget as HTMLInputElement;
		const file = input.files?.[0];
		input.value = '';
		if (!file || !confirm(warning)) return;
		run(async () => {
			let data: unknown;
			try {
				data = JSON.parse(await file.text());
			} catch {
				throw new Error("That file isn't a BanditBox backup.");
			}
			return restored(await api.restoreFile(data));
		});
	}

	function when(iso: string) {
		return new Date(iso).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' });
	}
</script>

<section class="panel">
	<div class="row spread">
		<h2>Backups</h2>
		<div class="row">
			<label class="button file"
				>Restore from a file…<input
					type="file"
					accept=".json,application/json"
					onchange={upload}
					disabled={busy}
				/></label
			>
			<button
				class="primary"
				disabled={busy}
				onclick={() => run(async () => (await api.createBackup(), 'Backup saved.'))}
				>Back up now</button
			>
		</div>
	</div>
	<p class="muted small">
		The server saves every profile once a day when something changed, and keeps the newest 30.
		Backups made on a TV (Settings → Backup) can be restored here too.
	</p>
	{#if error}<p class="error" role="alert">{error}</p>{/if}
	{#if message}<p role="status">{message}</p>{/if}
	{#if backups.length === 0}
		<p class="muted">No backups yet.</p>
	{:else}
		<table class="stack">
			<thead><tr><th>Made</th><th>Kind</th><th>Profiles</th><th></th></tr></thead>
			<tbody>
				{#each backups as b (b.name)}
					<tr>
						<td>{when(b.created)}</td>
						<td class="muted">{kind(b.name)}</td>
						<td class="muted">{b.profiles}</td>
						<td class="row">
							<a class="button" href={api.backupUrl(b.name)} download={b.name}>Download</a>
							<button disabled={busy} onclick={() => restore(b)}>Restore</button>
						</td>
					</tr>
				{/each}
			</tbody>
		</table>
	{/if}
</section>

<style>
	h2 {
		margin: 0;
	}
	.file {
		margin: 0;
		cursor: pointer;
	}
	.file input {
		position: absolute;
		width: 1px;
		height: 1px;
		opacity: 0;
	}
	.file:focus-within {
		outline: 2px solid var(--accent);
		outline-offset: 2px;
	}
</style>
