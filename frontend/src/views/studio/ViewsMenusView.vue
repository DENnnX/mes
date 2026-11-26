<template>
  <div>
    <div class="lc-card">
      <div class="lc-card-header">
        <h2 class="lc-card-title">页面布局配置 (支持可视化调整列、搜索条件与按钮)</h2>
        <div style="display:flex; gap:8px;">
          <el-select v-model="filterViewModel" clearable placeholder="按数据表筛选" size="small" style="width:195px;" @change="loadViewsAndMenus">
            <el-option v-for="m in appStore.businessModels" :key="m.name" :label="m.displayName + ' (' + m.name + ')'" :value="m.name" />
          </el-select>
          <el-button size="small" type="primary" @click="openNewViewDialog">+ 新增布局</el-button>
        </div>
      </div>
      <el-table :data="viewsList" border size="small">
        <el-table-column prop="name" label="页面名称" min-width="175" />
        <el-table-column prop="model" label="所属数据表" width="155" />
        <el-table-column label="页面类型" width="105">
          <template #default="{ row }"><el-tag size="small">{{ friendlyViewType(row.type) }}</el-tag></template>
        </el-table-column>
        <el-table-column prop="key" label="配置标识" width="190" />
        <el-table-column label="操作" width="125">
          <template #default="{ row }"><el-button link type="primary" size="small" @click="editViewVisual(row)">可视化设计</el-button></template>
        </el-table-column>
      </el-table>
    </div>

    <div class="lc-card">
      <div class="lc-card-header">
        <h2 class="lc-card-title">应用侧边导航菜单配置</h2>
        <el-button size="small" type="primary" @click="openMenuEditor(null)">+ 新增菜单项</el-button>
      </div>
      <el-table :data="menusFlatList" border size="small">
        <el-table-column prop="displayName" label="菜单名称" width="155" />
        <el-table-column prop="name" label="菜单标识" width="165" />
        <el-table-column label="所属应用" width="135"><template #default="{ row }">{{ appStore.resolveAppDisplayName(row.appName) }}</template></el-table-column>
        <el-table-column prop="parentId" label="上级菜单" width="150" />
        <el-table-column prop="model" label="打开的数据表" width="145" />
        <el-table-column prop="sequence" label="排序" width="65" align="center" />
        <el-table-column label="启用" width="70" align="center">
          <template #default="{ row }"><el-tag size="small" :type="row.active ? 'success' : 'info'">{{ row.active ? '是' : '否' }}</el-tag></template>
        </el-table-column>
        <el-table-column label="操作" width="90">
          <template #default="{ row }"><el-button link type="primary" size="small" @click="openMenuEditor(row)">编辑</el-button></template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 可视化页面布局设计器弹窗 -->
    <el-dialog v-model="viewEditor.visible" :title="'页面设计：' + (viewEditor.form.name || viewEditor.form.key)" width="820px" destroy-on-close>
      <el-form label-width="85px" size="small">
        <el-row :gutter="10">
          <el-col :span="8"><el-form-item label="页面名称"><el-input v-model="viewEditor.form.name" /></el-form-item></el-col>
          <el-col :span="8"><el-form-item label="所属数据表"><el-input v-model="viewEditor.form.model" :disabled="!!viewEditor.form.id" @change="buildVisualColumnsForView" /></el-form-item></el-col>
          <el-col :span="8">
            <el-form-item label="布局类型">
              <el-select v-model="viewEditor.form.type" :disabled="!!viewEditor.form.id" style="width:100%;">
                <el-option value="grid" label="数据表格" />
                <el-option value="search" label="顶部搜索栏" />
                <el-option value="form" label="录入表单" />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>
      </el-form>

      <el-tabs v-model="viewEditor.designTab" @tab-change="onViewEditorTabChange">
        <el-tab-pane label="可视化布局调整" name="visual">
          <div style="margin-bottom:8px;display:flex;justify-content:space-between;align-items:center;">
            <span style="font-size:12.5px;color:var(--text-muted);">勾选要显示的字段，调整显示名称、宽度与顺序：</span>
            <el-button size="small" @click="addVisualColumn">+ 手动加列</el-button>
          </div>
          <el-table :data="viewEditor.visualColumns" border size="small" max-height="290">
            <el-table-column label="启用" width="55" align="center"><template #default="{ row }"><el-checkbox v-model="row.enabled" /></template></el-table-column>
            <el-table-column label="字段标识" width="140"><template #default="{ row }"><el-input v-model="row.name" size="small" /></template></el-table-column>
            <el-table-column label="界面显示标题" min-width="150"><template #default="{ row }"><el-input v-model="row.displayName" size="small" /></template></el-table-column>
            <el-table-column v-if="viewEditor.form.type === 'grid'" label="列宽" width="100"><template #default="{ row }"><el-input v-model="row.width" size="small" placeholder="自动" /></template></el-table-column>
            <el-table-column v-if="viewEditor.form.type === 'grid'" label="可排序" width="68" align="center"><template #default="{ row }"><el-checkbox v-model="row.sortable" /></template></el-table-column>
            <el-table-column label="排序调整" width="110" align="center">
              <template #default="{ $index }">
                <el-button link size="small" :disabled="$index === 0" @click="moveVisualCol($index, -1)">上移</el-button>
                <el-button link size="small" :disabled="$index === viewEditor.visualColumns.length - 1" @click="moveVisualCol($index, 1)">下移</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
        <el-tab-pane label="源码模式 (高级)" name="json">
          <el-input v-model="viewEditor.bodyText" type="textarea" :rows="13" />
        </el-tab-pane>
      </el-tabs>
      <template #footer>
        <el-button size="small" @click="viewEditor.visible = false">取消</el-button>
        <el-button type="primary" size="small" @click="saveViewFromDesigner">保存布局生效</el-button>
      </template>
    </el-dialog>

    <!-- 导航菜单编辑弹窗 -->
    <el-dialog v-model="menuEditor.visible" title="导航菜单配置" width="520px" destroy-on-close>
      <el-form label-width="95px" size="small">
        <el-form-item label="菜单标识" required><el-input v-model="menuEditor.form.name" /></el-form-item>
        <el-form-item label="显示名称" required><el-input v-model="menuEditor.form.displayName" /></el-form-item>
        <el-form-item label="所属应用"><el-input v-model="menuEditor.form.appName" /></el-form-item>
        <el-form-item label="上级菜单"><el-input v-model="menuEditor.form.parentId" /></el-form-item>
        <el-form-item label="打开数据表"><el-input v-model="menuEditor.form.model" /></el-form-item>
        <el-form-item label="使用布局"><el-input v-model="menuEditor.form.view" /></el-form-item>
        <el-form-item label="默认筛选">
          <el-input v-model="menuEditor.form.filter" placeholder='可选 JSON 条件，如 [["status","=","DONE"]]' />
        </el-form-item>
        <el-form-item label="排序号"><el-input-number v-model="menuEditor.form.sequence" /></el-form-item>
        <el-form-item label="启用"><el-switch v-model="menuEditor.form.active" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button size="small" @click="menuEditor.visible = false">取消</el-button>
        <el-button type="primary" size="small" @click="saveMenuNode">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue';
