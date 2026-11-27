<template>
  <section
    v-if="open"
    id="mes-assistant-panel"
    class="mes-assistant"
    aria-labelledby="mes-assistant-heading"
    @keydown.esc.stop.prevent="$emit('close')"
  >
    <header class="assistant-header">
      <el-icon class="assistant-icon" aria-hidden="true"><ChatDotRound /></el-icon>
      <h2 id="mes-assistant-heading">MES 助手</h2>
      <el-button text circle aria-label="关闭 MES 助手" @click="$emit('close')">
        <el-icon><Close /></el-icon>
      </el-button>
    </header>

    <div ref="transcript" class="assistant-transcript" role="log" aria-label="MES 问答记录">
      <div v-if="!messages.length" class="assistant-welcome">
        <h3>想了解哪个功能？</h3>
        <button v-for="example in examples" :key="example" type="button" class="assistant-example" @click="useExample(example)">
          {{ example }}
          <el-icon aria-hidden="true"><ArrowRight /></el-icon>
        </button>
      </div>
      <article v-for="(message, index) in messages" :key="index" class="assistant-turn" :class="'assistant-turn--' + message.role">
        <div v-if="message.role === 'user'" class="assistant-question">{{ message.text }}</div>
        <template v-else>
          <p v-if="message.text" class="assistant-message">{{ message.text }}</p>
          <div v-for="section in message.sections" :key="section.title" class="assistant-answer">
            <h3>{{ section.title }}</h3>
            <p>{{ section.summary }}</p>
            <ol v-if="section.steps.length > 1">
              <li v-for="step in section.steps" :key="step">{{ step }}</li>
            </ol>
            <p v-else-if="section.steps.length">{{ section.steps[0] }}</p>
          </div>
          <div v-if="message.links.length" class="assistant-links" aria-label="相关页面">
            <RouterLink v-for="link in message.links" :key="link.path" :to="link.path" @click="afterLink">
              打开{{ link.label }} <el-icon aria-hidden="true"><ArrowRight /></el-icon>
            </RouterLink>
          </div>
        </template>
      </article>
      <p v-if="sending" class="assistant-state">正在查找相关操作说明…</p>
      <p v-if="error" class="assistant-state assistant-error" role="alert">{{ error }}</p>
    </div>

    <form class="assistant-compose" @submit.prevent="submit">
      <div class="assistant-compose-row">
        <textarea
          ref="input"
          v-model="draft"
          rows="2"
          maxlength="500"
          aria-label="输入 MES 问题"
          @keydown.enter.exact="handleEnter"
        />
        <el-button type="primary" native-type="submit" :loading="sending" :disabled="sending || !draft.trim()">发送</el-button>
      </div>
    </form>
  </section>
</template>

