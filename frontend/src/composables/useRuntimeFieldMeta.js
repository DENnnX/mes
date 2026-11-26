export function useRuntimeFieldMeta(viewDataRefOrGetter) {
  const getViewData = () =>
    typeof viewDataRefOrGetter === 'function' ? viewDataRefOrGetter() : viewDataRefOrGetter;

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
