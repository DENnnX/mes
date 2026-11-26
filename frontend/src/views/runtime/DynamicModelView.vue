<template>
  <div v-loading="loading" class="runtime-page">
    <!-- 顶部模型头部与视图工具 -->
    <header v-if="viewData.modelMeta" class="runtime-heading">
      <h1>{{ pageTitle }}</h1>
      <div class="runtime-heading-tools">
        <el-button size="small" :icon="Download" @click="exportRuntimeCsv">导出表格</el-button>
        <el-button v-if="authStore.canAccessStudio" size="small" @click="openModelDesigner">字段设计</el-button>
        <el-button size="small" :icon="Refresh" @click="refreshAll">刷新</el-button>
      </div>
    </header>

    <section v-if="viewData.modelMeta" class="runtime-results" :aria-label="pageTitle + '记录'">
      <!-- 搜索区 -->
      <form v-if="runtimeSearchColumns.length > 0" class="runtime-filters" aria-label="筛选记录" @submit.prevent="executeSearch(0)">
        <div class="lc-search-grid">
          <div
            v-for="col in runtimeSearchColumns"
            :key="col.name"
            class="lc-search-item"
            :class="{ 'lc-search-range': isFieldDate(col.name) }"
          >
            <div class="lc-search-label">{{ fieldLabel(col) }}</div>
            <template v-if="isFieldSelectionOrDict(col.name)">
              <el-select v-model="searchValues[col.name]" :aria-label="fieldLabel(col)" clearable style="width:100%;" placeholder="全部">
                <el-option v-for="opt in getFieldOptions(col.name)" :key="opt.value" :label="opt.label" :value="opt.value" />
              </el-select>
            </template>
            <template v-else-if="isFieldBoolean(col.name)">
              <el-select v-model="searchValues[col.name]" :aria-label="fieldLabel(col)" clearable style="width:100%;" placeholder="全部">
                <el-option :value="true" label="是" />
                <el-option :value="false" label="否" />
              </el-select>
            </template>
            <template v-else-if="isFieldManyToOne(col.name)">
              <el-select
                v-model="searchValues[col.name]"
                :aria-label="fieldLabel(col)"
                clearable
                filterable
                remote
                :remote-method="(kw) => fetchLookup(col.name, kw)"
                @visible-change="(vis) => { if (vis) fetchLookup(col.name, ''); }"
                style="width:100%;"
                placeholder="选择关联项"
              >
                <el-option v-for="opt in (lookupOptionsMap[col.name] || [])" :key="opt.value" :label="opt.label" :value="opt.value" />
              </el-select>
            </template>
            <template v-else-if="isFieldDate(col.name)">
              <el-date-picker
                v-model="searchValues[col.name]"
                :aria-label="fieldLabel(col)"
                :type="getFieldDataType(col.name) === 'datetime' ? 'datetimerange' : 'daterange'"
                :value-format="getFieldDataType(col.name) === 'datetime' ? 'YYYY-MM-DD HH:mm:ss' : 'YYYY-MM-DD'"
                range-separator="至"
                start-placeholder="开始"
                end-placeholder="结束"
                style="width:100%;"
              />
            </template>
            <template v-else-if="isFieldNumber(col.name)">
              <el-input-number v-model="searchValues[col.name]" :aria-label="fieldLabel(col)" style="width:100%;" />
            </template>
            <template v-else>
              <el-input v-model="searchValues[col.name]" :aria-label="fieldLabel(col)" clearable :placeholder="'输入' + fieldLabel(col)" />
            </template>
          </div>
          <div class="runtime-filter-actions">
            <el-button type="primary" native-type="submit">查询</el-button>
            <el-button @click="resetSearch">重置</el-button>
          </div>
        </div>
      </form>

      <!-- 列表区域 -->
      <div class="runtime-grid-toolbar">
        <div class="runtime-grid-summary">
          <span aria-live="polite">共 <strong>{{ gridTotal }}</strong> 条记录</span>
          <span v-if="selectedIds.length">已选 {{ selectedIds.length }} 条</span>
          <template v-for="btn in runtimeGridTbar" :key="btn.name">
            <el-button v-if="isButtonPermitted(btn)" :type="mapButtonType(btn.type)" size="small" @click="handleGridTbarButton(btn)">
              {{ btn.name }}
            </el-button>
          </template>
        </div>
        <div class="runtime-grid-tools">
          <el-radio-group v-model="displayMode" size="small" aria-label="记录视图">
            <el-radio-button value="table">表格</el-radio-button>
            <el-radio-button value="cards">卡片</el-radio-button>
          </el-radio-group>
          <el-popover placement="bottom-end" :width="210" trigger="click">
            <template #reference><el-button size="small" :icon="SetUp">显示列</el-button></template>
            <el-checkbox-group v-model="visibleGridColumnNames" class="runtime-columns" aria-label="列表显示字段">
              <el-checkbox v-for="c in runtimeGridColumns" :key="c.name" :value="c.name">
                {{ fieldLabel(c) }}
              </el-checkbox>
            </el-checkbox-group>
          </el-popover>
        </div>
      </div>

      <el-table
        v-if="displayMode === 'table'"
        class="runtime-table"
        :data="gridRows"
        :empty-text="loading ? '正在加载…' : '暂无匹配的记录'"
        stripe
        @selection-change="onSelectionChange"
      >
        <el-table-column type="selection" width="44" />
        <el-table-column
          v-for="(col, index) in effectiveGridColumns"
          :key="col.name"
          :prop="col.name"
          :label="fieldLabel(col)"
          :align="isFieldNumber(col.name) ? 'right' : 'left'"
          :class-name="isFieldNumber(col.name) ? 'runtime-table-number' : index === 0 ? 'runtime-table-identifier' : undefined"
          :min-width="col.width ? undefined : 112"
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
              <el-tag v-else-if="row[col.name] != null && row[col.name] !== ''" class="runtime-status" size="small" :type="getOptionTagColor(col.name, row[col.name])">
                {{ getOptionTagLabel(col.name, row[col.name]) }}
              </el-tag>
              <span v-else class="runtime-missing">—</span>
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
            <span v-else-if="row[col.name] == null || row[col.name] === ''" class="runtime-missing">—</span>
            <span v-else>{{ formatCellValue(row[col.name]) }}</span>
          </template>
        </el-table-column>

        <el-table-column v-if="runtimeRowButtons.length > 0" label="操作" min-width="208" fixed="right">
          <template #default="{ row }">
            <div class="runtime-row-actions">
              <template v-for="btn in runtimeRowButtons" :key="btn.name">
                <el-button v-if="isButtonPermitted(btn) && isRowButtonEnabled(btn, row)" link size="small" :type="mapButtonType(btn.type)" @click="handleRowButton(btn, row)">
                  {{ btn.name }}
                </el-button>
              </template>
            </div>
          </template>
        </el-table-column>
      </el-table>

      <div v-else-if="gridRows.length" class="lc-grid-cards runtime-grid-cards">
        <div v-for="row in gridRows" :key="row.id" class="lc-grid-card-item">
          <dl class="runtime-card-fields">
            <div v-for="col in cardGridColumns" :key="col.name">
              <dt>{{ fieldLabel(col) }}</dt>
              <dd>
                <el-tag
                  v-if="isFieldSelectionOrDict(col.name) && row[col.name] != null && !Array.isArray(row[col.name])"
                  class="runtime-status"
                  size="small"
                  :type="getOptionTagColor(col.name, row[col.name])"
                >
                  {{ getOptionTagLabel(col.name, row[col.name]) }}
                </el-tag>
                <template v-else>{{ formatCardField(row, col) }}</template>
              </dd>
            </div>
          </dl>
          <div v-if="runtimeRowButtons.length" class="runtime-row-actions runtime-card-actions">
            <template v-for="btn in runtimeRowButtons" :key="btn.name">
              <el-button v-if="isButtonPermitted(btn) && isRowButtonEnabled(btn, row)" link size="small" :type="mapButtonType(btn.type)" @click="handleRowButton(btn, row)">
                {{ btn.name }}
              </el-button>
            </template>
          </div>
        </div>
      </div>
      <div v-else class="runtime-empty">{{ loading ? '正在加载…' : '暂无匹配的记录' }}</div>

      <div v-if="gridTotal > pageSize" class="runtime-pagination">
        <el-pagination
          layout="prev, pager, next"
          :total="gridTotal"
          :page-size="pageSize"
          :current-page="page"
          @current-change="(p) => executeSearch((p - 1) * pageSize)"
        />
      </div>
    </section>

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
    <el-dialog v-model="serviceParamsModal.visible" :title="serviceParamsModal.title" width="min(450px, calc(100vw - 24px))" destroy-on-close>
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
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Download, Refresh, SetUp } from '@element-plus/icons-vue';
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
const compactViewport = window.matchMedia('(max-width: 780px)');
const useCompactLayout = ref(compactViewport.matches);
const selectedDisplayMode = ref('');
const displayMode = computed({
  get: () => selectedDisplayMode.value || (useCompactLayout.value ? 'cards' : 'table'),
  set: (mode) => { selectedDisplayMode.value = mode; }
});
const visibleGridColumnNames = ref([]);

