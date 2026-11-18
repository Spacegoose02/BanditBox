<script lang="ts">
	import type { WidgetFeed } from '$lib/api';

	let { feed }: { feed: WidgetFeed } = $props();

	function team(t: { name: string; abbr?: string; score?: string | null }) {
		return `${t.abbr || t.name}${t.score != null ? ' ' + t.score : ''}`;
	}
</script>

<div class="feed">
	<p class="small">
		<span class="code">{feed.id}</span>
		<span class="muted">· {feed.cards.length} card{feed.cards.length === 1 ? '' : 's'}</span>
		{#if !feed.kidsSafe}<span class="muted">· hidden on kids profiles</span>{/if}
	</p>
	{#if feed.error}<p class="error small">Last fetch failed: {feed.error}</p>{/if}
	<ul>
		{#each feed.cards.slice(0, 6) as card (card.id)}
			<li>
				{#if card.weather}
					<strong>{Math.round(card.weather.temp)}{card.weather.unit}</strong>
					{card.weather.label ?? ''}
					{#if card.weather.high != null && card.weather.low != null}
						<span class="muted"
							>· {Math.round(card.weather.high)}° / {Math.round(card.weather.low)}°</span
						>
					{/if}
					<span class="muted">· {card.title}</span>
				{:else if card.score}
					{#if card.badge}<span class="badge">{card.badge}</span>{/if}
					{team(card.score.away)} at {team(card.score.home)}
					<span class="muted">· {card.score.detail ?? card.score.state}</span>
				{:else}
					{#if card.badge}<span class="badge">{card.badge}</span>{/if}
					{card.title}{#if card.subtitle}<span class="muted"> · {card.subtitle}</span>{/if}
					{#if card.stat}<strong> {card.stat.value}{card.stat.unit ?? ''}</strong>{/if}
				{/if}
			</li>
		{:else}
			<li class="muted">No cards right now.</li>
		{/each}
		{#if feed.cards.length > 6}<li class="muted">and {feed.cards.length - 6} more</li>{/if}
	</ul>
</div>

<style>
	.feed {
		margin-top: 0.5rem;
	}
	.feed p {
		margin: 0;
	}
	ul {
		margin: 0.25rem 0 0;
		padding-left: 1.1rem;
	}
	.badge {
		font-size: 0.7rem;
		font-weight: 700;
		background: var(--danger);
		color: #fff;
		border-radius: 4px;
		padding: 0 0.3rem;
		margin-right: 0.3rem;
	}
</style>
