<script lang="ts">
	import { onMount } from 'svelte';
	import { api, ago, type HomeAssistantConfig, type HomeAssistantView } from '$lib/api';

	let view = $state<HomeAssistantView | null>(null);
	let form = $state<HomeAssistantConfig>({
		broker: '',
		username: '',
		password: '',
		prefix: 'banditbox',
		discovery: 'homeassistant',
		webhook: ''
	});
	let editing = $state(false);
	let busy = $state(false);
	let error = $state('');
	let message = $state('');

	async function load() {
		try {
			view = await api.homeAssistant();
			if (!editing) {
				form = { ...view, password: '' };
			}
		} catch (err) {
			error = (err as Error).message;
		}
	}

	onMount(() => {
		load();
		const t = setInterval(load, 15000);
		return () => clearInterval(t);
	});

	function body() {
		const b = $state.snapshot(form);
		if (!b.password) delete b.password;
		return b;
	}

	async function test() {
		busy = true;
		error = '';
		message = '';
		try {
			await api.testHomeAssistant(body());
			message = form.broker
				? `It works: a test message went to ${form.prefix}/test.`
				: 'It works: the webhook got a test event.';
		} catch (err) {
			error = (err as Error).message;
		} finally {
			busy = false;
		}
	}

	async function save(e: SubmitEvent) {
		e.preventDefault();
		busy = true;
		error = '';
		message = '';
		try {
			view = await api.saveHomeAssistant(body());
			editing = false;
			form = { ...view, password: '' };
			message = 'Saved.';
			setTimeout(load, 2000);
		} catch (err) {
			error = (err as Error).message;
		} finally {
			busy = false;
		}
	}

	function describe(s: HomeAssistantView['status']['tvs'][number]['state']) {
		if (!s.screenOn) return 'Screen off';
		const app = s.appLabel || s.app || 'Home screen';
		return s.playing ? `Playing in ${app}` : app;
	}
</script>

<section class="panel">
	<h2>Home Assistant</h2>
	<p class="muted small">
		Each TV tells this server which app is in front, whether something is playing and whether the
		screen is on. Over MQTT, every TV shows up in Home Assistant as a device with those sensors
		(MQTT discovery), ready for automations such as dimming the lights when a movie starts. A
		webhook gets a POST for each change instead, or as well.
	</p>
	{#if view}
		<p class="small">
			{#if view.broker}
				{#if view.status.connected}
					<span>Connected to {view.broker}</span>
				{:else if view.status.error}
					<span class="error">Can't reach {view.broker}: {view.status.error}</span>
				{:else}
					<span class="muted">Connecting to {view.broker}…</span>
				{/if}
			{:else}
				<span class="muted">No MQTT broker set.</span>
			{/if}
			{#if view.webhook}<span class="muted"> · Webhook on</span>{/if}
		</p>
		{#if view.status.tvs.length}
			<ul class="small">
				{#each view.status.tvs as tv (tv.device.id)}
					<li>
						<strong>{tv.device.name}</strong>: {describe(tv.state)}
						<span class="muted">({ago(tv.updated)})</span>
					</li>
				{/each}
			</ul>
		{:else}
			<p class="muted small">No TV has reported yet. TVs report once they're paired and on.</p>
		{/if}
	{/if}

	{#if editing}
		<form onsubmit={save}>
			<label>
				<span>MQTT broker (optional)</span>
				<input bind:value={form.broker} placeholder="mqtt://192.168.1.10:1883" />
				<small class="muted">Home Assistant's Mosquitto add-on listens on port 1883.</small>
			</label>
			<label
				><span>Username (optional)</span><input
					bind:value={form.username}
					autocomplete="off"
				/></label
			>
			<label>
				<span>Password (optional)</span>
				<input
					type="password"
					autocomplete="off"
					bind:value={form.password}
					placeholder={view?.hasPassword ? 'Saved. Leave empty to keep it' : ''}
				/>
			</label>
			<label><span>Topic prefix</span><input bind:value={form.prefix} /></label>
			<label>
				<span>Discovery prefix</span>
				<input bind:value={form.discovery} />
				<small class="muted">Leave as homeassistant unless you changed it in Home Assistant.</small>
			</label>
			<label>
				<span>Webhook (optional)</span>
				<input
					bind:value={form.webhook}
					placeholder="http://homeassistant.local:8123/api/webhook/banditbox"
				/>
				<small class="muted">
					Gets {'{'}"event", "tv", "state"{'}'} for app_changed, playback_started, playback_stopped,
					screen_on and screen_off.
				</small>
			</label>
			{#if error}<p class="error" role="alert">{error}</p>{/if}
			{#if message}<p role="status">{message}</p>{/if}
			<div class="row">
				<button type="button" onclick={test} disabled={busy}>{busy ? 'Trying…' : 'Test'}</button>
				<button class="primary" disabled={busy}>Save</button>
				<button type="button" onclick={() => ((editing = false), load())}>Cancel</button>
			</div>
		</form>
	{:else}
		{#if error}<p class="error" role="alert">{error}</p>{/if}
		{#if message}<p role="status">{message}</p>{/if}
		<button onclick={() => ((editing = true), (message = ''))}>
			{view?.broker || view?.webhook ? 'Edit' : 'Set up'}
		</button>
	{/if}
</section>
