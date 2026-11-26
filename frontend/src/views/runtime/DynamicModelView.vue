<template>
  <div v-loading="loading">
    <!-- 顶部模型头部与视图工具 -->
    <div v-if="viewData.modelMeta" class="lc-card" style="padding:13px 18px;">
      <div style="display:flex;justify-content:space-between;align-items:center;flex-wrap:wrap;gap:10px;">
        <div>
          <h2 style="margin:0;font-size:17px;color:#0f172a;">
            {{ viewData.modelMeta?.displayName || activeModel }}
            <el-tag size="small" type="success" style="margin-left:8px;">v{{ viewData.modelMeta?.versionNo }}</el-tag>
            <el-tag v-if="viewData.auth?.create" size="small" effect="plain" style="margin-left:6px;">可录入</el-tag>
            <el-tag v-if="viewData.auth?.update" size="small" effect="plain" style="margin-left:4px;">可修改</el-tag>
            <el-tag v-if="viewData.auth?.delete" size="small" effect="plain" style="margin-left:4px;">可删除</el-tag>
          </h2>
          <div style="font-size:12px;color:#64748b;margin-top:3px;">当前租户数据独立隔离 · 支持多条件查询、表单维护与业务流转</div>
        </div>
        <div style="display:flex;gap:8px;align-items:center;">
          <el-button size="small" @click="exportRuntimeCsv">导出表格</el-button>
          <el-radio-group v-model="displayMode" size="small">
            <el-radio-button value="table">表格</el-radio-button>
            <el-radio-button value="cards">卡片</el-radio-button>
          </el-radio-group>
          <el-popover placement="bottom-end" :width="210" trigger="click">
            <template #reference><el-button size="small">显示列</el-button></template>
            <div style="font-size:12px;font-weight:600;margin-bottom:6px;">勾选列表展示字段</div>
            <el-checkbox-group v-model="visibleGridColumnNames" style="display:flex;flex-direction:column;gap:4px;">
              <el-checkbox v-for="c in runtimeGridColumns" :key="c.name" :value="c.name">
                {{ c.displayName || getFieldMeta(c.name)?.displayName || c.name }}
              </el-checkbox>
            </el-checkbox-group>
          </el-popover>
          <el-button v-if="authStore.canAccessStudio" size="small" @click="openModelDesigner">修改字段设计</el-button>
          <el-button size="small" type="primary" plain @click="refreshAll">刷新</el-button>
        </div>
      </div>
    </div>

    <!-- 搜索区 -->
    <div v-if="viewData.modelMeta && runtimeSearchColumns.length > 0" class="lc-card">
      <div class="lc-search-grid">
        <div v-for="col in runtimeSearchColumns" :key="col.name" class="lc-search-item">
          <div class="lc-search-label">{{ col.displayName || getFieldMeta(col.name)?.displayName || col.name }}</div>
          <template v-if="isFieldSelectionOrDict(col.name)">
            <el-select v-model="searchValues[col.name]" clearable size="small" style="width:100%;" placeholder="全部">
              <el-option v-for="opt in getFieldOptions(col.name)" :key="opt.value" :label="opt.label" :value="opt.value" />
            </el-select>
          </template>
          <template v-else-if="isFieldBoolean(col.name)">
            <el-select v-model="searchValues[col.name]" clearable size="small" style="width:100%;" placeholder="全部">
              <el-option :value="true" label="是" />
              <el-option :value="false" label="否" />
            </el-select>
          </template>
          <template v-else-if="isFieldManyToOne(col.name)">
            <el-select
              v-model="searchValues[col.name]"
              clearable
              filterable
              remote
              :remote-method="(kw) => fetchLookup(col.name, kw)"
              @visible-change="(vis) => { if (vis) fetchLookup(col.name, ''); }"
              size="small"
              style="width:100%;"
              placeholder="选择关联项"
            >
              <el-option v-for="opt in (lookupOptionsMap[col.name] || [])" :key="opt.value" :label="opt.label" :value="opt.value" />
            </el-select>
          </template>
          <template v-else>
            <el-input v-model="searchValues[col.name]" clearable size="small" :placeholder="'输入' + (col.displayName || col.name)" @keyup.enter="executeSearch(0)" />
          </template>
        </div>
        <div style="display:flex;gap:8px;padding-bottom:1px;">
          <el-button type="primary" size="small" @click="executeSearch(0)">查询</el-button>
          <el-button size="small" @click="resetSearch">重置</el-button>
        </div>
      </div>
    </div>

    <!-- 列表区域 -->
    <div class="lc-card">
      <div class="lc-toolbar">
        <div style="display:flex;gap:8px;flex-wrap:wrap;">
          <template v-for="btn in runtimeGridTbar" :key="btn.name">
            <el-button v-if="isButtonPermitted(btn)" :type="mapButtonType(btn.type)" size="small" @click="handleGridTbarButton(btn)">
              {{ btn.name }}
            </el-button>
          </template>
        </div>
        <div style="font-size:12px;color:#64748b;">共 {{ gridTotal }} 条数据</div>
      </div>

      <el-table v-if="displayMode === 'table'" :data="gridRows" border stripe size="small" @selection-change="onSelectionChange">
        <el-table-column type="selection" width="44" />
        <el-table-column
          v-for="col in effectiveGridColumns"
          :key="col.name"
          :prop="col.name"
          :label="col.displayName || getFieldMeta(col.name)?.displayName || col.name"
          :min-width="col.width ? undefined : 130"
          :width="col.width || undefined"
          :sortable="col.sortable || false"
        >
          <template #default="{ row }">
            <template v-if="isFieldSelectionOrDict(col.name)">
              <template v-if="Array.isArray(row[col.name])">
                <el-tag v-for="(item, i) in row[col.name]" :key="i" size="small" :type="getOptionTagColor(col.name, item)" style="margin-right:3px;">
                  {{ getOptionTagLabel(col.name, item) }}
                </el-tag>
              </template>
              <el-tag v-else size="small" :type="getOptionTagColor(col.name, row[col.name])">
                {{ getOptionTagLabel(col.name, row[col.name]) }}
              </el-tag>
            </template>
            <template v-else-if="isFieldBoolean(col.name)">
              <el-tag size="small" :type="row[col.name] ? 'success' : 'info'">{{ row[col.name] ? '是' : '否' }}</el-tag>
            </template>
            <template v-else-if="isFieldManyToOne(col.name)"><span>{{ formatM2oCell(row[col.name]) }}</span></template>
            <template v-else-if="isFieldFile(col.name)">
              <a v-if="parseFileCell(row[col.name])" :href="parseFileCell(row[col.name]).downloadUrl" target="_blank" style="color:var(--primary);text-decoration:underline;">
                {{ parseFileCell(row[col.name]).fileName }}
              </a>
              <span v-else>-</span>
            </template>
            <template v-else><span>{{ formatCellValue(row[col.name]) }}</span></template>
          </template>
        </el-table-column>

        <el-table-column v-if="runtimeRowButtons.length > 0" label="操作" min-width="225" fixed="right">
          <template #default="{ row }">
            <template v-for="btn in runtimeRowButtons" :key="btn.name">
              <el-button v-if="isButtonPermitted(btn) && isRowButtonEnabled(btn, row)" link size="small" :type="mapButtonType(btn.type)" @click="handleRowButton(btn, row)">
                {{ btn.name }}
              </el-button>
            </template>
          </template>
        </el-table-column>
      </el-table>

      <div v-else class="lc-grid-cards">
        <div v-for="row in gridRows" :key="row.id" class="lc-grid-card-item">
          <div v-for="col in effectiveGridColumns.slice(0, 5)" :key="col.name" style="font-size:12.5px;margin-bottom:4px;">
            <span style="color:#64748b;">{{ col.displayName || getFieldMeta(col.name)?.displayName || col.name }}：</span>
            <strong>
              {{
                isFieldManyToOne(col.name)
                  ? formatM2oCell(row[col.name])
                  : isFieldSelectionOrDict(col.name)
                    ? getOptionTagLabel(col.name, row[col.name])
                    : formatCellValue(row[col.name])
              }}
            </strong>
          </div>
          <div style="margin-top:8px;display:flex;gap:6px;flex-wrap:wrap;border-top:1px solid #f1f5f9;padding-top:6px;">
            <template v-for="btn in runtimeRowButtons" :key="btn.name">
              <el-button v-if="isButtonPermitted(btn) && isRowButtonEnabled(btn, row)" link size="small" :type="mapButtonType(btn.type)" @click="handleRowButton(btn, row)">
                {{ btn.name }}
              </el-button>
            </template>
          </div>
        </div>
      </div>

      <div style="margin-top:12px;display:flex;justify-content:flex-end;">
        <el-pagination
          layout="total, prev, pager, next"
          :total="gridTotal"
          :page-size="pageSize"
          :current-page="page"
          @current-change="(p) => executeSearch((p - 1) * pageSize)"
        />
      </div>
    </div>

    <RecordDetailDrawer
      v-model:visible="detailDrawer.visible"
      :model-name="activeModel"
      :record="detailDrawer.record"
      :view-data="viewData"
      @edit="switchDrawerToEdit"
    />

    <RecordFormModal
      v-model:visible="formModal.visible"
      :mode="formModal.mode"
      :model-name="activeModel"
      :record-id="formModal.recordId"
      :view-data="viewData"
      :lookup-options-map="lookupOptionsMap"
      @lookup="fetchLookup"
      @saved="onFormSaved"
    />

    <!-- 自定义服务参数表单弹窗 -->
    <el-dialog v-model="serviceParamsModal.visible" :title="serviceParamsModal.title" width="450px" destroy-on-close>
      <el-form label-width="105px" size="small">
        <el-form-item v-for="pf in serviceParamsModal.schema" :key="pf.name" :label="pf.displayName || pf.name" :required="pf.required">
          <el-select v-if="pf.dataType === 'Selection' && pf.values" v-model="serviceParamsModal.values[pf.name]" style="width:100%;">
            <el-option v-for="o in pf.values" :key="o.value" :label="o.label" :value="o.value" />
          </el-select>
          <el-input-number v-else-if="pf.dataType === 'Integer' || pf.dataType === 'Double'" v-model="serviceParamsModal.values[pf.name]" style="width:100%;" />
          <el-input v-else v-model="serviceParamsModal.values[pf.name]" :type="pf.dataType === 'Text' ? 'textarea' : 'text'" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button size="small" @click="serviceParamsModal.visible = false">取消</el-button>
        <el-button type="primary" size="small" @click="confirmServiceParamsRun">确认执行</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import { rpcCall } from '@/api/rpc';