import { ElMessage } from 'element-plus';
import { rpcCall } from '@/api/rpc';
import { useAppStore } from '@/stores/app';
import { friendlyViewType } from '@/utils/formatters';

const appStore = useAppStore();

const viewsList = ref([]);
const menusFlatList = ref([]);
const filterViewModel = ref('');

const viewEditor = reactive({
  visible: false,
  designTab: 'visual',
  visualColumns: [],
  parsedBody: {},
  form: { key: '', model: '', type: 'grid', name: '', mode: 'primary', active: true },
  bodyText: '{}'
});

const menuEditor = reactive({
  visible: false,
  form: { name: '', displayName: '', appName: 'custom_app', parentId: '', model: '', view: '', filter: '', sequence: 10, active: true }
});

async function loadViewsAndMenus() {
  try {
    const [vs, ms] = await Promise.all([
      rpcCall('meta_view', 'listViews', { modelName: filterViewModel.value || '' }),
      rpcCall('meta_menu', 'listMenus', {})
    ]);
    viewsList.value = vs || [];
    menusFlatList.value = ms || [];
  } catch (e) {
    ElMessage.error(e.message);
  }
}

async function buildVisualColumnsForView() {
  const existingCols = Array.isArray(viewEditor.parsedBody.columns) ? viewEditor.parsedBody.columns : [];
  const byName = {};
  const ordered = [];
  for (const c of existingCols) {
    const item = { raw: { ...c }, name: c.name, displayName: c.displayName || c.name, width: c.width || '', sortable: !!c.sortable, enabled: true };
    ordered.push(item);
    byName[c.name] = item;
  }
  if (viewEditor.form.model) {
    try {
      const det = await rpcCall('meta_model', 'detail', { modelName: viewEditor.form.model });
      const activeVer = (det.versions || []).find((v) => v.id === det.model?.activeVersionId) || (det.versions || [])[0];
      for (const p of activeVer?.snapshot?.properties || []) {
        if (!byName[p.name]) {
          ordered.push({ name: p.name, displayName: p.displayName || p.name, width: '', sortable: true, enabled: false });
        }
      }
    } catch {
      // ignore
    }
  }
  viewEditor.visualColumns = ordered;
}

