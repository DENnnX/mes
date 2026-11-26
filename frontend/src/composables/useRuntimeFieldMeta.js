import { computed } from 'vue';

export function useRuntimeFieldMeta(viewDataRefOrGetter) {
  const getViewData = () =>
    typeof viewDataRefOrGetter === 'function' ? viewDataRefOrGetter() : viewDataRefOrGetter;

  const formColumns = computed(() => getViewData()?.views?.form?.body?.columns || []);
  const formTabs = computed(() => getViewData()?.views?.form?.body?.tabs || []);

  const runtimeFormGroups = computed(() => {
    const columns = new Map(formColumns.value.map((col) => [col.name, col]));
    const groups = [];
    const configuration = getViewData()?.views?.form?.body?.groupConf;
    if (Array.isArray(configuration)) {
      for (const [index, group] of configuration.entries()) {
        if (!Array.isArray(group?.columns)) continue;
        const members = [];
        for (const name of group.columns) {
          if (columns.has(name)) {
            members.push(columns.get(name));
            columns.delete(name);
          }
        }
        if (members.length) {
          groups.push({ key: index, title: group.title || '', columns: members });
        }
      }
    }
    if (columns.size) {
      groups.push({ key: 'remaining', title: groups.length ? '其他信息' : '', columns: [...columns.values()] });
    }
    return groups;
  });

  const runtimeEditableGroups = computed(() =>
    runtimeFormGroups.value
      .map((group) => ({ ...group, columns: group.columns.filter((col) => !col.detailOnly) }))
      .filter((group) => group.columns.length > 0)
  );

  const runtimeEditableTabs = computed(() => formTabs.value.filter((tab) => !tab.body?.readonly));

  function getFieldMeta(name) {
    return getViewData()?.fields?.[name] || null;
  }

  function getFieldDataType(name) {
    return (getFieldMeta(name)?.dataType || 'string').toLowerCase();
  }

  function isFieldSelectionOrDict(name) {
    const dt = getFieldDataType(name);
    return dt === 'selection' || dt === 'enum' || dt === 'dict';
  }

  function isFieldBoolean(name) {
    return getFieldDataType(name) === 'boolean';
  }

  function isFieldNumber(name) {
    const dt = getFieldDataType(name);
    return ['integer', 'long', 'double', 'bigdecimal', 'decimal'].includes(dt);
  }

  function isFieldManyToOne(name) {
    return getFieldDataType(name) === 'manytoone';
  }

  function isFieldDate(name) {
    const dt = getFieldDataType(name);
    return dt === 'date' || dt === 'datetime';
  }

  function isFieldFile(name) {
    return getFieldDataType(name) === 'file';
  }

  function isFieldRequired(name) {
    return !!getFieldMeta(name)?.required;
  }

  function isWideField(name) {
    const dt = getFieldDataType(name);
    return dt === 'text' || dt === 'json' || dt === 'file';
  }

  function getFieldOptions(name) {
    const vd = getViewData();
    const dyn = vd?.dynamicOptions?.[name];
    if (Array.isArray(dyn) && dyn.length > 0) return dyn;
    const dicts = vd?.dictOptions?.[name];
    if (Array.isArray(dicts) && dicts.length > 0) {
      return dicts.map((d) => ({ value: d.itemValue, label: d.itemLabel, color: d.color || 'info' }));
    }
    return getFieldMeta(name)?.values || [];
  }

  function getOptionTagLabel(name, val) {
    if (val && typeof val === 'object') return val.label || val.displayName || val.value || '-';
    const f = getFieldOptions(name).find((o) => o.value === val);
    return f ? f.label : (val ?? '-');
  }

  function getOptionTagColor(name, val) {
    if (val && typeof val === 'object') return val.color || 'info';
    const f = getFieldOptions(name).find((o) => o.value === val);
    return f?.color || 'info';
  }

  return {
    formColumns,
    formTabs,
    runtimeFormGroups,
    runtimeEditableGroups,
    runtimeEditableTabs,
    getFieldMeta,
    getFieldDataType,
    isFieldSelectionOrDict,
    isFieldBoolean,
    isFieldNumber,
    isFieldManyToOne,
    isFieldDate,
    isFieldFile,
    isFieldRequired,
    isWideField,
    getFieldOptions,
    getOptionTagLabel,
    getOptionTagColor
  };
}