function updateCompactLayout(event) {
  useCompactLayout.value = event.matches;
}

onMounted(() => compactViewport.addEventListener('change', updateCompactLayout));
onUnmounted(() => compactViewport.removeEventListener('change', updateCompactLayout));

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

const cardGridColumns = computed(() => {
  const columns = effectiveGridColumns.value;
  const previewColumns = columns.slice(0, 5);
  const stateColumn = columns.find((col) => getFieldDataType(col.name) === 'selection');
  if (stateColumn && !previewColumns.includes(stateColumn)) previewColumns.push(stateColumn);
  return previewColumns;
});

function fieldLabel(column) {
  return column.displayName || getFieldMeta(column.name)?.displayName || column.name;
}

function formatCardField(row, column) {
  const value = row[column.name];
  if (value == null || value === '') return '—';
  if (isFieldManyToOne(column.name)) return formatM2oCell(value);
  if (isFieldSelectionOrDict(column.name) && Array.isArray(value)) {
    return value.map((option) => getOptionTagLabel(column.name, option)).join('、');
  }
  return formatCellValue(value);
}

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

<style scoped>
.runtime-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px 16px;
  flex-wrap: wrap;
  margin-bottom: 16px;
}
.runtime-heading h1 {
  margin: 0;
  color: var(--text-main);
  font-size: 24px;
  font-weight: 650;
  line-height: 1.25;
}
.runtime-heading-tools,
.runtime-filter-actions,
.runtime-grid-summary,
.runtime-grid-tools,
.runtime-row-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px 8px;
}
.runtime-heading-tools :deep(.el-button + .el-button),
.runtime-filter-actions :deep(.el-button + .el-button),
.runtime-row-actions :deep(.el-button + .el-button) {
  margin-left: 0;
}
.runtime-results {
  min-width: 0;
  border: 1px solid var(--border-light);
  border-radius: var(--radius-panel);
  background: var(--bg-card);
}
.runtime-filters {
  padding: 16px;
  border-bottom: 1px solid var(--border-light);
}
.runtime-filters .lc-search-item {
  flex: 0 1 176px;
  min-width: 128px;
}
.runtime-filters .lc-search-range {
  flex: 0 1 276px;
  min-width: 240px;
}
.runtime-filters .lc-search-label {
  color: var(--el-text-color-regular);
  font-weight: 600;
}
.runtime-grid-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px 16px;
  flex-wrap: wrap;
  min-height: 49px;
  padding: 8px 16px;
}
.runtime-grid-summary {
  gap: 8px 14px;
  color: var(--text-muted);
  font-size: 13px;
  font-variant-numeric: tabular-nums;
}
.runtime-grid-summary strong { color: var(--text-main); }
.runtime-columns { display: flex; flex-direction: column; gap: 4px; }
.runtime-table {
  --el-table-border-color: var(--border-light);
  --el-table-header-bg-color: var(--bg-muted);
  --el-table-header-text-color: var(--text-muted);
  --el-table-row-hover-bg-color: var(--primary-soft);
  font-size: 13px;
}
.runtime-table :deep(th.el-table__cell) { padding: 8px 0; font-weight: 600; }
.runtime-table :deep(td.el-table__cell) { padding: 10px 0; }
.runtime-table :deep(.cell) { padding-inline: 12px; }
.runtime-table :deep(.runtime-table-number .cell) { font-variant-numeric: tabular-nums; }
.runtime-table :deep(td.runtime-table-identifier .cell) { font-weight: 650; }
.runtime-status { border-radius: var(--radius-control); font-weight: 600; }
.runtime-missing { color: var(--text-muted); }
.runtime-row-actions :deep(.el-button) { min-height: 24px; padding: 3px 0; }
.runtime-grid-cards { padding: 0 16px 16px; }
.runtime-card-fields {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px 14px;
  margin: 0;
}
.runtime-card-fields dt { margin-bottom: 3px; color: var(--text-muted); font-size: 12px; }
.runtime-card-fields dd { margin: 0; color: var(--text-main); font-size: 13px; font-weight: 600; overflow-wrap: anywhere; font-variant-numeric: tabular-nums; }
.runtime-card-actions { margin-top: 10px; padding-top: 6px; border-top: 1px solid var(--border-light); }
.runtime-card-actions:empty { display: none; }
.runtime-empty { padding: 26px 16px; text-align: center; color: var(--text-muted); }
.runtime-pagination { display: flex; justify-content: flex-end; padding: 8px 16px; }

@media (max-width: 780px) {
  .runtime-heading h1 { font-size: 21px; }
  .runtime-filters { padding: 12px; }
  .runtime-filters .lc-search-grid {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    align-items: end;
  }
  .runtime-filters .lc-search-item { min-width: 0; }
  .runtime-filters .lc-search-range { grid-column: 1 / -1; }
  .runtime-grid-toolbar { padding-inline: 12px; }
  .runtime-table :deep(.el-table-fixed-column--right) { position: static !important; }
  .runtime-table :deep(.el-table-fixed-column--right::before) { display: none; }
  .runtime-grid-cards { grid-template-columns: repeat(auto-fit, minmax(min(250px, 100%), 1fr)); }
}
</style>