async function editViewVisual(row) {
  viewEditor.form = { id: row.id, key: row.key, model: row.model, type: row.type, name: row.name, mode: row.mode || 'primary', active: row.active };
  viewEditor.parsedBody = JSON.parse(JSON.stringify(row.body || {}));
  viewEditor.bodyText = JSON.stringify(viewEditor.parsedBody, null, 2);
  viewEditor.designTab = 'visual';
  await buildVisualColumnsForView();
  viewEditor.visible = true;
}

function openNewViewDialog() {
  viewEditor.form = { key: '', model: '', type: 'grid', name: '', mode: 'primary', active: true };
  viewEditor.parsedBody = { type: 'grid', columns: [], tbar: [], buttons: [] };
  viewEditor.bodyText = JSON.stringify(viewEditor.parsedBody, null, 2);
  viewEditor.visualColumns = [];
  viewEditor.designTab = 'visual';
  viewEditor.visible = true;
}

function addVisualColumn() {
  viewEditor.visualColumns.push({ name: '', displayName: '', width: '', sortable: true, enabled: true });
}

function moveVisualCol(idx, delta) {
  const next = idx + delta;
  if (next < 0 || next >= viewEditor.visualColumns.length) return;
  const arr = viewEditor.visualColumns;
  const tmp = arr[idx];
  arr[idx] = arr[next];
  arr[next] = tmp;
}

function applyVisualToJson() {
  const cols = viewEditor.visualColumns
    .filter((c) => c.enabled && c.name.trim())
    .map((c) => {
      const obj = { ...(c.raw || {}), name: c.name.trim(), displayName: c.displayName || c.name.trim() };
      if (viewEditor.form.type === 'grid') {
        if (c.width) obj.width = c.width;
        else delete obj.width;
        obj.sortable = !!c.sortable;
      }
      return obj;
    });
  viewEditor.parsedBody.type = viewEditor.form.type;
  viewEditor.parsedBody.columns = cols;
  viewEditor.bodyText = JSON.stringify(viewEditor.parsedBody, null, 2);
}

function onViewEditorTabChange(tabName) {
  if (tabName === 'json') {
    applyVisualToJson();
  } else {
    try {
      viewEditor.parsedBody = JSON.parse(viewEditor.bodyText || '{}');
      buildVisualColumnsForView();
    } catch {
      // ignore invalid JSON while switching back
    }
  }
}

async function saveViewFromDesigner() {
  try {
    if (viewEditor.designTab === 'visual') applyVisualToJson();
    const body = JSON.parse(viewEditor.bodyText);
    if (!viewEditor.form.key && viewEditor.form.model) {
      viewEditor.form.key = viewEditor.form.model + '_' + viewEditor.form.type;
    }
    await rpcCall('meta_view', 'saveView', { view: { ...viewEditor.form, body } });
    ElMessage.success('页面布局保存成功');
    viewEditor.visible = false;
    await loadViewsAndMenus();
  } catch (e) {
    ElMessage.error(e.message);
  }
}

function openMenuEditor(row) {
  menuEditor.form = row
    ? { ...row }
    : { name: '', displayName: '', appName: appStore.businessAppsOnly[0]?.name || 'custom_app', parentId: '', model: '', view: '', filter: '', sequence: 10, active: true };
  menuEditor.visible = true;
}

async function saveMenuNode() {
  try {
    await rpcCall('meta_menu', 'saveMenu', { menu: menuEditor.form });
    ElMessage.success('菜单保存成功');
    menuEditor.visible = false;
    await appStore.refreshMenusAndOverview();
    await loadViewsAndMenus();
  } catch (e) {
    ElMessage.error(e.message);
  }
}

onMounted(() => {
  appStore.loadStudioOverview();
  loadViewsAndMenus();
});
</script>