import { useAuthStore } from '@/stores/auth';
import { useAppStore } from '@/stores/app';
import { useRuntimeFieldMeta } from '@/composables/useRuntimeFieldMeta';
import { formatM2oCell, parseFileCell, formatCellValue, mapButtonType } from '@/utils/formatters';
import RecordDetailDrawer from '@/components/runtime/RecordDetailDrawer.vue';
import RecordFormModal from '@/components/runtime/RecordFormModal.vue';

const route = useRoute();
const authStore = useAuthStore();
const appStore = useAppStore();

const activeModel = computed(() => String(route.params.model || ''));
const loading = ref(false);
const displayMode = ref('table');
const visibleGridColumnNames = ref([]);

const viewData = reactive({
  modelMeta: null,
  views: {},
  fields: {},
  dictOptions: {},
  dynamicOptions: {},
  services: [],
  auth: {}
});

const searchValues = reactive({});
const gridRows = ref([]);
const gridTotal = ref(0);
const page = ref(1);
const pageSize = ref(10);
const selectedIds = ref([]);
const lookupOptionsMap = reactive({});

const detailDrawer = reactive({ visible: false, record: null });
const formModal = reactive({ visible: false, mode: 'create', recordId: null });
const serviceParamsModal = reactive({ visible: false, title: '', btn: null, row: null, schema: [], values: {} });

