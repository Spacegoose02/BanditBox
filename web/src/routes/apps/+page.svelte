<script lang="ts">
	import { onMount } from 'svelte';
	import { api, ago, type Apk, type Device, type Install } from '$lib/api';

	let apks = $state<Apk[]>([]);
	let devices = $state<Device[]>([]);
	let error = $state('');
	let message = $state('');
	let busy = $state(false);
	/** The APK whose "Install on…" chooser is open, and the TVs ticked in it. */
	let choosing = $state<{ sha: string; devices: string[] } | null>(null);

	const SELF = 'com.spacegoose.banditbox';

	async function load() {
		try {
			[apks, devices] = await Promise.all([api.apks(), api.devices()]);
			error = '';
		} catch (err) {
			error = (err as Error).message;
		}
	}

	onMount(() => {
		load();
		// Install results come in as TVs work through them.
		const t = setInterval(() => {
			if (
				apks.some((a) => a.installs.some((i) => i.status === 'pending' || i.status === 'waiting'))
			)
				load();
		}, 4000);
		return () => clearInterval(t);
	});

	async function upload(e: Event) {
		const input = e.currentTarget as HTMLInputElement;
		const file = input.files?.[0];
		input.value = '';
		if (!file) return;
		busy = true;
		error = message = '';
		try {
			const a = await api.uploadApk(file);
			message = `Uploaded ${a.package} ${a.versionName}.`;
			await load();
		} catch (err) {
			error = (err as Error).message;
		} finally {
			busy = false;
		}
	}

	async function install(a: Apk, ids: string[]) {
		busy = true;
		error = message = '';
		try {
			await api.installApk(a.sha256, ids);
			choosing = null;
			message = `Sent ${a.versionName || a.package} to ${ids.length === 0 ? 'every TV' : ids.length + ' TV' + (ids.length === 1 ? '' : 's')}.`;
			await load();
		} catch (err) {
			error = (err as Error).message;
		} finally {
			busy = false;
		}
	}

	async function remove(a: Apk) {
		if (!confirm(`Delete ${a.fileName || a.package} from the server?`)) return;
		await api.deleteApk(a.sha256).catch((err) => (error = err.message));
		await load();
	}

	function deviceName(id: string) {
		return devices.find((d) => d.id === id)?.name ?? 'Removed TV';
	}

	const statusText: Record<Install['status'], string> = {
		pending: 'Waiting for the TV',
		waiting: 'Confirm on the TV',
		installed: 'Installed',
		skipped: 'Already up to date',
		failed: 'Failed'
	};

	function size(bytes: number) {
		return bytes >= 1 << 20
			? (bytes / (1 << 20)).toFixed(1) + ' MB'
			: Math.ceil(bytes / 1024) + ' KB';
	}
</script>

<h1>Apps</h1>
{#if error}<p class="error" role="alert">{error}</p>{/if}
{#if message}<p role="status">{message}</p>{/if}

<section class="panel">
	<div class="row spread">
		<p class="muted small grow">
			Upload an APK to install it on your TVs. A BanditBox APK updates the launcher itself. Each TV
			needs "Install unknown apps" allowed for BanditBox, and may ask to confirm on screen.
		</p>
		<label class="button primary-button"
			>{busy ? 'Working…' : 'Upload an APK…'}<input
				type="file"
				accept=".apk,application/vnd.android.package-archive"
				onchange={upload}
				disabled={busy}
			/></label
		>
	</div>
</section>

{#each apks as a (a.sha256)}
	<section class="panel">
		<div class="row spread">
			<div>
				<h2>
					{a.package === SELF ? 'BanditBox' : a.fileName || a.package}
					<span class="muted">{a.versionName}</span>
				</h2>
				<p class="muted small">
					{a.package} · version code {a.versionCode} · {size(a.size)} · uploaded {ago(a.uploaded)}
				</p>
			</div>
			<div class="row">
				<button
					class="primary"
					disabled={busy || devices.length === 0}
					onclick={() => install(a, [])}>Install on every TV</button
				>
				<button
					disabled={busy || devices.length === 0}
					onclick={() => (choosing = { sha: a.sha256, devices: [] })}>Choose TVs…</button
				>
				<button class="danger" onclick={() => remove(a)}>Delete</button>
			</div>
		</div>
		{#if choosing?.sha === a.sha256}
			<div class="checks">
				{#each devices as d (d.id)}
					<label
						><input type="checkbox" value={d.id} bind:group={choosing.devices} />{d.name}
						<span class="muted small">{d.model}</span></label
					>
				{/each}
				<div class="row">
					<button
						class="primary"
						disabled={busy || choosing.devices.length === 0}
						onclick={() => choosing && install(a, choosing.devices)}>Install</button
					>
					<button onclick={() => (choosing = null)}>Cancel</button>
				</div>
			</div>
		{/if}
		{#if a.installs.length}
			<table>
				<tbody>
					{#each a.installs as i (i.deviceId)}
						<tr>
							<td>{deviceName(i.deviceId)}</td>
							<td class:error={i.status === 'failed'}>
								{statusText[i.status] ?? i.status}{i.detail ? `: ${i.detail}` : ''}
							</td>
							<td class="muted small">{ago(i.updated)}</td>
						</tr>
					{/each}
				</tbody>
			</table>
		{/if}
	</section>
{:else}
	<p class="muted">No APKs uploaded yet.</p>
{/each}

<style>
	h2 {
		margin: 0;
	}
	h2 .muted {
		font-weight: 400;
	}
	.grow {
		flex: 1 1 20rem;
		margin: 0;
	}
	.checks {
		margin-top: 1rem;
	}
	.primary-button {
		margin: 0;
		cursor: pointer;
		background: var(--accent);
		color: var(--accent-ink);
		border-color: var(--accent);
		font-weight: 600;
	}
	.primary-button input {
		position: absolute;
		width: 1px;
		height: 1px;
		opacity: 0;
	}
	.primary-button:focus-within {
		outline: 2px solid var(--accent);
		outline-offset: 2px;
	}
	table {
		margin-top: 0.75rem;
	}
</style>
