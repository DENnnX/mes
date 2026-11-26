<template>
  <el-drawer
    :model-value="visible"
    :title="'单据详情 - ' + (viewData.modelMeta?.displayName || modelName)"
    size="620px"
    destroy-on-close
    @update:model-value="(val) => emit('update:visible', val)"
  >
    <div v-if="record">
      <details v-for="group in runtimeFormGroups" :key="group.key" class="lc-form-group" open>
        <summary>{{ group.title || '基础信息' }}</summary>
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item
            v-for="col in group.columns"
            :key="col.name"
            :label="col.displayName || getFieldMeta(col.name)?.displayName || col.name"
            :span="isWideField(col.name) ? 2 : 1"
          >
            <template v-if="isFieldSelectionOrDict(col.name)">
              <template v-if="Array.isArray(record[col.name])">
                <el-tag
                  v-for="(it, idx) in record[col.name]"
                  :key="idx"
                  size="small"
                  :type="getOptionTagColor(col.name, it)"
                  style="margin-right:3px;"
                >
                  {{ getOptionTagLabel(col.name, it) }}
                </el-tag>
              </template>
              <el-tag v-else size="small" :type="getOptionTagColor(col.name, record[col.name])">
                {{ getOptionTagLabel(col.name, record[col.name]) }}
              </el-tag>
            </template>
            <template v-else-if="isFieldBoolean(col.name)">
              <el-tag size="small" :type="record[col.name] ? 'success' : 'info'">{{ record[col.name] ? '是' : '否' }}</el-tag>
            </template>
            <template v-else-if="isFieldManyToOne(col.name)">{{ formatM2oCell(record[col.name]) || '-' }}</template>
            <template v-else-if="isFieldFile(col.name)">
              <el-button
                v-if="parseFileCell(record[col.name])"
                link
                type="primary"
                @click="handleDownloadFile(record[col.name])"
              >
                下载：{{ parseFileCell(record[col.name]).fileName }}
              </el-button>
              <span v-else>-</span>
            </template>
            <template v-else>{{ formatCellValue(record[col.name]) }}</template>
          </el-descriptions-item>
        </el-descriptions>
      </details>

      <el-descriptions :column="2" border size="small" title="系统留痕" style="margin-top:16px;">
        <el-descriptions-item label="创建人">{{ record.create_user || '-' }}</el-descriptions-item>
        <el-descriptions-item label="创建时间">{{ record.create_date || '-' }}</el-descriptions-item>
        <el-descriptions-item label="最后更新">{{ record.update_date || '-' }}</el-descriptions-item>
        <el-descriptions-item label="数据版本">v{{ record.version || 1 }}</el-descriptions-item>
      </el-descriptions>

      <el-tabs v-if="formTabs.length > 0" style="margin-top:16px;">
        <el-tab-pane v-for="tab in formTabs" :key="tab.header" :label="tab.header">
          <template v-if="tab.body?.type === 'manytomany'">
            <el-tag v-for="it in (record[tab.body.field] || [])" :key="it.id || it" size="small" style="margin:0 6px 6px 0;">
              {{ it.displayName || it.id || it }}
            </el-tag>
            <span v-if="!(record[tab.body.field] || []).length" style="color:#94a3b8;font-size:12px;">暂无关联记录</span>
          </template>
          <el-table v-else :data="record[tab.body.field] || []" border size="small">
            <el-table-column v-for="cc in (tab.body.columns || [])" :key="cc.name" :label="cc.displayName || cc.name">
              <template #default="{ row }">
                {{
                  cc.values?.find((option) => option.value === row[cc.name])?.label
                    || (row[cc.name] && typeof row[cc.name] === 'object' ? formatM2oCell(row[cc.name]) : formatCellValue(row[cc.name]))
                }}
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </div>
    <template #footer>
      <el-button size="small" @click="emit('update:visible', false)">关闭</el-button>
      <el-button v-if="canEditRecord" type="primary" size="small" @click="emit('edit', record)">编辑此记录</el-button>
    </template>
  </el-drawer>
</template>

<script setup>
import { computed } from 'vue';
import { ElMessage } from 'element-plus';
import { downloadFile } from '@/api/rpc';
import { useRuntimeFieldMeta } from '@/composables/useRuntimeFieldMeta';
import { formatM2oCell, parseFileCell, formatCellValue, evalEnableCondition } from '@/utils/formatters';

const props = defineProps({
  visible: { type: Boolean, default: false },
  modelName: { type: String, default: '' },
  record: { type: Object, default: null },
  viewData: { type: Object, required: true }
});

const emit = defineEmits(['update:visible', 'edit']);

const {
  formTabs,
  runtimeFormGroups,
  getFieldMeta,
  isFieldSelectionOrDict,
  isFieldBoolean,
  isFieldManyToOne,
  isFieldFile,
  isWideField,
  getOptionTagLabel,
  getOptionTagColor
} = useRuntimeFieldMeta(() => props.viewData);

const canEditRecord = computed(() => {
  const body = props.viewData.views?.form?.body;
  return (
    !!props.record
    && props.viewData.auth?.update !== false
    && body?.editable !== false
    && evalEnableCondition(body?.editCondition, props.record)
  );
});

async function handleDownloadFile(val) {
  try {
    await downloadFile(val);
  } catch (e) {
    ElMessage.error(e.message);
  }
}
</script>