const runtimeSearchColumns = computed(() => viewData.views?.search?.body?.columns || []);
const runtimeGridColumns = computed(() => viewData.views?.grid?.body?.columns || []);
const effectiveGridColumns = computed(() =>
  !visibleGridColumnNames.value.length
    ? runtimeGridColumns.value
    : runtimeGridColumns.value.filter((c) => visibleGridColumnNames.value.includes(c.name))
);
const runtimeGridTbar = computed(() => viewData.views?.grid?.body?.tbar || []);
const runtimeRowButtons = computed(() => viewData.views?.grid?.body?.buttons || []);

const {
  getFieldMeta,
  getFieldDataType,
  isFieldSelectionOrDict,
  isFieldBoolean,
  isFieldManyToOne,
  isFieldFile,
  getFieldOptions,
  getOptionTagLabel,
  getOptionTagColor
} = useRuntimeFieldMeta(() => viewData);

function isButtonPermitted(btn) {
  const auth = viewData.auth || {};
  if (btn.action === 'create') return auth.create !== false;
  if (btn.action === 'edit') return auth.update !== false;
  if (btn.action === 'delete') return auth.delete !== false;
  if (btn.action === 'preview') return auth.read !== false;
  if (btn.service && auth.services) {
    const key = btn.auth || btn.service;
    if (auth.services[key] === false || auth.services[btn.service] === false) return false;
  }
  return true;
}

