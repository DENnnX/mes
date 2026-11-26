<template>
  <section class="runtime-progress lc-dotted" :aria-label="label">
    <div class="runtime-progress-header">
      <strong>{{ progress.completedLabel }} {{ progress.completed }} / {{ progress.total }}</strong>
      <span v-if="progress.next">{{ progress.next }}</span>
    </div>
    <ol :key="progress.steps.map((step) => step.id + ':' + step.state).join('|')" class="runtime-progress-path" :aria-label="label + '工序'">
      <li v-for="(step, index) in progress.steps" :key="step.id" :data-state="step.state" :class="{ 'follows-complete': progress.steps[index - 1]?.state === 'DONE' }">
        <span class="runtime-progress-node" aria-hidden="true"></span>
        <strong>{{ step.name }}</strong>
        <span class="runtime-progress-label">{{ step.label }}</span>
      </li>
    </ol>
  </section>
</template>

<script setup>
defineProps({
  progress: { type: Object, required: true },
  label: { type: String, default: '工序进度' }
});
</script>

<style scoped>
.runtime-progress { border: 1px solid var(--border-light); border-radius: var(--radius-panel); padding: 13px 14px; }
.runtime-progress-header { display: flex; align-items: baseline; justify-content: space-between; flex-wrap: wrap; gap: 4px 10px; font-size: 13px; font-variant-numeric: tabular-nums; }
.runtime-progress-header span { color: var(--text-muted); }
.runtime-progress-path { list-style: none; display: flex; margin: 10px 0 0; padding: 5px 0 2px; overflow-x: auto; scrollbar-width: thin; }
.runtime-progress-path li { position: relative; isolation: isolate; flex: 1 0 88px; display: grid; justify-items: center; align-content: start; gap: 5px; padding: 1px 4px; text-align: center; font-size: 12px; }
.runtime-progress-path li:not(:first-child)::before { content: ""; position: absolute; z-index: -1; top: 10px; right: 50%; width: 100%; height: 2px; background: color-mix(in srgb, var(--primary) 25%, var(--bg-muted)); }
.runtime-progress-path li.follows-complete::before { background: var(--primary); transform-origin: left; animation: runtime-route-in var(--motion-standard) both; }
.runtime-progress-node { width: 19px; height: 19px; border-radius: 50%; border: 2px solid color-mix(in srgb, var(--primary) 32%, var(--bg-muted)); background: var(--bg-muted); }
.runtime-progress-path [data-state="DONE"] .runtime-progress-node { background: var(--primary); border-color: var(--primary); }
.runtime-progress-path [data-state="RUNNING"] .runtime-progress-node { background: var(--el-color-warning-light-9); border-color: var(--el-color-warning); outline: 3px solid var(--el-color-warning-light-8); }
.runtime-progress-label { color: var(--text-muted); }
@keyframes runtime-route-in { from { transform: scaleX(0); } to { transform: scaleX(1); } }
@media (prefers-reduced-motion: reduce) { .runtime-progress-path li.follows-complete::before { animation: none; } }
</style>
