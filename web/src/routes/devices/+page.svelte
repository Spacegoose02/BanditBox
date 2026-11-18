<script lang="ts">
	import { onMount } from 'svelte';
	import { page } from '$app/state';
	import { replaceState } from '$app/navigation';
	import { api, ago, type Device, type PendingPairing, type ProfileSummary } from '$lib/api';
	import ProfilePicker from '$lib/components/ProfilePicker.svelte';

	let devices = $state<Device[]>([]);
	let pending = $state<PendingPairing[]>([]);
	let profiles = $state<ProfileSummary[]>([]);
	let error = $state('');
	let loaded = $state(false);

	// The pairing being approved: from the list, the QR link (?pair=), or a typed code.
	let approving = $state<{ code: string; name: string; profiles: string[] } | null>(null);
	let typedCode = $state('');
	let editing = $state<{ id: string; name: string; profiles: string[] } | null>(null);
	let busy = $state(false);

	async function load() {
		try {
			[devices, pending, profiles] = await Promise.all([
				api.devices(),
				api.pending(),
				api.profiles()
			]);
			error = '';
		} catch (err) {
			error = (err as Error).message;
		}
		loaded = true;
	}

	onMount(() => {
		const code = page.url.searchParams.get('pair');
		if (code) {
			startApprove(code.replace(/\D/g, ''), '');
			replaceState('/devices', {});
		}
		load();
		// TVs waiting to pair show up within a few seconds.
		const t = setInterval(async () => {
			try {
				pending = await api.pending();
			} catch {
				// The next full load reports errors.
			}
		}, 3000);
		return () => clearInterval(t);
	});

	function startApprove(code: string, name: string) {
		const fromList = pending.find((p) => p.code === code);
		approving = { code, name: name || fromList?.name || '', profiles: [] };
	}

	function profileNames(ids: string[]) {
		if (ids.length === 0) return 'All profiles';
		return ids.map((id) => profiles.find((p) => p.id === id)?.name ?? id).join(', ');
	}

	async function approve(e: SubmitEvent) {
		e.preventDefault();
		if (!approving) return;
		busy = true;
		error = '';
		try {
			await api.approve(approving.code, approving.name, approving.profiles);
			approving = null;
			typedCode = '';
			await load();
		} catch (err) {
			error = (err as Error).message;
		} finally {
			busy = false;
		}
	}

	async function deny(code: string) {
		await api.deny(code).catch(() => {});
		if (approving?.code === code) approving = null;
		await load();
	}

	async function saveEdit(e: SubmitEvent) {
		e.preventDefault();
		if (!editing) return;
		busy = true;
		error = '';
		try {
			await api.updateDevice(editing.id, { name: editing.name, profiles: editing.profiles });
			editing = null;
			await load();
		} catch (err) {
			error = (err as Error).message;
		} finally {
			busy = false;
		}
	}

	async function revoke(d: Device) {
		if (
			!confirm(
				`Unpair ${d.name}? It keeps its current layout but stops syncing until you pair it again.`
			)
		)
			return;
		await api.revokeDevice(d.id).catch((err) => (error = err.message));
		await load();
	}
</script>

<h1>Devices</h1>
{#if error}<p class="error" role="alert">{error}</p>{/if}

{#if approving}
	<form class="panel approve" onsubmit={approve}>
		<h2>Pair the TV showing <span class="code">{approving.code}</span></h2>
		<label><span>Name</span><input bind:value={approving.name} placeholder="Living room" /></label>
		{#if profiles.length}
			<ProfilePicker {profiles} bind:selected={approving.profiles} />
		{/if}
		<div class="row">
			<button class="primary" disabled={busy}>Pair</button>
			<button type="button" onclick={() => (approving = null)}>Cancel</button>
		</div>
	</form>
{/if}

<section class="panel">
	<h2>Pair a TV</h2>
	<p class="muted">
		On the TV, open BanditBox <b>Settings → Sign in to sync</b>. It shows a 6-digit code, which
		appears below. You can also scan the TV's QR code with your phone.
	</p>
	{#if pending.length}
		<table class="stack">
			<thead><tr><th>Code</th><th>TV</th><th>Expires</th><th></th></tr></thead>
			<tbody>
				{#each pending as p (p.code)}
					<tr>
						<td class="code">{p.code}</td>
						<td
							>{p.name}{#if p.model}<span class="muted small">&nbsp;· {p.model}</span>{/if}</td
						>
						<td class="muted small">{new Date(p.expires).toLocaleTimeString()}</td>
						<td class="row">
							<button class="primary" onclick={() => startApprove(p.code, p.name)}>Pair</button>
							<button onclick={() => deny(p.code)}>Refuse</button>
						</td>
					</tr>
				{/each}
			</tbody>
		</table>
	{:else}
		<form
			class="row"
			onsubmit={(e) => {
				e.preventDefault();
				startApprove(typedCode.replace(/\D/g, ''), '');
			}}
		>
			<input
				class="code typed"
				inputmode="numeric"
				placeholder="123456"
				aria-label="Code shown on the TV"
				bind:value={typedCode}
			/>
			<button disabled={typedCode.replace(/\D/g, '').length !== 6}>Enter code</button>
		</form>
	{/if}
</section>

<section class="panel">
	<h2>Paired TVs</h2>
	{#if loaded && devices.length === 0}
		<p class="muted">No TVs yet.</p>
	{:else}
		<table class="stack">
			<thead><tr><th>Name</th><th>Profiles</th><th>Last seen</th><th>App</th><th></th></tr></thead>
			<tbody>
				{#each devices as d (d.id)}
					{#if editing?.id === d.id}
						<tr>
							<td colspan="5">
								<form onsubmit={saveEdit}>
									<label><span>Name</span><input bind:value={editing.name} required /></label>
									<ProfilePicker {profiles} bind:selected={editing.profiles} />
									<div class="row">
										<button class="primary" disabled={busy}>Save</button>
										<button type="button" onclick={() => (editing = null)}>Cancel</button>
									</div>
								</form>
							</td>
						</tr>
					{:else}
						<tr>
							<td
								>{d.name}{#if d.model}<div class="muted small">{d.model}</div>{/if}</td
							>
							<td>{profileNames(d.profiles)}</td>
							<td class="muted">{ago(d.lastSeen)}</td>
							<td class="muted">{d.appVersion || '–'}</td>
							<td class="row">
								<button
									onclick={() => (editing = { id: d.id, name: d.name, profiles: [...d.profiles] })}
									>Edit</button
								>
								<button class="danger" onclick={() => revoke(d)}>Unpair</button>
							</td>
						</tr>
					{/if}
				{/each}
			</tbody>
		</table>
	{/if}
</section>

<style>
	.approve {
		border-color: var(--accent);
	}
	.typed {
		max-width: 10rem;
	}
</style>
