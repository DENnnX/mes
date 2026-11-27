package com.edu.scnu.domain.shared;

import groovy.lang.Binding;
import groovy.lang.GroovyShell;

public final class GroovyScriptShell {
    private static final String LEGACY_NAMESPACE = "com.sie.lowcode.";
    private static final String CURRENT_NAMESPACE = "com.edu.scnu.";
    // Existing model versions can refer to platform classes by their original names.
    private static final ClassLoader SCRIPT_CLASSES = new ClassLoader(GroovyScriptShell.class.getClassLoader()) {
        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.startsWith(LEGACY_NAMESPACE)) {
                name = CURRENT_NAMESPACE + name.substring(LEGACY_NAMESPACE.length());
            }
            return super.loadClass(name, resolve);
        }
    };

    private GroovyScriptShell() {}

    public static GroovyShell create(Binding binding) {
        return new GroovyShell(SCRIPT_CLASSES, binding);
    }
}
