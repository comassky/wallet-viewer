package com.comassky.wallet.support;

import java.lang.reflect.Field;

/** Wires manually constructed test instances without widening production field visibility. */
public final class TestFields {
    private TestFields() { }

    public static void setField(Class<?> declaringClass, Object target, String name, Object value) {
        try {
            Field field = declaringClass.getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }
}
