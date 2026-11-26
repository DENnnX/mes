<template>
  <div v-loading="loading">
    <!-- 顶部模型头部与视图工具 -->
    <div v-if="viewData.modelMeta" class="lc-card" style="padding:13px 18px;">
      <div style="display:flex;justify-content:space-between;align-items:center;flex-wrap:wrap;gap:10px;">
        <div>
          <h2 style="margin:0;font-size:17px;color:#0f172a;">
            {{ pageTitle }}
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
        <div
          v-for="col in runtimeSearchColumns"
          :key="col.name"
          class="lc-search-item"
          :class="{ 'lc-search-range': isFieldDate(col.name) }"
        >
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
          <template v-else-if="isFieldDate(col.name)">
            <el-date-picker
              v-model="searchValues[col.name]"
              :type="getFieldDataType(col.name) === 'datetime' ? 'datetimerange' : 'daterange'"
              :value-format="getFieldDataType(col.name) === 'datetime' ? 'YYYY-MM-DD HH:mm:ss' : 'YYYY-MM-DD'"
              range-separator="至"
              start-placeholder="开始"
              end-placeholder="结束"
              size="small"
              style="width:100%;"
            />
          </template>
          <template v-else-if="isFieldNumber(col.name)">
            <el-input-number v-model="searchValues[col.name]" size="small" style="width:100%;" />
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
              <el-button v-if="parseFileCell(row[col.name])" link type="primary" @click="handleDownloadFile(row[col.name])">
                {{ parseFileCell(row[col.name]).fileName }}
              </el-button>
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
      <el-form label-width="105px" size="small" @submit.prevent>
        <el-form-item v-for="pf in serviceParamsModal.schema" :key="pf.name" :label="pf.displayName || pf.name" :required="pf.required">
          <el-select v-if="pf.dataType === 'Selection' && pf.values" v-model="serviceParamsModal.values[pf.name]" style="width:100%;">
            <el-option v-for="o in pf.values" :key="o.value" :label="o.label" :value="o.value" />
          </el-select>
          <el-input-number
            v-else-if="pf.dataType === 'Integer' || pf.dataType === 'Double'"
            v-model="serviceParamsModal.values[pf.name]"
            :min="pf.minValue ?? -Infinity"
            :max="pf.maxValue ?? Infinity"
            :precision="pf.dataType === 'Integer' ? 0 : undefined"
            style="width:100%;"
          />
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
import { useRoute, useRouter } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import { rpcCall, downloadFile } from '@/api/rpc';
import { useAuthStore } from '@/stores/auth';
import { useAppStore } from '@/stores/app';
import { useRuntimeFieldMeta } from '@/composables/useRuntimeFieldMeta';
import { formatM2oCell, parseFileCell, formatCellValue, mapButtonType, evalEnableCondition } from '@/utils/formatters';
import RecordDetailDrawer from '@/components/runtime/RecordDetailDrawer.vue';
import RecordFormModal from '@/components/runtime/RecordFormModal.vue';

const route = useRoute();
const router = useRouter();
const authStore = useAuthStore();
const appStore = useAppStore();

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

let runtimeRevision = 0;

const activeMenu = computed(() =>
  route.name === 'runtime-menu' ? decodeURIComponent(String(route.params.menuName || '')) : ''
);
const matchedMenuNode = computed(() =>
  activeMenu.value ? appStore.findMenuNode(activeMenu.value, route.path) : null
);
const activeModel = computed(
  () =>
    matchedMenuNode.value?.model
    || (route.name === 'runtime-model' ? decodeURIComponent(String(route.params.model || '')) : viewData.modelMeta?.name || '')
);
const pageTitle = computed(
  () => matchedMenuNode.value?.displayName || viewData.modelMeta?.displayName || activeModel.value
);

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
  isFieldNumber,
  isFieldManyToOne,
  isFieldDate,
  isFieldFile,
  getFieldOptions,
  getOptionTagLabel,
  getOptionTagColor
} = useRuntimeFieldMeta(() => viewData);

function isButtonPermitted(btn) {
  const auth = viewData.auth || {};
  if (btn.service && btn.model && btn.model !== activeModel.value) {
    return auth.targetServices?.[btn.model]?.[btn.service] === true;
  }
  if (btn.action === 'create') return auth.create !== false;
  if (btn.action === 'edit') return auth.update !== false;
  if (btn.action === 'delete') return auth.delete !== false;
  if (btn.action === 'preview') return auth.read !== false;
  if (btn.service && auth.services) {
    return (auth.services[btn.service] ?? auth.services[btn.auth]) !== false;
  }
  return true;
}

function isRowButtonEnabled(btn, row) {
  return evalEnableCondition(btn.enableCondition, row);
}

function onSelectionChange(rows) {
  selectedIds.value = (rows || []).map((r) => r.id);
}

async function fetchLookup(fieldName, keyword, model = activeModel.value, optionsKey = fieldName) {
  const revision = runtimeRevision;
  if (!model) return;
  try {
    const options = (await rpcCall(model, 'lookup', { field: fieldName, keyword: keyword || '', limit: 30 })) || [];
    if (revision === runtimeRevision) {
      lookupOptionsMap[optionsKey] = options;
    }
  } catch {
    // ignore
  }
}

async function handleDownloadFile(val) {
  try {
    await downloadFile(val);
  } catch (e) {
    ElMessage.error(e.message);
  }
}

