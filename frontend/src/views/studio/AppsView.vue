<template>
  <div>
    <div class="lc-card">
      <div class="lc-card-header">
        <h2 class="lc-card-title">应用管理</h2>
        <div style="display:flex;gap:8px;align-items:center;">
          <el-button size="small" type="primary" @click="openNewAppDialog">+ 新建业务应用</el-button>
          <el-button size="small" @click="importExportDrawerVisible = true">配置包导入 / 导出</el-button>
          <el-radio-group v-model="appsViewMode" size="small">
            <el-radio-button value="card">卡片</el-radio-button>
            <el-radio-button value="table">列表</el-radio-button>
          </el-radio-group>
        </div>
      </div>

      <el-table v-if="appsViewMode === 'table'" :data="appStore.appsList" border size="small">
        <el-table-column prop="displayName" label="应用名称" width="175" />
        <el-table-column prop="name" label="应用编码" width="145" />
        <el-table-column prop="category" label="分类" width="100" />
        <el-table-column prop="version" label="版本" width="80" />
        <el-table-column label="已授权租户" min-width="200">
          <template #default="{ row }">
            <span v-if="row.name === 'platform_kernel'" style="color:var(--text-muted);font-size:12px;">系统底座</span>
            <template v-else>
              <el-tag v-for="tn in appStore.getTenantsForApp(row.name)" :key="tn.id" size="small" type="success" style="margin-right:4px;">{{ tn.name }}</el-tag>
              <span v-if="appStore.getTenantsForApp(row.name).length === 0" style="color:var(--text-muted);font-size:12px;">暂未分配给独立租户</span>
            </template>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="230">
          <template #default="{ row }">
            <el-button v-if="row.name !== 'platform_kernel'" link type="primary" size="small" @click="openEditAppDialog(row)">编辑</el-button>
            <el-button link type="success" size="small" @click="exportAppJsonAndOpenDrawer(row.name)">导出配置</el-button>
            <el-button v-if="row.name !== 'platform_kernel' && authStore.isSystemAdmin" link type="warning" size="small" @click="router.push('/studio/iam')">授权管理</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div v-else class="lc-grid-cards">
        <div v-for="a in appStore.appsList" :key="a.name" class="lc-grid-card-item">
          <div style="display:flex;justify-content:space-between;align-items:center;">
            <strong style="font-size:15px;">{{ a.displayName }}</strong>
            <el-tag size="small">{{ a.version }}</el-tag>
          </div>
          <div style="font-size:12px;color:var(--text-muted);margin:6px 0;">编码：{{ a.name }} · 分类：{{ a.category }}</div>
          <div v-if="a.description" style="font-size:12.5px;color:var(--text-regular);min-height:36px;">{{ a.description }}</div>
          <div v-if="a.name !== 'platform_kernel'" style="margin:8px 0;">
            <span style="font-size:11.5px;color:var(--text-muted);">授权租户：</span>
            <el-tag v-for="tn in appStore.getTenantsForApp(a.name)" :key="tn.id" size="small" type="success" style="margin-right:4px;">{{ tn.name }}</el-tag>
            <span v-if="appStore.getTenantsForApp(a.name).length === 0" style="color:var(--text-muted);font-size:11.5px;">未授权</span>
          </div>
          <div style="margin-top:8px;display:flex;gap:6px;flex-wrap:wrap;">
            <el-button v-if="a.name !== 'platform_kernel'" size="small" type="primary" plain @click="jumpToAppAndSwitch(a.name)">打开使用</el-button>
            <el-button size="small" @click="exportAppJsonAndOpenDrawer(a.name)">导出配置</el-button>
            <el-button v-if="a.name !== 'platform_kernel' && authStore.isSystemAdmin" size="small" @click="router.push('/studio/iam')">授权</el-button>
          </div>
        </div>
      </div>
    </div>

    <el-dialog v-model="appDialog.visible" :title="appDialog.isEdit ? '编辑业务应用' : '新建业务应用'" width="480px" destroy-on-close>
      <el-form label-width="95px" size="small">
        <el-form-item label="应用名称" required>
          <el-input v-model="appDialog.form.displayName" placeholder="例如：设备巡检管理" />
        </el-form-item>
        <el-form-item label="英文编码" required>
          <el-input v-model="appDialog.form.name" :disabled="appDialog.isEdit" placeholder="小写字母+下划线，如 inspect_app" />
        </el-form-item>
        <el-form-item label="应用分类">
          <el-select v-model="appDialog.form.category" style="width:100%;">
            <el-option value="enterprise" label="企业运营管理" />
            <el-option value="manufacturing" label="生产制造执行" />
            <el-option value="office" label="行政办公协同" />
            <el-option value="custom" label="通用自定义应用" />
          </el-select>
        </el-form-item>
        <el-form-item label="版本号"><el-input v-model="appDialog.form.version" placeholder="1.0.0" /></el-form-item>
        <el-form-item label="应用说明">
          <el-input v-model="appDialog.form.description" type="textarea" :rows="3" placeholder="简要说明该应用承载的业务场景" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button size="small" @click="appDialog.visible = false">取消</el-button>
        <el-button type="primary" size="small" @click="saveNewApp">保存应用</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="importExportDrawerVisible" title="应用配置包导入与导出 (JSON)" size="640px" destroy-on-close>
      <div style="margin-bottom:12px;display:flex;justify-content:space-between;align-items:center;flex-wrap:wrap;gap:8px;">
        <el-switch v-model="importAutoPublish" active-text="导入后立即上线可用" inactive-text="仅存为草稿" />
        <div style="display:flex;gap:8px;">
          <el-button size="small" @click="fillBlankPackageTemplate">填入空白示例模板</el-button>
          <el-button size="small" type="primary" :loading="importingPkg" @click="submitImportPackage">校验并导入</el-button>
        </div>
      </div>
      <el-input v-model="importPkgText" type="textarea" :rows="22" placeholder="粘贴符合规范的应用配置 JSON 内容，或点击应用行的“导出配置”生成可移植配置包..." />
    </el-drawer>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { rpcCall } from '@/api/rpc';