function isRowButtonEnabled(btn, row) {
  if (!btn.enableCondition) return true;
  try {
    return Boolean(new Function('row', 'return Boolean(' + btn.enableCondition + ');')(row));
  } catch {
    return true;
  }
}

function onSelectionChange(rows) {
  selectedIds.value = (rows || []).map((r) => r.id);
}

async function fetchLookup(fieldName, keyword) {
  try {
    lookupOptionsMap[fieldName] =
      (await rpcCall(activeModel.value, 'lookup', { field: fieldName, keyword: keyword || '', limit: 30 })) || [];
  } catch {
    // ignore
  }
}

function buildPolishFilterDsl() {
  const conds = [];
  for (const col of runtimeSearchColumns.value) {
    const v = searchValues[col.name];
    if (v === undefined || v === null || v === '') continue;
    const dt = getFieldDataType(col.name);
    if (dt === 'string' || dt === 'text') conds.push([col.name, 'like', String(v)]);
    else conds.push([col.name, '=', v]);
  }
  if (conds.length === 0) return [];
  if (conds.length === 1) return conds;
  return ['&', ...conds];
}

async function executeSearch(offset = 0) {
  if (!viewData.modelMeta) return;
  page.value = Math.floor(offset / pageSize.value) + 1;
  try {
    const res = await rpcCall(activeModel.value, 'search', {
      filter: buildPolishFilterDsl(),
      limit: pageSize.value,
      offset,
      useDisplayForModel: true
    });
    gridRows.value = res.records || [];
    gridTotal.value = res.total || 0;
  } catch (e) {
    ElMessage.error('查询失败：' + e.message);
  }
}

function resetSearch() {
  Object.keys(searchValues).forEach((k) => delete searchValues[k]);
  executeSearch(0);
}

async function refreshAll() {
  if (!activeModel.value) return;
  loading.value = true;
  try {
    const data = await rpcCall('ui_view', 'loadView', { model: activeModel.value, type: '' });
    viewData.modelMeta = data.modelMeta;
    viewData.views = data.views || {};
    viewData.fields = data.fields || {};
    viewData.dictOptions = data.dictOptions || {};
    viewData.dynamicOptions = data.dynamicOptions || {};
    viewData.services = data.services || [];
    viewData.auth = data.auth || {};
    visibleGridColumnNames.value = (data.views?.grid?.body?.columns || []).map((c) => c.name);
    appStore.ensureTab('m:' + activeModel.value, data.modelMeta?.displayName || activeModel.value, 'runtime', activeModel.value);
    await executeSearch(0);
  } catch (e) {
    viewData.modelMeta = null;
    viewData.views = {};
    viewData.fields = {};
    gridRows.value = [];
    gridTotal.value = 0;
    ElMessage.error('无法打开页面：' + e.message);
  } finally {
    loading.value = false;
  }
}

function openModelDesigner() {
  appStore.designerDialogState.isEdit = true;
  appStore.designerDialogState.modelToOpen = activeModel.value;
  appStore.designerDialogState.visible = true;
}

