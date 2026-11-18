<script lang="ts">
	import '../app.css';
	import { onMount } from 'svelte';
	import { goto } from '$app/navigation';
	import { page } from '$app/state';
	import { api, handleSignedOut } from '$lib/api';
	import { session } from '$lib/session.svelte';

	let { children } = $props();

	const publicPages = ['/login', '/setup'];

	async function refresh() {
		try {
			const st = await api.status();
			session.admin = st.admin;
			session.setupNeeded = st.setupNeeded;
		} catch {
			session.admin = null;
		}
		session.checked = true;
	}

	// Send people to setup or sign-in when needed, keeping where they were going.
	$effect(() => {
		if (!session.checked) return;
		const path = page.url.pathname;
		if (session.setupNeeded && path !== '/setup') {
			goto('/setup', { replaceState: true });
		} else if (!session.setupNeeded && !session.admin && !publicPages.includes(path)) {
			const next = path + page.url.search;
			goto('/login?next=' + encodeURIComponent(next), { replaceState: true });
		}
	});

	onMount(() => {
		handleSignedOut(() => {
			session.admin = null;
		});
		refresh();
	});

	async function signOut() {
		await api.logout();
		session.admin = null;
	}

	const nav = [
		{ href: '/devices', label: 'Devices' },
		{ href: '/profiles', label: 'Profiles' },
		{ href: '/apps', label: 'Apps' },
		{ href: '/settings', label: 'Settings' }
	];
</script>

{#if session.admin}
	<header>
		<a class="brand" href="/devices">
			<img src="/favicon.png" alt="" width="28" height="28" />
			BanditBox
		</a>
		<nav>
			{#each nav as item (item.href)}
				<a
					href={item.href}
					aria-current={page.url.pathname.startsWith(item.href) ? 'page' : undefined}
					>{item.label}</a
				>
			{/each}
		</nav>
		<button class="signout" onclick={signOut}>Sign out</button>
	</header>
{/if}

<main class:narrow={!session.admin}>
	{#if session.checked}
		{@render children()}
	{/if}
</main>

<style>
	header {
		display: flex;
		align-items: center;
		gap: 1.5rem;
		padding: 0.75rem 1.25rem;
		border-bottom: 1px solid var(--line);
		background: var(--panel);
		flex-wrap: wrap;
	}
	.brand {
		display: flex;
		align-items: center;
		gap: 0.5rem;
		color: var(--text);
		text-decoration: none;
		font-weight: 700;
	}
	nav {
		display: flex;
		gap: 0.25rem;
		flex: 1;
	}
	nav a {
		color: var(--muted);
		text-decoration: none;
		padding: 0.35rem 0.75rem;
		border-radius: 8px;
	}
	nav a[aria-current='page'] {
		color: var(--text);
		background: var(--panel-2);
	}
	.signout {
		padding: 0.35rem 0.75rem;
	}
	main {
		max-width: 960px;
		margin: 0 auto;
		padding: 1.5rem 1rem 3rem;
	}
	main.narrow {
		max-width: 420px;
		padding-top: 12vh;
	}
</style>
