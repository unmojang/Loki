package org.unmojang.loki.transformers;

import org.objectweb.asm.ClassWriter;

class LoaderAwareClassWriter extends ClassWriter {
    private final ClassLoader loader;

    LoaderAwareClassWriter(int flags, ClassLoader loader) {
        super(flags);
        this.loader = loader != null ? loader : ClassLoader.getSystemClassLoader();
    }

    @Override
    protected String getCommonSuperClass(String type1, String type2) {
        try {
            Class<?> c1 = Class.forName(type1.replace('/', '.'), false, loader);
            Class<?> c2 = Class.forName(type2.replace('/', '.'), false, loader);
            if (c1.isAssignableFrom(c2)) return type1;
            if (c2.isAssignableFrom(c1)) return type2;
            if (c1.isInterface() || c2.isInterface()) return "java/lang/Object";
            do {
                c1 = c1.getSuperclass();
            } while (!c1.isAssignableFrom(c2));
            return c1.getName().replace('.', '/');
        } catch (Throwable t) {
            return "java/lang/Object";
        }
    }
}
