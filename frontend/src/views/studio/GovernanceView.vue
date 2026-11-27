<template>
  <div>
    <div class="lc-card">
      <div class="lc-card-header">
        <h2 class="lc-card-title">数据表停用与受控安全清理</h2>
        <span style="font-size:12px;color:var(--text-muted);">流程：暂停使用 → 1. 导出备份凭据 → 2. 申请彻底清理 → 系统管理员确认销毁</span>
      </div>
      <el-table :data="appStore.businessModels" border size="small">
        <el-table-column prop="displayName" label="中文名称" width="155" />
        <el-table-column prop="name" label="英文标识" width="150" />
        <el-table-column label="当前状态" width="115">
          <template #default="{ row }"><el-tag size="small" :type="lifecycleTagType(row.lifecycleState)">{{ friendlyLifecycleLabel(row.lifecycleState) }}</el-tag></template>
        </el-table-column>
        <el-table-column prop="offlineTime" label="停用时间" width="160" />
        <el-table-column label="管控操作" min-width="330">
          <template #default="{ row }">
            <el-button v-if="row.lifecycleState === 'ONLINE_ACTIVE'" size="small" type="warning" plain @click="handleOffline(row.name)">暂停使用</el-button>
            <el-button v-if="row.lifecycleState === 'OFFLINE'" size="small" type="success" plain @click="handleOnline(row.name)">恢复启用</el-button>
            <el-button size="small" @click="handleExportModelData(row.name)">1. 导出备份凭据</el-button>
            <el-button v-if="row.lifecycleState === 'OFFLINE'" size="small" type="danger" plain @click="openApplyPurgeDialog(row.name)">2. 申请清理</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <div class="lc-card">
      <div class="lc-card-header">
        <h2 class="lc-card-title">清理审批任务单</h2>
        <el-button size="small" @click="appStore.loadGovernanceLists()">刷新</el-button>
      </div>
      <el-table :data="appStore.purgeApplies" border size="small">
        <el-table-column prop="id" label="申请单号" width="125" />
        <el-table-column prop="modelName" label="目标数据表" width="130" />
        <el-table-column prop="exportTicket" label="备份凭据" width="155" />
        <el-table-column prop="applyBy" label="申请人" width="105" />
        <el-table-column prop="status" label="状态" width="100">
          <template #default="{ row }"><el-tag size="small" :type="row.status === 'EXECUTED' ? 'success' : (row.status === 'REJECTED' ? 'danger' : 'warning')">{{ row.status }}</el-tag></template>
        </el-table-column>
        <el-table-column label="管理员确认操作" min-width="195">
          <template #default="{ row }">
            <template v-if="row.status === 'PENDING'">
              <el-button size="small" type="danger" @click="handleConfirmPurge(row.id)">批准并销毁</el-button>
              <el-button size="small" @click="handleRejectPurge(row.id)">退回</el-button>
            </template>
            <span v-else style="color:var(--text-muted);font-size:12px;">{{ row.confirmBy ? '审核人: ' + row.confirmBy : '-' }}</span>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <div class="lc-card">
      <div class="lc-card-header"><h2 class="lc-card-title">历史清理审计留痕</h2></div>
      <el-table :data="appStore.purgeAudits" border size="small">
        <el-table-column prop="auditId" label="记录编号" width="145" />
        <el-table-column prop="modelName" label="原数据表" width="135" />
        <el-table-column prop="applyBy" label="申请人" width="105" />
        <el-table-column prop="confirmBy" label="审核人" width="115" />
        <el-table-column prop="purgedRecordCount" label="清理条数" width="85" />
        <el-table-column prop="purgedAt" label="执行时间" min-width="160" />
      </el-table>
    </div>

    <el-dialog v-model="purgeDialog.visible" :title="'申请彻底清理：' + purgeDialog.modelName" width="460px" destroy-on-close>
      <el-alert title="需先点击“导出备份凭据”，提交后可直接在下方任务单确认销毁。" type="warning" :closable="false" style="margin-bottom:10px;" />
      <el-form label-width="95px" size="small">
        <el-form-item label="备份凭据号" required><el-input v-model="purgeDialog.exportTicket" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button size="small" @click="purgeDialog.visible = false">取消</el-button>
        <el-button type="danger" size="small" @click="submitApplyPurge">提交申请</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { rpcCall } from '@/api/rpc';
import { useAppStore } from '@/stores/app';
import { lifecycleTagType, friendlyLifecycleLabel } from '@/utils/formatters';

const appStore = useAppStore();
const purgeDialog = reactive({ visible: false, modelName: '', exportTicket: '' });

async function handleOffline(modelName) {
  try {
    await rpcCall('meta_model', 'offline', { modelName });
    ElMessage.success('已暂停使用');
    await appStore.refreshMenusAndOverview();
    await appStore.loadGovernanceLists();
  } catch (e) {
    ElMessage.error(e.message);
  }
}

async function handleOnline(modelName) {
  try {
    await rpcCall('meta_model', 'online', { modelName });
    ElMessage.success('已恢复启用');
    await appStore.refreshMenusAndOverview();
    await appStore.loadGovernanceLists();
  } catch (e) {
    ElMessage.error(e.message);
  }
}

async function handleExportModelData(modelName) {
  try {
    const res = await rpcCall('meta_model', 'exportData', { modelName });
    appStore.lastExportTickets[modelName] = res.exportTicket;
    ElMessage.success('备份成功，凭据号：' + res.exportTicket);
  } catch (e) {
    ElMessage.error(e.message);
  }
}

function openApplyPurgeDialog(modelName) {
  purgeDialog.modelName = modelName;
  purgeDialog.exportTicket = appStore.lastExportTickets[modelName] || '';
  purgeDialog.visible = true;
}

async function submitApplyPurge() {
  try {
    await rpcCall('meta_model', 'applyPurge', { modelName: purgeDialog.modelName, exportTicket: purgeDialog.exportTicket });
    ElMessage.success('清理申请已提交，请在任务单中确认批准销毁');
    purgeDialog.visible = false;
    await appStore.loadGovernanceLists();
  } catch (e) {
    ElMessage.error(e.message);
  }
}

async function handleConfirmPurge(applyId) {
  try {
    await ElMessageBox.confirm('批准后将彻底销毁该表及数据且不可恢复，确认继续？', '终审确认', { type: 'error' });
    await rpcCall('meta_model', 'confirmPurge', { applyId });
    ElMessage.success('彻底清理完成');
    await appStore.refreshMenusAndOverview();
    await appStore.loadGovernanceLists();
  } catch (e) {
    if (e !== 'cancel') ElMessage.error(e.message);
  }
}

async function handleRejectPurge(applyId) {
  try {
    await rpcCall('meta_model', 'rejectPurge', { applyId, reason: '审核人退回' });
    ElMessage.info('已退回');
    await appStore.loadGovernanceLists();
  } catch (e) {
    ElMessage.error(e.message);
  }
}

onMounted(() => {
  appStore.loadGovernanceLists();
});
</script>
