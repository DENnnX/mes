<template>
  <div class="lc-card">
    <div class="lc-card-header">
      <h2 class="lc-card-title">自动生成的开放接口说明文档</h2>
      <el-button size="small" @click="loadApiCatalog">刷新文档</el-button>
    </div>
    <el-row :gutter="14">
      <el-col :span="8">
        <div style="border:1px solid var(--border-light);border-radius:8px;padding:10px;max-height:540px;overflow-y:auto;">
          <div v-for="cat in apiCatalog" :key="cat.category" style="margin-bottom:10px;">
            <div
              style="font-size:12.5px;font-weight:700;color:var(--text-main);padding:4px 6px;background:var(--bg-muted);border-radius:5px;cursor:pointer;"
              @click="cat.modelName ? inspectApiModel(cat.modelName) : (activeApiCategory = cat)"
            >
              {{ cat.title }}
            </div>
            <div
              v-for="ep in (cat.endpoints || [])"
              :key="ep.rpcMethod"
              style="font-size:12px;padding:4px 8px;cursor:pointer;color:var(--text-regular);"
              @click="selectApiEndpoint(cat, ep)"
            >
              <code>{{ ep.rpcMethod }}</code>
            </div>
          </div>
        </div>
      </el-col>
      <el-col :span="16">
        <div v-if="apiModelSpec" style="margin-bottom:12px;">
          <div style="display:flex;align-items:center;gap:8px;margin-bottom:8px;">
            <strong style="font-size:15px;">{{ apiModelSpec.modelMeta.displayName }} ({{ apiModelSpec.modelMeta.name }})</strong>
            <el-tag size="small">表名: {{ apiModelSpec.modelMeta.tableName }}</el-tag>
          </div>
          <el-table :data="apiModelSpec.properties" border size="small" max-height="200">
            <el-table-column prop="name" label="字段名" width="120" />
            <el-table-column prop="displayName" label="中文名" width="110" />
            <el-table-column prop="dataType" label="类型" width="95" />
            <el-table-column label="特性" min-width="190">
              <template #default="{ row }">
                <el-tag v-if="row.required" size="small" type="danger" style="margin-right:3px;">必填</el-tag>
                <el-tag v-if="row.dictType" size="small" style="margin-right:3px;">字典:{{ row.dictType }}</el-tag>
                <el-tag v-if="row.codeRule" size="small" type="success" style="margin-right:3px;">编号:{{ row.codeRule }}</el-tag>
                <el-tag v-if="row.computeScript || row.computeMethod" size="small" type="warning" style="margin-right:3px;">自动计算</el-tag>
              </template>
            </el-table-column>
          </el-table>
        </div>
        <div v-if="selectedApiEndpoint" style="border:1px solid var(--border-light);border-radius:8px;padding:12px;background:var(--bg-muted);">
          <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:8px;">
            <div>
              <strong>{{ selectedApiEndpoint.rpcMethod }}</strong>
              <span style="font-size:12px;color:var(--text-muted);margin-left:8px;">{{ selectedApiEndpoint.summary }}</span>
            </div>
            <el-button size="small" type="primary" @click="tryEndpointInConsole(selectedApiEndpoint)">在调试工具中试调</el-button>
          </div>
          <pre class="lc-code" style="max-height:230px;margin:0;">{{ JSON.stringify(selectedApiEndpoint.requestTemplate, null, 2) }}</pre>
        </div>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { rpcCall } from '@/api/rpc';
import { useAppStore } from '@/stores/app';

const router = useRouter();
const appStore = useAppStore();

const apiCatalog = ref([]);
const activeApiCategory = ref(null);
const apiModelSpec = ref(null);
const selectedApiEndpoint = ref(null);

async function inspectApiModel(modelName) {
  try {
    apiModelSpec.value = await rpcCall('api_doc', 'describeModel', { modelName });
  } catch {
    // ignore
  }
}

async function selectApiEndpoint(cat, ep) {
  selectedApiEndpoint.value = ep;
  if (cat.modelName) await inspectApiModel(cat.modelName);
  else apiModelSpec.value = null;
}

async function loadApiCatalog() {
  try {
    apiCatalog.value = (await rpcCall('api_doc', 'listCatalog', {})) || [];
    if (apiCatalog.value.length > 0 && !selectedApiEndpoint.value) {
      const first = apiCatalog.value.find((c) => c.modelName) || apiCatalog.value[0];
      if (first.modelName) await inspectApiModel(first.modelName);
      if (first.endpoints?.length) selectedApiEndpoint.value = first.endpoints[0];
    }
  } catch {
    // ignore
  }
}

function tryEndpointInConsole(ep) {
  const [m, s] = (ep.rpcMethod || '').split('.');
  appStore.rpcConsolePreset.model = m || '';
  appStore.rpcConsolePreset.service = s || '';
  appStore.rpcConsolePreset.argsText = JSON.stringify(ep.sampleArgs || {}, null, 2);
  router.push('/studio/rpc_console');
}

onMounted(loadApiCatalog);
</script>
