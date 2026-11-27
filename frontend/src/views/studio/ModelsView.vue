<template>
  <div class="lc-card">
    <div class="lc-card-header">
      <h2 class="lc-card-title">
        <span>表单与数据表管理</span>
        <el-checkbox v-model="showKernelModels" size="small" style="margin-left:8px;">显示系统基础表</el-checkbox>
      </h2>
      <div style="display:flex; gap:8px;">
        <el-button type="primary" size="small" @click="openNewModelDesigner">+ 新建数据表</el-button>
        <el-button size="small" @click="appStore.loadStudioOverview()">刷新</el-button>
      </div>
    </div>

    <el-table :data="displayedModels" border stripe size="small" style="width:100%;">
      <el-table-column prop="displayName" label="中文名称" min-width="140" />
      <el-table-column prop="name" label="英文标识与复用关系" min-width="200">
        <template #default="{ row }">
          <code>{{ row.name }}</code>
          <el-tag v-if="row.kernelModel" size="small" type="info" style="margin-left:5px;">系统表</el-tag>
          <el-tag v-if="row.abstractModel" size="small" type="warning" style="margin-left:5px;">仅作模板</el-tag>
          <el-tag v-if="row.extendModel" size="small" type="success" style="margin-left:5px;">扩展:{{ row.extendModel }}</el-tag>
          <el-tag v-if="row.parents && row.parents.length" size="small" style="margin-left:5px;">复用:{{ row.parents.join(',') }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="所属应用" width="135">
        <template #default="{ row }">{{ appStore.resolveAppDisplayName(row.appName) }}</template>
      </el-table-column>
      <el-table-column prop="versionNo" label="版本" width="80" />
      <el-table-column prop="fieldCount" label="字段" width="60" align="center" />
      <el-table-column label="启用状态" width="110">
        <template #default="{ row }">
          <el-tag size="small" :type="lifecycleTagType(row.lifecycleState)">{{ friendlyLifecycleLabel(row.lifecycleState) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="340" fixed="right">
        <template #default="{ row }">
          <div style="display:flex;align-items:center;gap:6px;flex-wrap:nowrap;">
            <el-button link type="primary" size="small" style="margin-left:0;" @click="openModelDesigner(row.name)">设计字段</el-button>
            <el-button link type="success" size="small" style="margin-left:0;" :disabled="row.kernelModel || row.abstractModel || !!row.extendModel" @click="handleBuildDefaultViews(row.name)">生成页面</el-button>
            <el-button link type="warning" size="small" style="margin-left:0;" @click="openVersionCenter(row.name)">版本/检查</el-button>
            <el-button link type="primary" size="small" style="margin-left:0;" :disabled="row.kernelModel || row.abstractModel || !!row.extendModel || row.lifecycleState !== 'ONLINE_ACTIVE' || !row.activeVersionId" @click="jumpToRuntimeModel(row.name)">打开使用</el-button>
            <el-button v-if="row.lifecycleState === 'ONLINE_ACTIVE'" link type="danger" size="small" style="margin-left:0;" :disabled="row.kernelModel || row.abstractModel || !!row.extendModel" @click="handleOfflineModel(row)">停用</el-button>
            <el-button v-else-if="row.lifecycleState === 'OFFLINE'" link type="success" size="small" style="margin-left:0;" :disabled="row.kernelModel" @click="handleOnlineModel(row)">恢复使用</el-button>
            <el-button link type="info" size="small" style="margin-left:0;" :disabled="row.abstractModel || !!row.extendModel || !row.activeVersionId" @click="handleBackupModel(row)">备份</el-button>
          </div>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import { rpcCall } from '@/api/rpc';
import { useAppStore } from '@/stores/app';
import { lifecycleTagType, friendlyLifecycleLabel } from '@/utils/formatters';

const router = useRouter();
const appStore = useAppStore();
const showKernelModels = ref(false);

const displayedModels = computed(() =>
  showKernelModels.value ? appStore.modelsSummary : appStore.businessModels
);

function openNewModelDesigner() {
  appStore.designerDialogState.isEdit = false;
  appStore.designerDialogState.modelToOpen = '';
  appStore.designerDialogState.visible = true;
}

function openModelDesigner(modelName) {
  appStore.designerDialogState.isEdit = true;
  appStore.designerDialogState.modelToOpen = modelName;
  appStore.designerDialogState.visible = true;
}

function openVersionCenter(modelName) {
  appStore.versionCenterState.modelName = modelName;
  appStore.versionCenterState.impact = null;
  appStore.versionCenterState.visible = true;
}

async function handleBuildDefaultViews(modelName) {
  try {
    await rpcCall('meta_model', 'buildDefaultViews', { modelName });
    ElMessage.success('页面生成完成');
    await appStore.refreshMenusAndOverview();
  } catch (e) {
    ElMessage.error(e.message);
  }
}

async function handleOfflineModel(row) {
  try {
    await ElMessageBox.confirm(
      `确认停用数据表「${row.displayName || row.name}」吗？停用后普通业务接口与工作区入口将暂停访问，随时可恢复使用。`,
      '停用确认',
      { type: 'warning', confirmButtonText: '确认停用', cancelButtonText: '取消' }
    );
    await rpcCall('meta_model', 'offline', { modelName: row.name });
    ElMessage.success(`数据表 ${row.displayName || row.name} 已停用`);
    await appStore.refreshMenusAndOverview();
  } catch (e) {
    if (e !== 'cancel' && e !== 'close') {
      ElMessage.error(e.message || '停用失败');
    }
  }
}

async function handleOnlineModel(row) {
  try {
    await rpcCall('meta_model', 'online', { modelName: row.name });
    ElMessage.success(`数据表 ${row.displayName || row.name} 已恢复使用`);
    await appStore.refreshMenusAndOverview();
  } catch (e) {
    ElMessage.error(e.message || '恢复使用失败');
  }
}

async function handleBackupModel(row) {
  try {
    const res = await rpcCall('meta_model', 'exportData', { modelName: row.name });
    const payload = {
      modelName: res.modelName || row.name,
      displayName: row.displayName || row.name,
      exportTicket: res.exportTicket,
      recordCount: res.recordCount ?? 0,
      exportedAt: new Date().toISOString(),
      records: res.records || []
    };
    const blob = new Blob([JSON.stringify(payload, null, 2)], { type: 'application/json;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = `${row.name}_backup_${Date.now()}.json`;
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
    URL.revokeObjectURL(url);
    ElMessage.success(`备份成功：共导出 ${payload.recordCount} 条记录（凭据 ${res.exportTicket}）`);
  } catch (e) {
    ElMessage.error(e.message || '数据备份失败');
  }
}

function jumpToRuntimeModel(modelName) {
  router.push('/m/' + encodeURIComponent(modelName));
}

onMounted(() => {
  appStore.loadStudioOverview();
});
</script>
