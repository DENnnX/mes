<template>
  <div class="lc-card">
    <div class="lc-card-header">
      <h2 class="lc-card-title">接口调试工具</h2>
      <el-button type="primary" size="small" :loading="loading" @click="runConsoleRpc">发送请求</el-button>
    </div>
    <el-row :gutter="12" style="margin-bottom:10px;">
      <el-col :span="6">
        <div class="lc-search-label">接口地址</div>
        <el-select v-model="endpoint" size="small" style="width:100%;">
          <el-option value="/rbac_tenant_root/rpc/service" label="POST /rbac_tenant_root/rpc/service (鉴权)" />
          <el-option value="/rpc/open/service" label="POST /rpc/open/service (免鉴权)" />
        </el-select>
      </el-col>
      <el-col :span="4"><div class="lc-search-label">目标数据表</div><el-input v-model="model" size="small" /></el-col>
      <el-col :span="4"><div class="lc-search-label">功能方法</div><el-input v-model="service" size="small" /></el-col>
      <el-col :span="10">
        <div class="lc-search-label">快捷模板</div>
        <div style="display:flex; gap:6px; flex-wrap:wrap;">
          <el-button size="small" @click="presetRpc('ui_menu', 'loadMenu', {})">加载菜单</el-button>
          <el-button size="small" @click="presetRpc('rbac_tenant', 'listTenants', {})">查看租户</el-button>
          <el-button size="small" @click="presetRpc('meta_dict', 'listTypes', {})">字典列表</el-button>
        </div>
      </el-col>
    </el-row>
    <el-row :gutter="12">
      <el-col :span="10"><div class="lc-search-label">请求参数 (JSON)</div><el-input v-model="argsText" type="textarea" :rows="11" /></el-col>
      <el-col :span="14"><div class="lc-search-label">返回报文 ({{ latencyMs }} ms)</div><pre class="lc-code" style="height:245px;margin:0;">{{ responseText }}</pre></el-col>
    </el-row>
  </div>
</template>

<script setup>
import { ref, watch } from 'vue';
import { useAuthStore } from '@/stores/auth';
import { useAppStore } from '@/stores/app';

const authStore = useAuthStore();
const appStore = useAppStore();

const endpoint = ref('/rbac_tenant_root/rpc/service');
const model = ref(appStore.rpcConsolePreset.model || 'meta_model');
const service = ref(appStore.rpcConsolePreset.service || 'listModels');
const argsText = ref(appStore.rpcConsolePreset.argsText || '{}');
const latencyMs = ref(0);
const responseText = ref('点击上方“发送请求”即可查看接口返回结果...');
const loading = ref(false);

watch(
  () => [appStore.rpcConsolePreset.model, appStore.rpcConsolePreset.service, appStore.rpcConsolePreset.argsText],
  ([m, s, a]) => {
    if (m) model.value = m;
    if (s) service.value = s;
    if (a) argsText.value = a;
  }
);

function presetRpc(m, s, args) {
  model.value = m;
  service.value = s;
  argsText.value = JSON.stringify(args, null, 2);
}

async function runConsoleRpc() {
  loading.value = true;
  const t0 = performance.now();
  try {
    const headers = { 'Content-Type': 'application/json' };
    if (authStore.token) headers['Authorization'] = 'Bearer ' + authStore.token;
    const payload = {
      jsonrpc: '2.0',
      id: 'console-' + Date.now(),
      method: 'service',
      params: { app: 'rpc_console', tag: 'master', model: model.value, service: service.value, context: { lang: 'zh-CN' }, args: JSON.parse(argsText.value || '{}') }
    };
    const resp = await fetch(endpoint.value, { method: 'POST', headers, body: JSON.stringify(payload) });
    const json = await resp.json();
    latencyMs.value = Math.round(performance.now() - t0);
    responseText.value = JSON.stringify(json, null, 2);
  } catch (e) {
    responseText.value = '请求或格式错误：' + e.message;
  } finally {
    loading.value = false;
  }
}
</script>