function exportRuntimeCsv() {
  const cols = effectiveGridColumns.value;
  const escapeCsv = (s) => '"' + String(s ?? '').replace(/"/g, '""') + '"';
  const header = cols.map((c) => escapeCsv(c.displayName || getFieldMeta(c.name)?.displayName || c.name)).join(',');
  const lines = gridRows.value.map((r) =>
    cols
      .map((c) => {
        const val = r[c.name];
        if (isFieldSelectionOrDict(c.name)) {
          return escapeCsv(
            Array.isArray(val) ? val.map((x) => getOptionTagLabel(c.name, x)).join(';') : getOptionTagLabel(c.name, val)
          );
        }
        if (isFieldManyToOne(c.name)) return escapeCsv(formatM2oCell(val));
        if (isFieldFile(c.name)) return escapeCsv(parseFileCell(val)?.fileName || '');
        return escapeCsv(formatCellValue(val));
      })
      .join(',')
  );
  const csv = '\uFEFF' + [header, ...lines].join('\r\n');
  const blob = new Blob([csv], { type: 'text/csv;charset=utf-8;' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = (viewData.modelMeta?.displayName || activeModel.value) + '.csv';
  a.click();
  URL.revokeObjectURL(url);
}

async function handleServiceUiResult(res, defaultMsg, actionAfter) {
  if (res && res._uiAction) {
    ElMessage({ type: res.level || 'success', message: res.message || defaultMsg });
    if (res._uiAction === 'reloadWindow') {
      await refreshAll();
      return;
    }
  } else {
    ElMessage.success(defaultMsg);
  }
  if (!actionAfter || actionAfter === 'refreshTable') {
    await executeSearch((page.value - 1) * pageSize.value);
  }
}

async function executeButtonRpc(btn, row, extraParams) {
  try {
    if (btn.confirm) await ElMessageBox.confirm(btn.confirm, btn.name, { type: 'warning' });
    const resolvedArgs = {};
    for (const [k, v] of Object.entries(btn.args || {})) {
      resolvedArgs[k] = v === '$row.id' ? row.id : v === '$selectedIds' ? selectedIds.value : v;
    }
    Object.assign(resolvedArgs, extraParams || {});
    const res = await rpcCall(activeModel.value, btn.service, resolvedArgs);
    await handleServiceUiResult(res, '操作 [' + btn.name + '] 成功', btn.actionAfter);
  } catch (e) {
    if (e !== 'cancel') ElMessage.error(e.message);
  }
}

async function invokeConfiguredButton(btn, row) {
  if (btn.paramsForm && btn.paramsForm.length > 0) {
    serviceParamsModal.title = btn.name;
    serviceParamsModal.btn = btn;
    serviceParamsModal.row = row;
    serviceParamsModal.schema = btn.paramsForm;
    serviceParamsModal.values = {};
    serviceParamsModal.visible = true;
    return;
  }
  await executeButtonRpc(btn, row, {});
}

async function confirmServiceParamsRun() {
  serviceParamsModal.visible = false;
  await executeButtonRpc(serviceParamsModal.btn, serviceParamsModal.row, serviceParamsModal.values);
}

async function handleGridTbarButton(btn) {
  if (btn.action === 'create') {
    formModal.mode = 'create';
    formModal.recordId = null;
    formModal.visible = true;
  } else if (btn.action === 'delete') {
    if (!selectedIds.value.length) {
      ElMessage.warning('请先勾选至少一条记录');
      return;
    }
    try {
      if (btn.confirm) await ElMessageBox.confirm(btn.confirm, '删除确认', { type: 'warning' });
      await rpcCall(activeModel.value, 'delete', { ids: selectedIds.value });
      ElMessage.success('删除成功');
      await executeSearch(0);
    } catch (e) {
      if (e !== 'cancel') ElMessage.error(e.message);
    }
  } else if (btn.service) {
    await invokeConfiguredButton(btn, {});
  }
}

async function handleRowButton(btn, row) {
  if (btn.action === 'preview') {
    try {
      detailDrawer.record = await rpcCall(activeModel.value, 'find', { id: row.id });
      detailDrawer.visible = true;
    } catch (e) {
      ElMessage.error(e.message);
    }
  } else if (btn.action === 'edit') {
    formModal.mode = 'edit';
    formModal.recordId = row.id;
    formModal.visible = true;
  } else if (btn.action === 'delete') {
    try {
      await ElMessageBox.confirm(btn.confirm || '确认删除该记录？', '确认', { type: 'warning' });
      await rpcCall(activeModel.value, 'delete', { id: row.id });
      ElMessage.success('已删除');
      await executeSearch((page.value - 1) * pageSize.value);
    } catch (e) {
      if (e !== 'cancel') ElMessage.error(e.message);
    }
  } else if (btn.service) {
    await invokeConfiguredButton(btn, row);
  }
}

function switchDrawerToEdit(rec) {
  detailDrawer.visible = false;
  if (rec?.id) {
    formModal.mode = 'edit';
    formModal.recordId = rec.id;
    formModal.visible = true;
  }
}

async function onFormSaved({ resetPage }) {
  await executeSearch(resetPage ? 0 : (page.value - 1) * pageSize.value);
}

watch(
  activeModel,
  (m) => {
    Object.keys(searchValues).forEach((k) => delete searchValues[k]);
    selectedIds.value = [];
    if (m) refreshAll();
  },
  { immediate: true }
);
</script>