import { useAuthStore } from '@/stores/auth';
import { useAppStore } from '@/stores/app';

const router = useRouter();
const authStore = useAuthStore();
const appStore = useAppStore();

const appsViewMode = ref('card');
const appDialog = reactive({
  visible: false,
  isEdit: false,
  form: { id: '', name: '', displayName: '', category: 'enterprise', version: '1.0.0', description: '' }
});
const importExportDrawerVisible = ref(false);
const importPkgText = ref('');
const importAutoPublish = ref(true);
const importingPkg = ref(false);

function openNewAppDialog() {
  appDialog.isEdit = false;
  appDialog.form = { id: '', name: '', displayName: '', category: 'enterprise', version: '1.0.0', description: '' };
  appDialog.visible = true;
}

function openEditAppDialog(row) {
  appDialog.isEdit = true;
  appDialog.form = { ...row };
  appDialog.visible = true;
}

async function saveNewApp() {
  if (!appDialog.form.name.trim() || !appDialog.form.displayName.trim()) {
    ElMessage.warning('请填写应用名称和英文编码');
    return;
  }
  try {
    await rpcCall('meta_app', 'save', { app: appDialog.form });
    ElMessage.success('应用保存成功');
    appDialog.visible = false;
    await appStore.loadStudioOverview();
    try {
      await authStore.refreshProfile();
    } catch {
      // ignore
    }
  } catch (e) {
    ElMessage.error(e.message);
  }
}

async function exportAppJsonAndOpenDrawer(appName) {
  try {
    const pkg = await rpcCall('meta_app', 'exportPackage', { appName });
    importPkgText.value = JSON.stringify(pkg, null, 2);
    importExportDrawerVisible.value = true;
  } catch (e) {
    ElMessage.error(e.message);
  }
}

function fillBlankPackageTemplate() {
  importPkgText.value = JSON.stringify({
    app: { name: 'sample_app', displayName: '通用业务应用', category: 'custom', version: '1.0.0', description: '业务配置包模板' },
    models: [{
      name: 'custom_entity',
      displayName: '业务主表',
      appName: 'sample_app',
      versionNo: '1.0.0',
      properties: [
        { name: 'code', displayName: '编号', dataType: 'String', required: true, unique: true },
        { name: 'title', displayName: '标题', dataType: 'String', required: true, displayForModel: true },
        {
          name: 'status',
          displayName: '状态',
          dataType: 'Selection',
          defaultValue: 'OPEN',
          values: [{ value: 'OPEN', label: '待处理', color: 'info' }, { value: 'DONE', label: '已完成', color: 'success' }]
        }
      ],
      services: [{
        name: 'completeEntity',
        displayName: '标记完成',
        type: 'STATE_TRANSITION',
        auth: 'action',
        transitionRule: { statusField: 'status', fromStates: ['OPEN'], toState: 'DONE', errorMessage: '仅待处理状态可标记完成' }
      }]
    }]
  }, null, 2);
}

async function submitImportPackage() {
  if (!importPkgText.value.trim()) {
    ElMessage.warning('请先填入配置包内容');
    return;
  }
  importingPkg.value = true;
  try {
    const pkg = JSON.parse(importPkgText.value);
    const res = await rpcCall('meta_app', 'importPackage', { package: pkg, autoPublish: importAutoPublish.value });
    ElMessage.success('导入成功，共处理 ' + (res.importedModels?.length || 0) + ' 个数据表');
    importExportDrawerVisible.value = false;
    await appStore.refreshMenusAndOverview();
  } catch (e) {
    ElMessage.error('导入失败：' + e.message);
  } finally {
    importingPkg.value = false;
  }
}

async function jumpToAppAndSwitch(appName) {
  await appStore.loadMenuTree();
  const leaf = appStore.findFirstLeafMenu(appName);
  if (leaf?.routePath) {
    await router.push(leaf.routePath);
  } else {
    ElMessage.info('应用 [' + appStore.resolveAppDisplayName(appName) + '] 暂未生成菜单页面，可先在管理中心创建数据表');
  }
}

watch(() => appStore.appCreateDialogTrigger, (v, oldV) => {
  if (v > oldV) openNewAppDialog();
});

onMounted(() => {
  appStore.loadStudioOverview();
  if (appStore.appCreateDialogTrigger > 0) {
    openNewAppDialog();
  }
});
</script>