<script setup>
import { nextTick, onBeforeUnmount, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { askMesAssistant } from '@/api/assistant';
import { useAppStore } from '@/stores/app';
import { useAuthStore } from '@/stores/auth';

const props = defineProps({ open: Boolean });
const emit = defineEmits(['close', 'navigate']);
const route = useRoute();
const router = useRouter();
const appStore = useAppStore();
const authStore = useAuthStore();
const transcript = ref(null);
const input = ref(null);
const draft = ref('');
const sending = ref(false);
const error = ref('');
const messages = ref([]);
const previousTopics = ref([]);
const examples = ['生产工单怎么开工？', '工序报工怎么用？', '在哪里查看工单执行记录？'];
let request = null;

async function scrollToLatest() {
  await nextTick();
  if (transcript.value) transcript.value.scrollTop = transcript.value.scrollHeight;
}

watch(() => props.open, async (isOpen) => {
  if (!isOpen) return;
  await scrollToLatest();
  input.value?.focus({ preventScroll: true });
});
onBeforeUnmount(() => request?.abort());

function useExample(question) {
  draft.value = question;
  input.value?.focus();
}

function handleEnter(event) {
  if (event.isComposing) return;
  event.preventDefault();
  submit();
}

function canOpenLink(link) {
  if (!link.path.startsWith('/')) return false;
  const target = router.resolve(link.path);
  if (target.name === 'runtime-home') return true;
  if (target.meta.requiresStudio) return authStore.canAccessStudio;
  if (target.meta.requiresTenantAdmin) return authStore.canManageTenants;
  return target.name === 'runtime-menu' && Boolean(appStore.findMenuNode('', target.path));
}

function afterLink(event) {
  if (!event.ctrlKey && !event.metaKey && !event.shiftKey && !event.altKey) emit('navigate');
}

async function submit() {
  const question = draft.value.trim();
  if (!question || sending.value) return;
  draft.value = '';
  error.value = '';
  messages.value.push({ role: 'user', text: question });
  sending.value = true;
  request = new AbortController();
  scrollToLatest();
  try {
    const reply = await askMesAssistant(authStore.token, question, route.path, previousTopics.value, request.signal);
    messages.value.push({
      role: 'assistant',
      text: reply.message,
      sections: reply.sections,
      links: reply.links.filter(canOpenLink)
    });
    if (reply.topicIds.length) previousTopics.value = reply.topicIds;
  } catch (e) {
    if (e.name !== 'AbortError') {
      error.value = e.message;
      if (!draft.value) draft.value = question;
    }
  } finally {
    sending.value = false;
    request = null;
    scrollToLatest();
  }
}
</script>

<style scoped>
.mes-assistant {
  position: absolute;
  inset: 12px 12px 12px auto;
  z-index: 4;
  width: min(432px, calc(100% - 24px));
  display: flex;
  flex-direction: column;
  border: 1px solid var(--sidebar-rule);
  border-radius: var(--radius-panel);
  background: var(--bg-overlay);
  box-shadow: 0 12px 38px var(--panel-shadow);
}
.assistant-header {
  display: grid;
  grid-template-columns: auto 1fr auto;
  align-items: center;
  gap: 10px;
  padding: 12px 14px;
  border-bottom: 1px solid var(--border-light);
  background: var(--bg-header);
  border-radius: var(--radius-panel) var(--radius-panel) 0 0;
}
.assistant-icon {
  width: 36px;
  height: 36px;
  border-radius: var(--radius-control);
  background: var(--primary-soft);
  color: var(--primary);
  font-size: 19px;
}
h2, h3, p { margin: 0; }
h2 { font-size: 16px; }
.assistant-transcript {
  flex: 1;
  min-height: 0;
  overflow: auto;
  padding: 16px;
  scrollbar-width: thin;
  scrollbar-color: var(--sidebar-rule) transparent;
  font-size: 13px;
  line-height: 1.65;
  overflow-wrap: anywhere;
}
.assistant-welcome h3, .assistant-answer h3 { margin-bottom: 7px; font-size: 14px; }
.assistant-example {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 10px;
  width: 100%;
  margin: 7px 0;
  padding: 8px 11px;
  border: 1px solid var(--border-light);
  border-radius: var(--radius-control);
  background: var(--bg-card);
  color: var(--text-main);
  font: inherit;
  text-align: start;
  cursor: pointer;
}
.assistant-example:hover, .assistant-links a:hover { background: var(--primary-soft); color: var(--primary); }
.assistant-turn + .assistant-turn { margin-top: 14px; }
.assistant-turn--user { display: flex; justify-content: end; }
.assistant-question {
  max-width: 88%;
  padding: 7px 11px;
  border-radius: var(--radius-control);
  background: var(--primary-soft);
  color: var(--text-main);
  white-space: pre-wrap;
}
.assistant-answer + .assistant-answer { border-top: 1px solid var(--border-light); margin-top: 11px; padding-top: 11px; }
.assistant-answer p + p, .assistant-answer ol { margin: 6px 0 0; }
.assistant-answer ol { padding-inline-start: 21px; }
.assistant-answer li + li { margin-top: 4px; }
.assistant-message + .assistant-answer { margin-top: 8px; }
.assistant-state { margin-top: 8px; color: var(--text-muted); }
.assistant-error { color: var(--el-color-danger); }
.assistant-links { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 9px; }
.assistant-links a {
  display: inline-flex;
  gap: 4px;
  align-items: center;
  min-height: 31px;
  padding: 3px 8px;
  border: 1px solid var(--sidebar-rule);
  border-radius: var(--radius-control);
  color: var(--primary);
  background: var(--bg-card);
  text-decoration: none;
}
.assistant-compose {
  border-top: 1px solid var(--border-light);
  padding: 11px 14px;
}
.assistant-compose-row { display: flex; align-items: end; gap: 8px; }
textarea {
  flex: 1;
  min-width: 0;
  resize: none;
  border: 1px solid var(--control-border);
  border-radius: var(--radius-control);
  background: var(--bg-card);
  color: var(--text-main);
  padding: 7px 9px;
  font: inherit;
  font-size: 14px;
}
textarea:focus-visible, .assistant-links a:focus-visible, .assistant-example:focus-visible {
  outline: 2px solid var(--primary);
  outline-offset: -2px;
}
@media (max-width: 780px) {
  .mes-assistant { inset: 0; width: auto; border-radius: 0; }
  .assistant-header { border-radius: 0; }
}
</style>