function buildPolishFilterDsl() {
  const conds = [];
  for (const col of runtimeSearchColumns.value) {
    const v = searchValues[col.name];
    if (v === undefined || v === null || v === '') continue;
    const dt = getFieldDataType(col.name);
    if (isFieldDate(col.name)) {
      if (Array.isArray(v) && v.length === 2 && v[0] && v[1]) {
        conds.push([col.name, '>=', v[0]], [col.name, '<=', v[1]]);
      }
    } else if (dt === 'string' || dt === 'text') {
      conds.push([col.name, 'like', String(v)]);
    } else {
      conds.push([col.name, '=', v]);
    }
  }
  if (conds.length === 0) return [];
  if (conds.length === 1) return conds;
  return ['&', ...conds];
}

async function executeSearch(offset = 0) {
  if (!viewData.modelMeta) return;
  const revision = runtimeRevision;
  page.value = Math.floor(offset / pageSize.value) + 1;
  try {
    const args = {
      filter: buildPolishFilterDsl(),
      limit: pageSize.value,
      offset,
      useDisplayForModel: true
    };
    if (activeMenu.value) args.menu = activeMenu.value;
    const res = await rpcCall(activeModel.value, 'search', args);
    if (revision !== runtimeRevision) return;
    gridRows.value = res.records || [];
    gridTotal.value = res.total || 0;
  } catch (e) {
    if (revision === runtimeRevision) {
      ElMessage.error('查询失败：' + e.message);
    }
  }
}

function resetSearch() {
  Object.keys(searchValues).forEach((k) => delete searchValues[k]);
  executeSearch(0);
}

function resetLocalRuntimeState() {
  runtimeRevision++;
  loading.value = false;
  Object.assign(viewData, {
    modelMeta: null,
    views: {},
    fields: {},
    dictOptions: {},
    dynamicOptions: {},
    services: [],
    auth: {}
  });
  gridRows.value = [];
  gridTotal.value = 0;
  selectedIds.value = [];
  Object.keys(searchValues).forEach((k) => delete searchValues[k]);
  Object.keys(lookupOptionsMap).forEach((k) => delete lookupOptionsMap[k]);
  detailDrawer.visible = false;
  detailDrawer.record = null;
  formModal.visible = false;
  serviceParamsModal.visible = false;
}

async function refreshAll() {
  const targetModel = activeModel.value;
  const targetMenu = activeMenu.value;
  const targetPath = route.path;
  if (!targetModel && !targetMenu) return;

  const revision = ++runtimeRevision;
  loading.value = true;
  try {
    const args = { model: targetModel };
    if (targetMenu) args.menu = targetMenu;
    const data = await rpcCall('ui_view', 'loadView', args);
    if (revision !== runtimeRevision) return;
    viewData.modelMeta = data.modelMeta;
    viewData.views = data.views || {};
    viewData.fields = data.fields || {};
    viewData.dictOptions = data.dictOptions || {};
    viewData.dynamicOptions = data.dynamicOptions || {};
    viewData.services = data.services || [];
    viewData.auth = data.auth || {};
    visibleGridColumnNames.value = (data.views?.grid?.body?.columns || []).map((c) => c.name);
    appStore.ensureTab(
      targetPath,
      matchedMenuNode.value?.displayName || data.modelMeta?.displayName || targetModel,
      'runtime',
      targetPath
    );
    await executeSearch(0);
  } catch (e) {
    if (revision !== runtimeRevision) return;
    ElMessage.error('无法打开页面：' + e.message);
    await router.replace('/runtime');
  } finally {
    if (revision === runtimeRevision) {
      loading.value = false;
    }
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
  a.download = (pageTitle.value || activeModel.value) + '.csv';
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
  const revision = runtimeRevision;
  const targetModel = btn.model || activeModel.value;
  try {
    if (btn.confirm) await ElMessageBox.confirm(btn.confirm, btn.name, { type: 'warning' });
    if (revision !== runtimeRevision) return;
    const resolvedArgs = {};
    for (const [key, value] of Object.entries(btn.args || {})) {
      if (value === '$selectedIds') {
        resolvedArgs[key] = selectedIds.value;
      } else if (typeof value === 'string' && value.startsWith('$row.')) {
        const field = value
          .slice(5)
          .split('.')
          .reduce((current, part) => current?.[part], row);
        if (field == null) {
          throw new Error('当前记录缺少按钮参数 ' + value);
        }
        resolvedArgs[key] = field?.id ?? field;
      } else {
        resolvedArgs[key] = value;
      }
    }
    Object.assign(resolvedArgs, extraParams || {});
    const res = await rpcCall(targetModel, btn.service, resolvedArgs);
    if (revision === runtimeRevision) {
      await handleServiceUiResult(res, '操作 [' + btn.name + '] 成功', btn.actionAfter);
    }
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
  const missing = serviceParamsModal.schema.find(
    (field) => field.required && String(serviceParamsModal.values[field.name] ?? '').trim() === ''
  );
  if (missing) {
    ElMessage.warning('请填写' + (missing.displayName || missing.name));
    return;
  }
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
  () => route.path,
  () => {
    resetLocalRuntimeState();
    if (activeModel.value || activeMenu.value) refreshAll();
  },
  { immediate: true }
);
</script>
